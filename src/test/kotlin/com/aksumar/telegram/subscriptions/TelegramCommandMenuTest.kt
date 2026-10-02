package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.client.TelegramTransport
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.support.testMapper
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class TelegramCommandMenuTest {
    private class MenuServer : AutoCloseable {
        val requests = mutableListOf<Pair<String, JsonNode>>()
        var menuStatus = 200
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/bottest/") { exchange ->
                val method = exchange.requestURI.path.substringAfterLast('/')
                requests += method to testMapper.readTree(exchange.requestBody)
                val status = if (method == "setChatMenuButton") menuStatus else 200
                val body = "{\"ok\":${status == 200},\"result\":true}".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        fun menu() = TelegramCommandMenu(TelegramTransport(AppProperties().apply {
            botToken = "test"
            telegramBaseUrl = "http://127.0.0.1:${server.address.port}"
        }, testMapper))

        override fun close() = server.stop(0)
    }

    @Test
    fun `registers private chat commands and native menu button once per start`() {
        MenuServer().use { server ->
            val menu = server.menu()
            menu.register()
            menu.register()
            assertEquals(listOf("setMyCommands", "setChatMenuButton"), server.requests.map { it.first })
            val commands = server.requests[0].second
            assertEquals("all_private_chats", commands.path("scope").path("type").asText())
            assertEquals(listOf("start", "stop", "help"), commands.path("commands").map { it.path("command").asText() })
            assertEquals(listOf("Setup or modify search", "Pause alerts", "Show help guide"),
                commands.path("commands").map { it.path("description").asText() })
            val button = server.requests[1].second
            assertEquals("commands", button.path("menu_button").path("type").asText())
            assertFalse(button.has("chat_id"))
        }
    }

    @Test
    fun `retries incomplete registration and registers again after application restart`() {
        MenuServer().use { server ->
            val menu = server.menu()
            server.menuStatus = 400
            menu.register()
            server.menuStatus = 200
            menu.register()
            menu.register()
            assertEquals(4, server.requests.size)
            server.menu().register()
            assertEquals(6, server.requests.size)
        }
    }
}
