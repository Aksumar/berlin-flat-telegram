package com.aksumar.telegram.maps

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

internal class GeoapifyClient(private val apiKey: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun get(
        endpoint: String,
        parameters: Map<String, String>,
        operation: String,
        deadlineNanos: Long,
    ): ByteArray {
        val startedNanos = System.nanoTime()
        val remaining = deadlineNanos - startedNanos
        val timeout = minOf(remaining, REQUEST_TIMEOUT.toNanos())
        // Only host/path are diagnostic data; the query contains the API key.
        val uri = URI.create(endpoint)
        val requestDetails = "operation=$operation, endpoint=${uri.host}${uri.path}, " +
            "timeoutMs=${Duration.ofNanos(timeout.coerceAtLeast(0)).toMillis()}, " +
            "budgetAtRequestStartMs=${Duration.ofNanos(remaining).toMillis()}"
        fun details(): String {
            val now = System.nanoTime()
            return "$requestDetails, requestElapsedMs=${Duration.ofNanos(now - startedNanos).toMillis()}, " +
                "remainingBudgetMs=${Duration.ofNanos(deadlineNanos - now).toMillis()}"
        }
        if (remaining <= 0) throw MapGenerationException("истекло время генерации карты", details())
        val query =
            (parameters + ("apiKey" to apiKey)).entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("$endpoint?$query"))
                .timeout(Duration.ofNanos(timeout))
                .GET()
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: InterruptedException) {
                throw error
            } catch (error: HttpTimeoutException) {
                throw MapGenerationException("Geoapify не ответил за отведённое время", "${details()}, ${error.mapFailureDetails()}")
            } catch (error: Exception) {
                throw MapGenerationException("не удалось выполнить запрос к Geoapify", "${details()}, ${error.mapFailureDetails()}")
            }
        if (response.statusCode() != 200) {
            throw MapGenerationException("Geoapify вернул HTTP ${response.statusCode()}",
                "${details()}, httpStatus=${response.statusCode()}, responseBytes=${response.body().size}")
        }
        if (response.body().size > 5_000_000) {
            throw MapGenerationException("размер ответа Geoapify превысил 5 МБ",
                "${details()}, httpStatus=${response.statusCode()}, responseBytes=${response.body().size}, maxResponseBytes=5000000")
        }
        return response.body()
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
