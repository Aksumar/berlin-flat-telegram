package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.TelegramTransport
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.support.subscriptionStore
import com.aksumar.telegram.support.testMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

class TelegramUpdatesTest {
    @Test
    fun `polling receives private commands and confirms saved updates on next request`() {
        val requests = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bottest/getUpdates") { exchange ->
            requests.add(testMapper.readTree(exchange.requestBody))
            val result = if (requests.size == 1) listOf(
                update(10, "/start"), callback(11, FilterCommands.SETUP), callback(12, "Только без WBS"),
                update(13, "50,5"), update(14, "1000"), callback(15, FilterCommands.SAVE),
            ) else emptyList()
            val body = testMapper.writeValueAsBytes(mapOf("ok" to true, "result" to result))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/bottest/answerCallbackQuery") { exchange ->
            exchange.requestBody.close()
            val body = "{\"ok\":true,\"result\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = subscriptionStore()
            val replies = mutableListOf<String>()
            val poller = TelegramUpdates(transport(server.address.port), TelegramSender { chat, text ->
                assertEquals("123", chat)
                replies += text
                CompletableFuture.completedFuture(null)
            }, FilterCommands(store, testMapper), store)
            poller.poll()
            poller.poll()
            assertEquals(listOf(0L, 16L), requests.map { it.path("offset").asLong() })
            assertEquals(listOf("message", "callback_query"), requests[0].path("allowed_updates").map { it.asText() })
            assertEquals(6, replies.size)
            assertEquals(ListingFilter(WbsFilter.NOT_REQUIRED, "50.50".toBigDecimal(), "1000.00".toBigDecimal()),
                store.get("123")!!.filter)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `transient reply failure retries same update while permanent rejection advances offset`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bottest/getUpdates") { exchange ->
            exchange.requestBody.close()
            val body = testMapper.writeValueAsBytes(mapOf("ok" to true, "result" to listOf(update(20, "/start"))))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/bottest/answerCallbackQuery") { exchange ->
            exchange.requestBody.close()
            val body = "{\"ok\":true,\"result\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = subscriptionStore()
            var retryable = true
            val poller = TelegramUpdates(transport(server.address.port), TelegramSender { _, _ ->
                CompletableFuture.failedFuture(TelegramDeliveryException("rejected", retryable))
            }, FilterCommands(store, testMapper), store)
            poller.poll()
            assertEquals(0L, store.nextOffset())
            assertNull(store.get("123"))
            retryable = false
            poller.poll()
            assertEquals(21L, store.nextOffset())
        } finally {
            server.stop(0)
        }
    }

    private fun transport(port: Int) = TelegramTransport(AppProperties().apply {
        botToken = "test"
        telegramBaseUrl = "http://127.0.0.1:$port"
    }, testMapper)

    private fun callback(id: Long, data: String) = mapOf(
        "update_id" to id,
        "callback_query" to mapOf("id" to "callback-$id", "data" to data,
            "from" to mapOf("id" to 123, "is_bot" to false),
            "message" to mapOf("chat" to mapOf("id" to 123, "type" to "private"))),
    )

    private fun update(id: Long, text: String) = mapOf(
        "update_id" to id,
        "message" to mapOf("text" to text, "chat" to mapOf("id" to 123, "type" to "private"),
            "from" to mapOf("id" to 123, "is_bot" to false)),
    )
}
