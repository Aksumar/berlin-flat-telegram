package com.aksumar.telegram.maps

import io.micrometer.core.instrument.Metrics
import java.net.http.HttpConnectTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import org.slf4j.LoggerFactory

internal class GeoapifyClient(
    private val apiKey: String,
    private val metrics: MapMetrics = MapMetrics(Metrics.globalRegistry),
) {
    private val log = LoggerFactory.getLogger(GeoapifyClient::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun get(
        endpoint: String,
        parameters: Map<String, String>,
        operation: String,
        deadlineNanos: Long,
    ): ByteArray = metrics.measure("telegram.maps.request", operation.replace(' ', '_')) {
        request(endpoint, parameters, operation, deadlineNanos)
    }

    private fun request(
        endpoint: String,
        parameters: Map<String, String>,
        operation: String,
        deadlineNanos: Long,
    ): ByteArray {
        val startedNanos = System.nanoTime()
        val remaining = deadlineNanos - startedNanos
        val timeout = minOf(remaining, REQUEST_TIMEOUT.toNanos())
        val uri = URI.create(endpoint)
        // Build diagnostics separately from the authenticated request.
        val safeEndpoint = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null)
        val safeQuery = parameters.filterKeys { !it.equals("apiKey", ignoreCase = true) }
            .entries.joinToString("&") { "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val safeRequest = "$safeEndpoint${if (safeQuery.isEmpty()) "" else "?$safeQuery"}"
        val requestDetails = "operation=$operation, endpoint=${uri.host}${uri.path}, request=$safeRequest, " +
            "timeoutMs=${Duration.ofNanos(timeout.coerceAtLeast(0)).toMillis()}, " +
            "budgetAtRequestStartMs=${Duration.ofNanos(remaining).toMillis()}"
        fun details(): String {
            val now = System.nanoTime()
            return "$requestDetails, requestElapsedMs=${Duration.ofNanos(now - startedNanos).toMillis()}, " +
                "remainingBudgetMs=${Duration.ofNanos(deadlineNanos - now).toMillis()}"
        }
        if (remaining <= 0) throw MapGenerationException("истекло время генерации карты", details(), "budget_exhausted")
        val query =
            (parameters + ("apiKey" to apiKey)).entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("$endpoint?$query"))
                .timeout(Duration.ofNanos(timeout))
                .GET()
                .build()
        log.info("Geoapify request: {}", requestDetails)
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: InterruptedException) {
                throw error
            } catch (error: HttpTimeoutException) {
                throw MapGenerationException("Geoapify не ответил за отведённое время", "${details()}, ${error.mapFailureDetails()}",
                    if (error is HttpConnectTimeoutException) "connect_timeout" else "timeout")
            } catch (error: Exception) {
                throw MapGenerationException("не удалось выполнить запрос к Geoapify", "${details()}, ${error.mapFailureDetails()}")
            }
        if (response.statusCode() != 200) {
            throw MapGenerationException("Geoapify вернул HTTP ${response.statusCode()}",
                "${details()}, httpStatus=${response.statusCode()}, responseBytes=${response.body().size}", "http_error")
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
