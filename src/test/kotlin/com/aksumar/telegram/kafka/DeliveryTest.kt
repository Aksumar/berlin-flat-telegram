package com.aksumar.telegram.kafka

import com.aksumar.telegram.client.TelegramDeliveryException
import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.contract.ListingContract
import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.support.fixture
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DeliveryTest {
    private fun properties() = AppProperties().apply {
        chatIds = listOf("123", "456")
    }

    private fun record(
        value: String = fixture(),
        key: String = "[\"gewobag\",\"123\"]"
    ) = ConsumerRecord("test", 0, 0, key, value)

    private fun listener(sender: TelegramSender) =
        NewFlatEventListener(
            ListingContract(),
            MessageFormatter(),
            sender,
            properties()
        )

    @Test
    fun `creates map once and waits for photo delivery to all chats`() {
        var mapCalls = 0
        val expectedMap = ListingMap(byteArrayOf(1), "https://example.com/map", false)
        val completions = mutableListOf<CompletableFuture<Void>>()
        val sender = object : TelegramSender {
            override fun send(chat: String, text: String): CompletableFuture<Void> = error("Expected photo")
            override fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?): CompletableFuture<Void> {
                assertSame(expectedMap, map)
                assertTrue(text.startsWith("🏠 Gewobag"))
                return CompletableFuture<Void>().also { completions += it }
            }
        }
        val listener = NewFlatEventListener(ListingContract(), MessageFormatter(), sender, properties(),
            ListingMaps { mapCalls++; expectedMap })
        val result = listener.receive(record())
        assertEquals(1, mapCalls)
        assertEquals(2, completions.size)
        assertFalse(result.isDone)
        completions[0].complete(null)
        assertFalse(result.isDone)
        completions[1].complete(null)
        result.join()
    }

    @Test
    fun `map failure still delivers text to every chat`() {
        val delivered = mutableListOf<String>()
        val sender = TelegramSender { chat, _ ->
            delivered += chat
            CompletableFuture.completedFuture(null)
        }
        NewFlatEventListener(ListingContract(), MessageFormatter(), sender, properties(),
            ListingMaps { error("provider unavailable") }).receive(record()).join()
        assertEquals(listOf("123", "456"), delivered)
    }

    @Test
    fun `successful send completes after every configured chat`() {
        val delivered = mutableListOf<String>()
        val future = listener { chat, _ ->
            delivered += chat
            CompletableFuture.completedFuture(null)
        }.receive(record())

        future.join()

        assertEquals(listOf("123", "456"), delivered)
        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun `permanent Telegram rejection is skipped`() {
        val delivered = mutableListOf<String>()
        val future = listener { chat, _ ->
            delivered += chat
            if (chat == "456") {
                CompletableFuture.failedFuture(
                    TelegramDeliveryException("fail", retryable = false)
                )
            } else {
                CompletableFuture.completedFuture(null)
            }
        }.receive(record())

        future.join()

        assertEquals(listOf("123", "456"), delivered)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun `transient Telegram failure remains exceptional`() {
        val future = listener { chat, _ ->
            if (chat == "456") {
                CompletableFuture.failedFuture(
                    TelegramDeliveryException("fail", retryable = true)
                )
            } else {
                CompletableFuture.completedFuture(null)
            }
        }.receive(record())

        val error = assertThrows(CompletionException::class.java) {
            future.join()
        }

        assertTrue(error.cause is TelegramDeliveryException)
        assertTrue((error.cause as TelegramDeliveryException).retryable)
    }

    @Test
    fun `unknown delivery failure is treated as transient`() {
        val future = listener { chat, _ ->
            if (chat == "456") {
                CompletableFuture.failedFuture(IllegalStateException("fail"))
            } else {
                CompletableFuture.completedFuture(null)
            }
        }.receive(record())

        assertThrows(CompletionException::class.java) {
            future.join()
        }
    }

    @Test
    fun `invalid record is skipped without sending`() {
        val listener = listener { _, _ ->
            fail<Unit>("Must not send")
            CompletableFuture.completedFuture(null)
        }

        for (record in listOf(
            record(fixture().replace("\"version\": 2", "\"version\": 3")),
            record(key = "wrong")
        )) {
            listener.receive(record).join()
        }
    }
}
