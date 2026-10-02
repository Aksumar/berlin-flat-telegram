package com.aksumar.telegram.client

import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.exception.DeliveryException
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

@Component
class TelegramTransport(properties: AppProperties, private val mapper: ObjectMapper) {
    private val endpoint: String
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    init {
        require(properties.botToken.isNotBlank()) { "Telegram token is required" }
        endpoint = "${properties.telegramBaseUrl}/bot${properties.botToken}"
    }

    fun getUpdates(offset: Long): CompletableFuture<com.fasterxml.jackson.databind.JsonNode> =
        request("getUpdates", "application/json", mapper.writeValueAsBytes(mapOf(
            "offset" to offset, "timeout" to 20, "allowed_updates" to listOf("message", "callback_query"),
        ))).thenApply { response ->
            check(response.statusCode() in 200..299 && isTelegramSuccess(response.body())) {
                "Telegram getUpdates failed (HTTP ${response.statusCode()}); check token, webhook and other polling instances"
            }
            mapper.readTree(response.body()).path("result").also {
                check(it.isArray) { "Invalid Telegram updates response" }
            }
        }.exceptionallyCompose {
            // HTTP client exceptions can include the URL containing the bot token.
            CompletableFuture.failedFuture(DeliveryException("Telegram getUpdates failed; check token, webhook and other polling instances"))
        }

    fun answerCallback(id: String): CompletableFuture<Void> = deliver(
        "answerCallbackQuery", "application/json", mapper.writeValueAsBytes(mapOf("callback_query_id" to id)),
    )

    fun configureCommandMenu(): CompletableFuture<Void> =
        deliver("setMyCommands", "application/json", mapper.writeValueAsBytes(mapOf(
            "scope" to mapOf("type" to "all_private_chats"),
            "commands" to listOf(
                mapOf("command" to "start", "description" to "Setup or modify search"),
                mapOf("command" to "stop", "description" to "Pause alerts"),
                mapOf("command" to "help", "description" to "Show help guide"),
            ),
        ))).thenCompose {
            deliver("setChatMenuButton", "application/json", mapper.writeValueAsBytes(mapOf(
                "menu_button" to mapOf("type" to "commands"),
            )))
        }

    fun deliver(
        method: String,
        contentType: String,
        body: ByteArray,
        attempt: Int = 1,
    ): CompletableFuture<Void> =
        request(method, contentType, body)
            .handle { response, failure -> decideDeliveryOutcome(response, failure, attempt) }
            .thenCompose { decision ->
                completeOrRetryDelivery(decision, method, contentType, body, attempt)
            }
            .exceptionallyCompose { error -> propagateDeliveryFailure(error) }

    private fun decideDeliveryOutcome(
        response: HttpResponse<String>?,
        failure: Throwable?,
        attempt: Int,
    ): RetryDecision =
        when {
            failure != null -> RetryDecision.retry(defaultRetryDelay(attempt))
            response!!.statusCode() in 200..299 && isTelegramSuccess(response.body()) ->
                RetryDecision.success()
            response.statusCode() == 429 ->
                RetryDecision.retry(retryAfter(response.body()) ?: defaultRetryDelay(attempt))
            response.statusCode() >= 500 -> RetryDecision.retry(defaultRetryDelay(attempt))
            else -> RetryDecision.fail(retryable = false)
        }

    private fun completeOrRetryDelivery(
        decision: RetryDecision,
        method: String,
        contentType: String,
        body: ByteArray,
        attempt: Int,
    ): CompletableFuture<Void> =
        when {
            decision.success -> CompletableFuture.completedFuture(null)
            decision.retryDelay != null && attempt < MAX_ATTEMPTS ->
                retryDeliveryAfterDelay(method, contentType, body, attempt, decision.retryDelay)
            else ->
                CompletableFuture.failedFuture(
                    TelegramDeliveryException(
                        "Telegram delivery failed",
                        retryable = decision.retryDelay != null || decision.retryable,
                    )
                )
        }

    private fun retryDeliveryAfterDelay(
        method: String,
        contentType: String,
        body: ByteArray,
        attempt: Int,
        delay: Duration,
    ): CompletableFuture<Void> =
        CompletableFuture.runAsync(
                {},
                CompletableFuture.delayedExecutor(delay.seconds, TimeUnit.SECONDS),
            )
            .thenCompose { deliver(method, contentType, body, attempt + 1) }

    private fun propagateDeliveryFailure(error: Throwable): CompletableFuture<Void> {
        val cause = unwrap(error)
        val failure =
            cause as? TelegramDeliveryException
                ?: TelegramDeliveryException("Telegram delivery failed", retryable = true)
        return CompletableFuture.failedFuture(failure)
    }

    private fun request(
        method: String,
        contentType: String,
        body: ByteArray,
    ): CompletableFuture<HttpResponse<String>> {
        return try {
            val request =
                HttpRequest.newBuilder(URI.create("$endpoint/$method"))
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
            }
            .getOrDefault(false)

    private fun retryAfter(body: String?): Duration? =
        runCatching {
                mapper
                    .readTree(body ?: "null")
                    ?.path("parameters")
                    ?.path("retry_after")
                    ?.takeIf { it.isIntegralNumber }
                    ?.asLong()
                    ?.coerceAtLeast(1)
                    ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
                    ?.let(Duration::ofSeconds)
            }
            .getOrNull()

    private fun defaultRetryDelay(attempt: Int): Duration =
        Duration.ofSeconds((1L shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_SECONDS))

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) error.cause!! else error

    private data class RetryDecision(
        val success: Boolean,
        val retryDelay: Duration?,
        val retryable: Boolean,
    ) {
        companion object {
            fun success() = RetryDecision(success = true, retryDelay = null, retryable = false)

            fun retry(delay: Duration) =
                RetryDecision(success = false, retryDelay = delay, retryable = true)

            fun fail(retryable: Boolean) =
                RetryDecision(success = false, retryDelay = null, retryable = retryable)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val MAX_BACKOFF_SECONDS = 4L
        private const val MAX_RETRY_AFTER_SECONDS = 30L
    }
}
