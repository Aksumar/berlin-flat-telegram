package com.aksumar.telegram.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
class AppProperties {
    var bootstrapServers: String = ""
    var topic: String = "berlin-flat-listings-v1"
    var groupId: String = "berlin-flat-telegram-v1"
    var geoapifyApiKey: String = ""
    var botToken: String = ""
    var chatIds: List<String> = emptyList()

    fun chats(): List<String> = chatIds.also {
        require(it.isNotEmpty()) { "Telegram destination is required" }
    }

    fun kafkaConfig(): Map<String, Any> {
        require(bootstrapServers.isNotBlank()) { "Kafka bootstrap servers are required" }

        return mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to maxOf(300_000, chats().size * 35_000 + 60_000),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            "security.protocol" to "PLAINTEXT"
        )
    }
}
