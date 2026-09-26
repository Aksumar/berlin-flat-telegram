package com.aksumar.telegram.client

import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.contract.jsonMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CompletionException

class TelegramClientTest {
    private class PhotoServer(var photoStatus: Int = 200) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, String>>()
        init {
            server.createContext("/bottest/") { exchange ->
                val method = exchange.requestURI.path.substringAfterLast('/')
                requests += method to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val status = if (method == "sendPhoto") photoStatus else 200
                val bytes = "{\"ok\":${status == 200}}".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        fun client() = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
        override fun close() { server.stop(0) }
    }

    private val map = ListingMap(byteArrayOf(1, 2, 3), "https://www.openstreetmap.org/?mlat=52.53&mlon=13.38", false)

    @Test
    fun `uploads photo with caption and both buttons`() {
        PhotoServer().use { server ->
            server.client().sendListing("123", "🏠 Gewobag · Mitte", "https://example.com/123", map).join()
            val (method, body) = server.requests.single()
            assertEquals("sendPhoto", method)
            assertTrue(body.contains("name=\"photo\"; filename=\"map.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
            assertTrue(body.contains("🏠 Gewobag · Mitte"))
            assertTrue(body.contains(map.url))
            assertTrue(body.contains("https://example.com/123"))
            assertFalse(body.contains("disable_notification"))
        }
    }

    @Test
    fun `long caption sends complete text quietly after photo`() {
        PhotoServer().use { server ->
            val text = "🏠 Gewobag\n" + "я".repeat(1100)
            server.client().sendListing("123", text, "https://example.com/123", map.copy(approximate = true)).join()
            assertEquals(listOf("sendPhoto", "sendMessage"), server.requests.map { it.first })
            assertTrue(server.requests[0].second.contains("Примерное расположение"))
            assertFalse(server.requests[0].second.contains("я".repeat(1100)))
            val message = jsonMapper().readTree(server.requests[1].second)
            assertEquals(text, message["text"].asText())
            assertTrue(message["disable_notification"].asBoolean())
        }
    }

    @Test
    fun `caption at limit stays in photo and over limit uses text`() {
        for (size in listOf(1024, 1025)) PhotoServer().use { server ->
            server.client().sendListing("123", "x".repeat(size), "https://example.com/123", map).join()
            assertEquals(if (size == 1024) 1 else 2, server.requests.size)
        }
    }

    @Test
    fun `rejected photo falls back to text while exhausted transient failure propagates`() {
        PhotoServer(400).use { server ->
            server.client().sendListing("123", "listing", "https://example.com/123", map).join()
            assertEquals(listOf("sendPhoto", "sendMessage"), server.requests.map { it.first })
        }
        PhotoServer(500).use { server ->
            val error = assertThrows(CompletionException::class.java) {
                server.client().sendListing("123", "listing", "https://example.com/123", map).join()
            }
            assertTrue((error.cause as TelegramDeliveryException).retryable)
            assertEquals(List(4) { "sendPhoto" }, server.requests.map { it.first })
        }
    }

    @Test
    fun `sends expected JSON`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request = ""

        server.createContext("/bottest/sendMessage") { exchange ->
            request = exchange.requestBody.reader().readText()
            val bytes = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
            client.send("-123", "Привет").join()

            val json = jsonMapper().readTree(request)
            assertEquals("-123", json["chat_id"].asText())
            assertEquals("Привет", json["text"].asText())
            assertTrue(json["link_preview_options"]["is_disabled"].asBoolean())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `retries server errors and succeeds`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0

        server.createContext("/bottest/sendMessage") { exchange ->
            requests++
            val success = requests > 1
            val body = if (success) "{\"ok\":true}" else "{\"ok\":false}"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (success) 200 else 500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            TelegramClient("test", "http://127.0.0.1:${server.address.port}")
                .send("1", "test")
                .join()

            assertEquals(2, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `respects retry after for rate limits`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0

        server.createContext("/bottest/sendMessage") { exchange ->
            requests++
            val success = requests > 1
            val body = if (success) {
                "{\"ok\":true}"
            } else {
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (success) 200 else 429, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            TelegramClient("test", "http://127.0.0.1:${server.address.port}")
                .send("1", "test")
                .join()

            assertEquals(2, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `does not retry permanent client errors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0

        server.createContext("/bottest/sendMessage") { exchange ->
            requests++
            val bytes = "{\"ok\":false}".toByteArray()
            exchange.sendResponseHeaders(400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val client = TelegramClient("test", "http://127.0.0.1:${server.address.port}")
            val ex = assertThrows(CompletionException::class.java) {
                client.send("1", "test").join()
            }

            assertTrue(ex.cause is TelegramDeliveryException)
            assertFalse((ex.cause as TelegramDeliveryException).retryable)
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }
}
