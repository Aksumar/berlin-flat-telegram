package com.aksumar.telegram

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class TelegramAndConfigTest {
    @Test fun `map is uploaded with caption and buttons and long text is sent silently`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, String>>()
        var status = 200
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = if (status == 200) "{\"ok\":true}".toByteArray() else "secret-token".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = TelegramClient("secret-token", "http://127.0.0.1:${server.address.port}")
            val map = ListingMap(byteArrayOf(1, 2, 3), "https://www.openstreetmap.org/?mlat=52.53&mlon=13.38", true)
            val text = MessageFormatter().format(event())
            client.sendListing("123", text, event().url, map)
            assertEquals(1, requests.size)
            assertTrue(requests[0].first.endsWith("/sendPhoto"))
            val form = requests[0].second
            assertTrue(form.contains("filename=\"map.png\""))
            assertTrue(form.contains(text + "\n📍 Примерное расположение"))
            assertTrue(form.contains("Открыть на карте"))
            assertTrue(form.contains(map.url))
            assertTrue(form.contains(event().url))

            requests.clear()
            val longText = "Gewobag · Mitte\n" + "Длинное описание ".repeat(100)
            client.sendListing("123", longText, event().url, map)
            assertEquals(2, requests.size)
            assertTrue(requests[0].first.endsWith("/sendPhoto"))
            assertFalse(requests[0].second.contains("Длинное описание"))
            val followup = jsonMapper().readTree(requests[1].second)
            assertEquals(longText, followup["text"].asText())
            assertTrue(followup["disable_notification"].asBoolean())

            requests.clear()
            client.sendListing("123", text, event().url, null)
            assertEquals(1, requests.size)
            assertTrue(requests[0].first.endsWith("/sendMessage"))

            requests.clear()
            status = 429
            val ex = assertThrows(DeliveryException::class.java) { client.sendListing("123", longText, event().url, map) }
            assertEquals(1, requests.size)
            assertNull(ex.cause)
            assertFalse(ex.stackTraceToString().contains("secret-token"))
        } finally { server.stop(0) }
    }

    @Test fun `sends expected JSON and rejects errors without exposing token`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request = ""
        var response = "{\"ok\":true}"
        var status = 200
        server.createContext("/botsecret-token/sendMessage") { exchange ->
            request = exchange.requestBody.reader().readText()
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = TelegramClient("secret-token", "http://127.0.0.1:${server.address.port}")
            client.send("-123", "Привет")
            val json = jsonMapper().readTree(request)
            assertEquals("-123", json["chat_id"].asText())
            assertEquals("Привет", json["text"].asText())
            assertTrue(json["link_preview_options"]["is_disabled"].asBoolean())
            for (failure in listOf(200 to "{\"ok\":false}", 429 to "{\"ok\":false}", 500 to "secret-token", 200 to "bad json")) {
                status = failure.first; response = failure.second
                val ex = assertThrows(DeliveryException::class.java) { client.send("1", "test") }
                assertFalse(ex.stackTraceToString().contains("secret-token"))
                assertNull(ex.cause)
            }
        } finally { server.stop(0) }
    }
    @Test fun `chat configuration preserves fallback order and dedup`() {
        val p = AppProperties().apply { chatIds = " 123,456,123,, "; chatId = "789" }
        assertEquals(listOf("123", "456"), p.chats())
        p.chatIds = " "
        assertEquals(listOf("789"), p.chats())
        p.chatId = ""
        assertThrows(IllegalArgumentException::class.java) { p.chats() }
    }
    @Test fun `Kafka configuration keeps manual commits and uses plaintext`() {
        val p = AppProperties().apply { bootstrapServers = "localhost:9092"; chatId = "123" }
        assertEquals(false, p.kafkaConfig()["enable.auto.commit"])
        assertEquals(false, p.kafkaConfig()["allow.auto.create.topics"])
        assertEquals("berlin-flat-telegram-v1", p.kafkaConfig()["group.id"])
        assertEquals("PLAINTEXT", p.kafkaConfig()["security.protocol"])
        assertFalse(p.kafkaConfig().containsKey("sasl.jaas.config"))
        p.bootstrapServers = " "
        assertThrows(IllegalArgumentException::class.java) { p.kafkaConfig() }
    }
}
