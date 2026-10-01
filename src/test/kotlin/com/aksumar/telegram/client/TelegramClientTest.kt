package com.aksumar.telegram.client

import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.support.testMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TelegramClientTest {
    private class PhotoServer(var photoStatus: Int = 200, var textStatus: Int = 200) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, String>>()

        init {
            server.createContext("/bottest/") { exchange ->
                val method = exchange.requestURI.path.substringAfterLast('/')
                requests += method to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val status = if (method == "sendPhoto") photoStatus else textStatus
                val bytes = "{\"ok\":${status == 200}}".toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun client() =
            TelegramClient(
                TelegramTransport(
                    AppProperties().apply {
                        botToken = "test"
                        telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
                    },
                    testMapper,
                ),
                testMapper,
            )

        override fun close() {
            server.stop(0)
        }
    }

    private val map = ListingMap(byteArrayOf(1, 2, 3), false)
    private val mapUrl = "https://www.google.com/maps/search/?api=1&query=Musterstra%C3%9Fe+12%2C+Berlin"

    @Test
    fun `uploads photo with caption and both buttons`() {
        PhotoServer().use { server ->
            server
                .client()
                .sendListing("123", "Straße & <Platz> 🏠 12, Berlin · Mitte\nПлощадь: 39,1 м²", "https://example.com/123", map, mapUrl)
                .join()
            val (method, body) = server.requests.single()
            assertEquals("sendPhoto", method)
            assertTrue(body.contains("name=\"photo\"; filename=\"map.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
            assertTrue(body.contains("Straße & <Platz> 🏠 12, Berlin · Mitte\nПлощадь: 39,1 м²"))
            val entities = testMapper.readTree(
                body.substringAfter("name=\"caption_entities\"\r\n\r\n").substringBefore("\r\n")
            )
            assertEquals(testMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                listOf(mapOf("type" to "bold", "offset" to 0, "length" to "Straße & <Platz> 🏠 12, Berlin · Mitte".length))
            ), entities)
            assertTrue(body.contains(mapUrl))
            assertTrue(body.contains("https://example.com/123"))
            val keyboard = testMapper.readTree(
                body.substringAfter("name=\"reply_markup\"\r\n\r\n").substringBefore("\r\n")
            )["inline_keyboard"]
            assertEquals(1, keyboard.size())
            assertEquals(2, keyboard[0].size())
            assertEquals("Объявление", keyboard[0][0]["text"].asText())
            assertEquals("📍 Карта", keyboard[0][1]["text"].asText())
            assertFalse(body.contains("disable_notification"))
        }
    }

    @Test
    fun `long caption sends complete text quietly after photo`() {
        PhotoServer().use { server ->
            val text = "Musterstraße 12, 10115 Berlin · Mitte\n" + "я".repeat(1100)
            server
                .client()
                .sendListing("123", text, "https://example.com/123", map.copy(approximate = true), mapUrl)
                .join()
            assertEquals(listOf("sendPhoto", "sendMessage"), server.requests.map { it.first })
            assertTrue(server.requests[0].second.contains("Примерное расположение"))
            assertFalse(server.requests[0].second.contains("я".repeat(1100)))
            val message = testMapper.readTree(server.requests[1].second)
            assertEquals(text, message["text"].asText())
            assertTrue(message["disable_notification"].asBoolean())
            assertEquals("bold", message["entities"][0]["type"].asText())
            assertEquals(0, message["entities"][0]["offset"].asInt())
            assertEquals(text.substringBefore('\n').length, message["entities"][0]["length"].asInt())
        }
    }

    @Test
    fun `caption at limit stays in photo and over limit uses text`() {
        for (size in listOf(1024, 1025)) PhotoServer().use { server ->
            server
                .client()
                .sendListing("123", "x".repeat(size), "https://example.com/123", map, mapUrl)
                .join()
            assertEquals(if (size == 1024) 1 else 2, server.requests.size)
        }
    }

    @Test
    fun `missing map still sends text with listing and address buttons`() {
        PhotoServer().use { server ->
            server.client().sendListing("123", "listing", "https://example.com/123", null, mapUrl).join()
            val (method, body) = server.requests.single()
            assertEquals("sendMessage", method)
            val message = testMapper.readTree(body)
            assertEquals("listing", message["text"].asText())
            assertEquals("bold", message["entities"][0]["type"].asText())
            assertEquals(0, message["entities"][0]["offset"].asInt())
            assertEquals(7, message["entities"][0]["length"].asInt())
            val buttons = message["reply_markup"]["inline_keyboard"]
            assertEquals(1, buttons.size())
            assertEquals(2, buttons[0].size())
            assertEquals("Объявление", buttons[0][0]["text"].asText())
            assertEquals("📍 Карта", buttons[0][1]["text"].asText())
            assertEquals("https://example.com/123", buttons[0][0]["url"].asText())
            assertEquals(mapUrl, buttons[0][1]["url"].asText())
            assertFalse(message["disable_notification"].asBoolean())
        }
    }

    @Test
    fun `missing address retains listing button without inventing map location`() {
        PhotoServer().use { server ->
            server.client().sendListing("123", "listing", "https://example.com/123", null, null).join()
            val message = testMapper.readTree(server.requests.single().second)
            val buttons = message["reply_markup"]["inline_keyboard"]
            assertEquals(1, buttons.size())
            assertEquals("https://example.com/123", buttons[0][0]["url"].asText())
        }
    }

    @Test
    fun `rejected photo and exhausted photo retries fall back to text with buttons`() {
        for (status in listOf(400, 500)) PhotoServer(status).use { server ->
            server.client().sendListing("123", "listing", "https://example.com/123", map, mapUrl).join()
            assertEquals(
                List(if (status == 400) 1 else 4) { "sendPhoto" } + "sendMessage",
                server.requests.map { it.first },
            )
            val message = testMapper.readTree(server.requests.last().second)
            assertEquals("listing", message["text"].asText())
            assertEquals("bold", message["entities"][0]["type"].asText())
            assertEquals(0, message["entities"][0]["offset"].asInt())
            assertEquals(7, message["entities"][0]["length"].asInt())
            val buttons = message["reply_markup"]["inline_keyboard"]
            assertEquals(1, buttons.size())
            assertEquals(2, buttons[0].size())
            assertEquals("https://example.com/123", buttons[0][0]["url"].asText())
            assertEquals(mapUrl, buttons[0][1]["url"].asText())
        }
    }

    @Test
    fun `transient text fallback failure still propagates for Kafka recovery`() {
        PhotoServer(400, 500).use { server ->
            val error =
                assertThrows(CompletionException::class.java) {
                    server
                        .client()
                        .sendListing("123", "listing", "https://example.com/123", map, mapUrl)
                        .join()
                }
            assertTrue((error.cause as TelegramDeliveryException).retryable)
            assertEquals(listOf("sendPhoto") + List(4) { "sendMessage" }, server.requests.map { it.first })
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
            val client =
                TelegramClient(
                    TelegramTransport(
                        AppProperties().apply {
                            botToken = "test"
                            telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
                        },
                        testMapper,
                    ),
                    testMapper,
                )
            client.send("-123", "Привет").join()

            val json = testMapper.readTree(request)
            assertEquals("-123", json["chat_id"].asText())
            assertEquals("Привет", json["text"].asText())
            assertFalse(json.has("entities"))
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
            TelegramClient(
                    TelegramTransport(
                        AppProperties().apply {
                            botToken = "test"
                            telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
                        },
                        testMapper,
                    ),
                    testMapper,
                )
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
            val body =
                if (success) {
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
            TelegramClient(
                    TelegramTransport(
                        AppProperties().apply {
                            botToken = "test"
                            telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
                        },
                        testMapper,
                    ),
                    testMapper,
                )
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
            val client =
                TelegramClient(
                    TelegramTransport(
                        AppProperties().apply {
                            botToken = "test"
                            telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
                        },
                        testMapper,
                    ),
                    testMapper,
                )
            val ex =
                assertThrows(CompletionException::class.java) { client.send("1", "test").join() }

            assertTrue(ex.cause is TelegramDeliveryException)
            assertFalse((ex.cause as TelegramDeliveryException).retryable)
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }
}
