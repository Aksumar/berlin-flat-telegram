package com.aksumar.telegram

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

class DeliveryTest {
    @Test fun `map is prepared once for all recipients and failed photo is not acknowledged`() {
        var created = 0
        var acked = false
        val map = ListingMap(byteArrayOf(1), "https://www.openstreetmap.org/", false)
        val maps = ListingMaps { created++; map }
        val delivered = mutableListOf<String>()
        val sender = object : TelegramSender {
            override fun send(chat: String, text: String) { fail<Unit>("Expected photo") }
            override fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?) {
                assertNotNull(map)
                delivered += chat
                if (chat == "456") throw DeliveryException("Photo failed")
            }
        }
        val listener = ListingListener(Contract(), MessageFormatter(), sender, props(), maps)
        assertThrows(DeliveryException::class.java) { listener.receive(record(), Acknowledgment { acked = true }) }
        assertEquals(1, created)
        assertEquals(listOf("123", "456"), delivered)
        assertFalse(acked)
    }

    private fun props() = AppProperties().apply { chatIds = "123, 456,123" }
    private fun record(value: String = fixture(), key: String = "[\"gewobag\",\"123\"]") =
        ConsumerRecord("test", 0, 0, key, value)
    private fun listener(send: TelegramSender) = ListingListener(Contract(), MessageFormatter(), send, props())

    @Test fun `successful delivery sends to every configured chat and acknowledges`() {
        val delivered = mutableListOf<String>()
        var acked = false
        listener { chat, _ -> delivered += chat }.receive(record(), Acknowledgment { acked = true })
        assertEquals(listOf("123", "456"), delivered)
        assertTrue(acked)
    }

    @Test fun `failed delivery does not acknowledge`() {
        val delivered = mutableListOf<String>()
        var acked = false
        val listener = listener { chat, _ ->
            if (chat == "456") error("fail")
            delivered += chat
        }
        assertThrows(DeliveryException::class.java) {
            listener.receive(record(), Acknowledgment { acked = true })
        }
        assertEquals(listOf("123"), delivered)
        assertFalse(acked)
    }

    @Test fun `v1 or wrong key cannot send or acknowledge`() {
        val listener = listener { _, _ -> fail<Unit>("Must not send") }
        for (record in listOf(
            record(fixture().replace("\"version\": 2", "\"version\": 1")),
            record(key = "wrong")
        )) {
            assertThrows(DeliveryException::class.java) {
                listener.receive(record, Acknowledgment { fail<Unit>("Must not commit") })
            }
        }
    }
}
