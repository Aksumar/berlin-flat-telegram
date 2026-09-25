package com.aksumar.telegram

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.kafka.support.Acknowledgment
import java.nio.file.Files
import java.nio.file.Path

class DeliveryTest {
    @TempDir lateinit var directory: Path
    private fun props() = AppProperties().apply { chatIds = "123, 456,123" }
    private fun record(value: String = fixture(), key: String = "[\"gewobag\",\"123\"]") = ConsumerRecord("test", 0, 0, key, value)
    private fun listener(state: DeliveryState, send: TelegramSender) = ListingListener(Contract(), MessageFormatter(), state, send, props())

    @Test fun `partial delivery resumes after process restart`() {
        val path = directory.resolve("sent.json")
        val delivered = mutableListOf<String>()
        var acked = false
        DeliveryState(path).use { state ->
            val listener = listener(state) { chat, _ -> if (chat == "456") error("fail") else delivered.add(chat) }
            assertThrows(DeliveryException::class.java) { listener.receive(record(), Acknowledgment { acked = true }) }
            assertFalse(acked)
            assertEquals(setOf("123"), state.deliveredChats(listingKey("gewobag", "123")))
        }
        DeliveryState(path).use { state ->
            val listener = listener(state) { chat, _ -> delivered.add(chat) }
            listener.receive(record(), Acknowledgment { acked = true })
            listener.receive(record(), Acknowledgment { acked = true })
            assertTrue(acked)
            assertEquals(listOf("123", "456"), delivered)
            assertTrue(state.isSent(listingKey("gewobag", "123")))
            assertTrue(state.deliveredChats(listingKey("gewobag", "123")).isEmpty())
        }
    }
    @Test fun `loads Python state without pending and does not resend to new chats`() {
        val path = directory.resolve("sent.json")
        Files.writeString(path, """{"version":1,"sent":["[\"gewobag\",\"123\"]"]}""")
        DeliveryState(path).use { state ->
            var acked = false
            listener(state) { _, _ -> fail<Unit>("Already sent") }.receive(record(), Acknowledgment { acked = true })
            assertTrue(acked)
        }
    }
    @Test fun `loads Python pending state`() {
        val path = directory.resolve("sent.json")
        Files.writeString(path, """{"version":1,"sent":[],"pending":{"[\"gewobag\",\"123\"]":["123"]}}""")
        DeliveryState(path).use { state ->
            val chats = mutableListOf<String>()
            listener(state) { chat, _ -> chats += chat }.receive(record(), Acknowledgment {})
            assertEquals(listOf("456"), chats)
        }
    }
    @Test fun `failed commit retains state and duplicate only retries acknowledgment`() {
        val path = directory.resolve("sent.json")
        var sends = 0
        DeliveryState(path).use { state ->
            val listener = listener(state) { _, _ -> sends++ }
            assertThrows(DeliveryException::class.java) { listener.receive(record(), Acknowledgment { error("commit") }) }
            listener.receive(record(), Acknowledgment {})
            assertEquals(2, sends)
        }
    }
    @Test fun `v1 or wrong key cannot send or acknowledge`() {
        val path = directory.resolve("sent.json")
        DeliveryState(path).use { state ->
            val listener = listener(state) { _, _ -> fail<Unit>("Must not send") }
            for (record in listOf(record(fixture().replace("\"version\": 2", "\"version\": 1")), record(key = "wrong"))) {
                assertThrows(DeliveryException::class.java) { listener.receive(record, Acknowledgment { fail<Unit>("Must not commit") }) }
            }
            assertFalse(Files.exists(path))
        }
    }
    @Test fun `invalid state is fatal and never overwritten`() {
        val path = directory.resolve("sent.json")
        for (text in listOf("{", """{"version":2,"sent":[]}""", """{"version":1,"sent":[1]}""",
            """{"version":1,"sent":[],"pending":{"key":"123"}}""")) {
            Files.writeString(path, text)
            assertThrows(DeliveryException::class.java) { DeliveryState(path) }
            assertEquals(text, Files.readString(path))
        }
    }
    @Test fun `second writer cannot acquire the same state`() {
        val path = directory.resolve("sent.json")
        DeliveryState(path).use { assertThrows(DeliveryException::class.java) { DeliveryState(path) } }
        DeliveryState(path).close()
    }
    @Test fun `failed persistence does not mark delivery in memory`() {
        val path = directory.resolve("sent.json")
        DeliveryState(path).use { state ->
            Files.createDirectory(directory.resolve("sent.json.tmp"))
            assertThrows(Exception::class.java) { state.recordChat("key", "123") }
            assertTrue(state.deliveredChats("key").isEmpty())
            assertFalse(state.isSent("key"))
        }
    }
}
