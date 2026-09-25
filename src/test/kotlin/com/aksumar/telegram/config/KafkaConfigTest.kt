package com.aksumar.telegram.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaConfigTest {
    @Test
    fun `Kafka configuration uses plaintext and manual offset control`() {
        val properties = AppProperties().apply {
            bootstrapServers = "localhost:9092"
            chatIds = listOf("123")
        }

        val config = properties.kafkaConfig()

        assertEquals(false, config["enable.auto.commit"])
        assertEquals(false, config["allow.auto.create.topics"])
        assertEquals("berlin-flat-telegram-v1", config["group.id"])
        assertEquals("PLAINTEXT", config["security.protocol"])
    }
}
