package com.aksumar.telegram.client

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.contract.DeliveryException
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
}

class TelegramDeliveryException(
    message: String,
    val retryable: Boolean
) : DeliveryException(message)

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
    private val endpoint: URI
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    init {
        require(token.isNotBlank()) { "Telegram token is required" }
        endpoint = URI.create("$baseUrl/bot$token/sendMessage")
    }

    override fun send(chat: String, text: String): CompletableFuture<Void> =
        send(chat, text, attempt = 1)

    private fun send(chat: String, text: String, attempt: Int): CompletableFuture<Void> {
        return request(chat, text).handle { response, failure ->
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
                decision.success -> CompletableFuture.completedFuture(null)
                decision.retryDelay != null && attempt < MAX_ATTEMPTS ->
                    CompletableFuture
                        .runAsync(
                            {},
                            CompletableFuture.delayedExecutor(
                                decision.retryDelay.seconds,
                                TimeUnit.SECONDS
                            )
                        )
                        .thenCompose { send(chat, text, attempt + 1) }

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

    private fun request(chat: String, text: String): CompletableFuture<HttpResponse<String>> {
        return try {
            val body = mapper.writeValueAsString(
                mapOf(
                    "chat_id" to chat,
                    "text" to text,
                    "link_preview_options" to mapOf("is_disabled" to true)
                )
            )

            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
