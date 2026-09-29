package com.aksumar.telegram.maps

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory

internal class GeoapifyClient(private val apiKey: String) {
    private val logger = LoggerFactory.getLogger(GeoapifyClient::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun get(
        endpoint: String,
        parameters: Map<String, String>,
        operation: String,
        deadlineNanos: Long,
    ): ByteArray {
        val remaining = deadlineNanos - System.nanoTime()
        check(remaining > 0) { "Map time budget exceeded" }
        val query =
            (parameters + ("apiKey" to apiKey)).entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("$endpoint?$query"))
                .timeout(Duration.ofNanos(minOf(remaining, REQUEST_TIMEOUT.toNanos())))
                .GET()
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Geoapify {} request failed", operation)
                throw error
            }
        if (response.statusCode() != 200) {
            logger.warn("Geoapify {} returned HTTP {}", operation, response.statusCode())
            error("Geoapify request failed")
        }
        if (response.body().size > 5_000_000) {
            logger.warn("Geoapify {} response exceeded 5 MB", operation)
            error("Geoapify response too large")
        }
        return response.body()
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
