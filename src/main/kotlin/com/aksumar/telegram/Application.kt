package com.aksumar.telegram

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Bean
import kotlin.system.exitProcess

@ConfigurationProperties("app")
class AppProperties {
    var bootstrapServers: String = ""
    var topic: String = "berlin-flat-listings-v1"
    var groupId: String = "berlin-flat-telegram-v1"
    var botToken: String = ""
    var chatIds: String = ""
    var chatId: String = ""
    var geoapifyApiKey: String = ""

    fun chats(): List<String> = (chatIds.trim().ifEmpty { chatId }).split(',').map { it.trim() }
        .filter { it.isNotEmpty() }.distinct().also { require(it.isNotEmpty()) { "Telegram destination is required" } }

    fun kafkaConfig(): Map<String, Any> {
        require(bootstrapServers.isNotBlank()) { "Kafka bootstrap servers are required" }
        val result = mutableMapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to maxOf(300_000, chats().size * 65_000 + 60_000),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            "security.protocol" to "PLAINTEXT"
        )
        return result
    }
}

@SpringBootApplication(exclude = [KafkaAutoConfiguration::class])
@EnableConfigurationProperties(AppProperties::class)
class Application {
    @Bean fun telegramSender(properties: AppProperties): TelegramSender = TelegramClient(properties.botToken)
    @Bean fun listingMaps(properties: AppProperties): ListingMaps = GeoapifyMaps(properties.geoapifyApiKey)
}

fun main(args: Array<String>) {
    val result = try {
        SpringApplication.run(Application::class.java, *args).use { context ->
            context.getBean(DeliveryRuntime::class.java).run()
        }
    } catch (_: Exception) {
        System.err.println("Delivery failed. Check configuration and connectivity.")
        1
    }
    exitProcess(result)
}
