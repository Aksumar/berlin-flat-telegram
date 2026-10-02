package com.aksumar.telegram.kafka

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.maps.MapGenerationException
import com.aksumar.telegram.support.fixture
import com.aksumar.telegram.support.testMapper
import com.aksumar.telegram.support.subscriptionStore
import com.aksumar.telegram.subscriptions.ListingFilter
import com.aksumar.telegram.subscriptions.Subscription
import com.aksumar.telegram.subscriptions.SubscriptionStore
import com.aksumar.telegram.support.event
import org.mockito.Mockito
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class DeliveryTest {
    private fun subscriptions() = subscriptionStore(listOf("123", "456"))

    private fun record(value: String = fixture(), key: String = "[\"gewobag\",\"123\"]") =
        ConsumerRecord("test", 0, 0, key, value)

    private fun listener(sender: TelegramSender) =
        NewFlatEventListener(
            ListingContract(testMapper),
            MessageFormatter(),
            sender,
            subscriptions(),
            testMapper,
        )

    @Test
    fun `unresolved district is logged but omitted from message`(output: CapturedOutput) {
        val delivered = mutableListOf<String>()
        val sender = TelegramSender { _, text ->
            delivered += text
            CompletableFuture.completedFuture(null)
        }
        val payload = testMapper.readTree(fixture()) as com.fasterxml.jackson.databind.node.ObjectNode
        (payload.path("address") as com.fasterxml.jackson.databind.node.ObjectNode).put("district", " ")
        listener(sender).receive(record(testMapper.writeValueAsString(payload))).join()
        assertEquals(2, delivered.size)
        delivered.forEach {
            assertEquals("Musterstraße 12, 10115 Berlin", it.lineSequence().first())
            assertFalse(it.contains("район не определён"))
        }
        assertTrue(output.out.contains("Не удалось определить район; source=gewobag, id=123"))
    }

    @Test
    fun `all sources use resolved district even when map fails and preserve supplied district`() {
        val sources = listOf("allod", "rbb", "berlinhaus", "berlinovo", "gewobag", "wbm",
            "degewo", "inberlinwohnen", "deutschewohnen", "howoge")
        for (source in sources) {
            for (supplied in listOf(null, " ", "Wedding")) {
                val payload = testMapper.readTree(fixture()) as com.fasterxml.jackson.databind.node.ObjectNode
                payload.put("source", source)
                (payload.path("address") as com.fasterxml.jackson.databind.node.ObjectNode).put("district", supplied)
                val delivered = mutableListOf<String>()
                val sender = object : TelegramSender {
                    override fun send(chat: String, text: String): CompletableFuture<Void> {
                        delivered += text
                        return CompletableFuture.completedFuture(null)
                    }
                }
                val maps = object : ListingMaps {
                    override fun create(item: com.aksumar.telegram.model.Listing): ListingMap? = error("Expected enrichment")
                    override fun create(item: com.aksumar.telegram.model.Listing, onDistrictResolved: (String) -> Unit): ListingMap? {
                        onDistrictResolved("Mitte")
                        throw MapGenerationException("тестовая ошибка карты")
                    }
                }
                val listener = NewFlatEventListener(ListingContract(testMapper), MessageFormatter(), sender,
                    subscriptions(), testMapper, maps)
                listener.receive(record(testMapper.writeValueAsString(payload), "[\"$source\",\"123\"]")).join()
                assertEquals(2, delivered.size, source)
                delivered.forEach { assertTrue(it.lineSequence().first().endsWith(" · ${supplied?.takeIf { it.isNotBlank() } ?: "Mitte"}"), it) }
            }
        }
    }

    @Test
    fun `creates map once and waits for photo delivery to all chats`() {
        var mapCalls = 0
        val expectedMap = ListingMap(byteArrayOf(1), false)
        val completions = mutableListOf<CompletableFuture<Void>>()
        val sender =
            object : TelegramSender {
                override fun send(chat: String, text: String): CompletableFuture<Void> =
                    error("Expected photo")

                override fun sendListing(
                    chat: String,
                    text: String,
                    listingUrl: String,
                    map: ListingMap?,
                    mapUrl: String?,
                ): CompletableFuture<Void> {
                    assertSame(expectedMap, map)
                    assertEquals(
                        "https://www.google.com/maps/search/?api=1&query=Musterstra%C3%9Fe+12%2C+10115+Berlin%2C+Mitte",
                        mapUrl,
                    )
                    assertTrue(text.startsWith("Musterstraße 12, 10115 Berlin · Mitte\n"))
                    assertFalse(text.contains("Карта не сгенерирована"))
                    return CompletableFuture<Void>().also { completions += it }
                }
            }
        val listener =
            NewFlatEventListener(
                ListingContract(testMapper),
                MessageFormatter(),
                sender,
                subscriptions(),
                testMapper,
                ListingMaps {
                    mapCalls++
                    expectedMap
                },
            )
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
    fun `map failure still delivers text with reason to every chat and logs warning`(output: CapturedOutput) {
        val delivered = mutableListOf<String>()
        var expectedReason = ""
        val sender = object : TelegramSender {
            override fun send(chat: String, text: String): CompletableFuture<Void> =
                error("Expected listing with independent map link")

            override fun sendListing(
                chat: String,
                text: String,
                listingUrl: String,
                map: ListingMap?,
                mapUrl: String?,
            ): CompletableFuture<Void> {
                assertNull(map)
                assertTrue(text.endsWith("⚠️ Карта не сгенерирована, потому что $expectedReason."))
                assertFalse(text.contains("test-secret"))
                assertEquals("https://example.com/123", listingUrl)
                assertEquals(
                    "https://www.google.com/maps/search/?api=1&query=Musterstra%C3%9Fe+12%2C+10115+Berlin%2C+Mitte",
                    mapUrl,
                )
                delivered += chat
                return CompletableFuture.completedFuture(null)
            }
        }
        val failures = listOf(
            ListingMaps { throw MapGenerationException("Geoapify вернул HTTP 429", "operation=detail map, httpStatus=429, timeoutMs=20000") } to "Geoapify вернул HTTP 429",
            ListingMaps { error("https://provider.invalid/?apiKey=test-secret") } to "произошла непредвиденная ошибка генерации карты",
            ListingMaps { null } to "сервис генерации карты не вернул изображение",
        )
        for ((maps, reason) in failures) {
            delivered.clear()
            expectedReason = reason
            NewFlatEventListener(
                ListingContract(testMapper),
                MessageFormatter(),
                sender,
                subscriptions(),
                testMapper,
                maps,
            )
            .receive(record())
            .join()
            assertEquals(listOf("123", "456"), delivered)
            assertTrue(output.out.lineSequence().any {
                it.contains("WARN") && it.contains("Карта не сгенерирована, потому что $reason") &&
                    it.contains("source=gewobag, id=123") && it.contains("address=Musterstraße 12") &&
                    it.contains("topic=test, partition=0, offset=0") && it.contains("elapsedMs=")
            })
        }
        assertTrue(output.all.contains("operation=detail map, httpStatus=429, timeoutMs=20000"))
        assertTrue(output.all.contains("exception=java.lang.IllegalStateException"))
        assertTrue(output.all.contains("at=com.aksumar.telegram.kafka.DeliveryTest"))
        assertFalse(output.all.contains("test-secret"))
    }

    @Test
    fun `interrupted map generation does not acknowledge the listing`() {
        val listener =
            NewFlatEventListener(
                ListingContract(testMapper),
                MessageFormatter(),
                TelegramSender { _, _ ->
                    fail<Unit>("Must not send")
                    CompletableFuture.completedFuture(null)
                },
                subscriptions(),
                testMapper,
                ListingMaps { throw InterruptedException("stopped") },
            )

        try {
            assertThrows(CompletionException::class.java) { listener.receive(record()).join() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `successful send completes after every configured chat`() {
        val delivered = mutableListOf<String>()
        val future =
            listener { chat, _ ->
                    delivered += chat
                    CompletableFuture.completedFuture(null)
                }
                .receive(record())

        future.join()

        assertEquals(listOf("123", "456"), delivered)
        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun `permanent Telegram rejection is skipped`() {
        val delivered = mutableListOf<String>()
        val future =
            listener { chat, _ ->
                    delivered += chat
                    if (chat == "456") {
                        CompletableFuture.failedFuture(
                            TelegramDeliveryException("fail", retryable = false)
                        )
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                }
                .receive(record())

        future.join()

        assertEquals(listOf("123", "456"), delivered)
        assertFalse(future.isCompletedExceptionally)
    }

    @Test
    fun `transient Telegram failure remains exceptional`() {
        val future =
            listener { chat, _ ->
                    if (chat == "456") {
                        CompletableFuture.failedFuture(
                            TelegramDeliveryException("fail", retryable = true)
                        )
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                }
                .receive(record())

        val error = assertThrows(CompletionException::class.java) { future.join() }

        assertTrue(error.cause is TelegramDeliveryException)
        assertTrue((error.cause as TelegramDeliveryException).retryable)
    }

    @Test
    fun `unknown delivery failure is treated as transient`() {
        val future =
            listener { chat, _ ->
                    if (chat == "456") {
                        CompletableFuture.failedFuture(IllegalStateException("fail"))
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                }
                .receive(record())

        assertThrows(CompletionException::class.java) { future.join() }
    }

    @Test
    fun `invalid record is skipped without sending`() {
        val listener = listener { _, _ ->
            fail<Unit>("Must not send")
            CompletableFuture.completedFuture(null)
        }

        for (record in
            listOf(
                record(fixture().replace("\"version\": 2", "\"version\": 3")),
                record(key = "wrong"),
            )) {
            listener.receive(record).join()
        }
    }

    @Test
    fun `only matching subscribers receive listings and empty audience skips map generation`() {
        val store = subscriptions()
        store.save(Subscription("123", filter = ListingFilter(maxWarm = "800".toBigDecimal())))
        store.save(Subscription("789", filter = ListingFilter(minArea = "64.5".toBigDecimal())))
        val delivered = mutableListOf<String>()
        var mapCalls = 0
        val listener = NewFlatEventListener(ListingContract(testMapper), MessageFormatter(),
            TelegramSender { chat, _ -> delivered += chat; CompletableFuture.completedFuture(null) },
            store, testMapper, ListingMaps { mapCalls++; null })
        listener.receive(record()).join()
        assertEquals(listOf("456", "789"), delivered)
        assertEquals(1, mapCalls)
        store.save(Subscription("456", active = false))
        store.save(Subscription("789", active = false))
        listener.receive(record()).join()
        assertEquals(2, delivered.size)
        assertEquals(1, mapCalls)
    }

    @Test
    fun `database failure does not acknowledge listing`() {
        val store = Mockito.mock(SubscriptionStore::class.java)
        Mockito.`when`(store.recipients(event())).thenThrow(IllegalStateException("database unavailable"))
        val listener = NewFlatEventListener(ListingContract(testMapper), MessageFormatter(),
            TelegramSender { _, _ -> error("Must not send") }, store, testMapper)
        assertThrows(CompletionException::class.java) { listener.receive(record()).join() }
    }
}
