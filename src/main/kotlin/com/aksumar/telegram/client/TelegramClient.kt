package com.aksumar.telegram.client

import com.aksumar.telegram.maps.ListingMap
import java.io.ByteArrayOutputStream
import java.util.UUID
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.contract.exceptions.DeliveryException
import com.aksumar.telegram.contract.jsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

fun interface TelegramSender {
    fun send(chat: String, text: String): CompletableFuture<Void>
    fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?): CompletableFuture<Void> =
        send(chat, text)
}

@Component
class TelegramClient internal constructor(
    token: String,
    baseUrl: String
) : TelegramSender {
    @Autowired
    constructor(properties: AppProperties) : this(
        properties.botToken,
        "https://api.telegram.org"
    )

    private val mapper = jsonMapper()
    private val endpoint: String
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    init {
        require(token.isNotBlank()) { "Telegram token is required" }
        endpoint = "$baseUrl/bot$token"
    }

    override fun send(chat: String, text: String): CompletableFuture<Void> = sendText(chat, text)

    private fun sendText(chat: String, text: String, silent: Boolean = false): CompletableFuture<Void> =
        deliver("sendMessage", "application/json", mapper.writeValueAsBytes(mapOf(
            "chat_id" to chat, "text" to text, "disable_notification" to silent,
            "link_preview_options" to mapOf("is_disabled" to true)
        )))

    override fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?): CompletableFuture<Void> {
        if (map == null) return send(chat, text)
        val note = if (map.approximate) "\n📍 Примерное расположение" else ""
        val fits = text.length + note.length <= 1024
        val heading = text.substringBefore('\n').take(900).dropLastWhile { it.isHighSurrogate() }
        val caption = (if (fits) text else heading) + note
        val keyboard = mapOf("inline_keyboard" to listOf(
            listOf(mapOf("text" to "📍 Открыть на карте", "url" to map.url)),
            listOf(mapOf("text" to "Посмотреть объявление ↗", "url" to listingUrl))
        ))
        val boundary = "map-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream().use { out ->
            fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
            for ((name, value) in mapOf("chat_id" to chat, "caption" to caption,
                "reply_markup" to mapper.writeValueAsString(keyboard))) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            write("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"map.png\"\r\nContent-Type: image/png\r\n\r\n")
            out.write(map.png)
            write("\r\n--$boundary--\r\n")
            out.toByteArray()
        }
        // A rejected photo must not discard the listing. Retry transient failures
        // through the normal delivery path so Kafka retains uncommitted records.
        return deliver("sendPhoto", "multipart/form-data; boundary=$boundary", body)
            .handle { _, failure ->
                if (failure == null) {
                    if (fits) CompletableFuture.completedFuture<Void>(null) else sendText(chat, text, silent = true)
                } else {
                    val cause = unwrap(failure)
                    if (cause is TelegramDeliveryException && !cause.retryable) send(chat, text)
                    else CompletableFuture.failedFuture<Void>(cause)
                }
            }.thenCompose { it }
    }

    private fun deliver(method: String, contentType: String, body: ByteArray, attempt: Int = 1): CompletableFuture<Void> {
        return request(method, contentType, body).handle { response, failure ->
            when {
                failure != null -> RetryDecision.retry(defaultRetryDelay(attempt))
                response!!.statusCode() in 200..299 && isTelegramSuccess(response.body()) ->
                    RetryDecision.success()

                response.statusCode() == 429 ->
                    RetryDecision.retry(retryAfter(response.body()) ?: defaultRetryDelay(attempt))

                response.statusCode() >= 500 ->
                    RetryDecision.retry(defaultRetryDelay(attempt))

                else ->
                    RetryDecision.fail(retryable = false)
            }
        }.thenCompose { decision ->
            when {
                decision.success -> CompletableFuture.completedFuture<Void>(null)
                decision.retryDelay != null && attempt < MAX_ATTEMPTS ->
                    CompletableFuture
                        .runAsync(
                            {},
                            CompletableFuture.delayedExecutor(
                                decision.retryDelay.seconds,
                                TimeUnit.SECONDS
                            )
                        )
                        .thenCompose { deliver(method, contentType, body, attempt + 1) }

                else ->
                    CompletableFuture.failedFuture(
                        TelegramDeliveryException(
                            "Telegram delivery failed",
                            retryable = decision.retryDelay != null || decision.retryable
                        )
                    )
            }
        }.exceptionallyCompose { error ->
            val cause = unwrap(error)
            if (cause is TelegramDeliveryException) {
                CompletableFuture.failedFuture(cause)
            } else {
                CompletableFuture.failedFuture(
                    TelegramDeliveryException(
                        "Telegram delivery failed",
                        retryable = true
                    )
                )
            }
        }
    }

    private fun request(method: String, contentType: String, body: ByteArray): CompletableFuture<HttpResponse<String>> {
        return try {
            val request = HttpRequest.newBuilder(URI.create("$endpoint/$method"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            CompletableFuture.failedFuture(DeliveryException("Telegram request failed"))
        }
    }

    private fun isTelegramSuccess(body: String?): Boolean =
        runCatching {
            val ok = mapper.readTree(body ?: "null")?.get("ok")
            ok?.isBoolean == true && ok.booleanValue()
        }.getOrDefault(false)

    private fun retryAfter(body: String?): Duration? =
        runCatching {
            mapper.readTree(body ?: "null")
                ?.path("parameters")
                ?.path("retry_after")
                ?.takeIf { it.isIntegralNumber }
                ?.asLong()
                ?.coerceAtLeast(1)
                ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
                ?.let(Duration::ofSeconds)
        }.getOrNull()

    private fun defaultRetryDelay(attempt: Int): Duration =
        Duration.ofSeconds((1L shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_SECONDS))

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) error.cause!! else error

    private data class RetryDecision(
        val success: Boolean,
        val retryDelay: Duration?,
        val retryable: Boolean
    ) {
        companion object {
            fun success() = RetryDecision(success = true, retryDelay = null, retryable = false)
            fun retry(delay: Duration) = RetryDecision(success = false, retryDelay = delay, retryable = true)
            fun fail(retryable: Boolean) = RetryDecision(success = false, retryDelay = null, retryable = retryable)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val MAX_BACKOFF_SECONDS = 4L
        private const val MAX_RETRY_AFTER_SECONDS = 30L
    }
}
