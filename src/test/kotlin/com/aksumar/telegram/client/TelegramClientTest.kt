package com.aksumar.telegram.client

import com.aksumar.telegram.contract.jsonMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CompletionException

class TelegramClientTest {
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
