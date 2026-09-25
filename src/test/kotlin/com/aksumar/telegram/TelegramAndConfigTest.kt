package com.aksumar.telegram

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class TelegramAndConfigTest {
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
    @Test fun `Kafka configuration keeps manual commits and translates SASL`() {
        val p = AppProperties().apply { bootstrapServers = "localhost:9092"; chatId = "123"; securityProtocol = "PLAINTEXT" }
        assertEquals(false, p.kafkaConfig()["enable.auto.commit"])
        assertEquals(false, p.kafkaConfig()["allow.auto.create.topics"])
        assertEquals("berlin-flat-telegram-v1", p.kafkaConfig()["group.id"])
        p.securityProtocol = "SASL_SSL"
        assertThrows(IllegalArgumentException::class.java) { p.kafkaConfig() }
        p.saslUsername = "user"; p.saslPassword = "a\"b\\c"; p.saslMechanism = "SCRAM-SHA-512"
        assertTrue((p.kafkaConfig()["sasl.jaas.config"] as String).contains("ScramLoginModule"))
        assertTrue((p.kafkaConfig()["sasl.jaas.config"] as String).contains("a\\\"b\\\\c"))
    }
}
