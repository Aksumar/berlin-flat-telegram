package com.aksumar.telegram.config

import com.aksumar.telegram.exception.DeliveryException
import com.aksumar.telegram.kafka.FatalErrorReporter
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer

@Configuration
@EnableKafka
class KafkaConfig {
    @Bean
    fun consumerFactory(properties: AppProperties): DefaultKafkaConsumerFactory<String, String> {
        require(properties.bootstrapServers.isNotBlank()) { "Kafka bootstrap servers are required" }

        return DefaultKafkaConsumerFactory<String, String>(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to properties.groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to maxOf(300_000, properties.chats().size * 35_000 + 60_000),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            "security.protocol" to "PLAINTEXT"
        ))
    }

    @Bean
    fun kafkaListenerContainerFactory(
        factory: DefaultKafkaConsumerFactory<String, String>,
        reporter: FatalErrorReporter
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            consumerFactory = factory
            setConcurrency(1)
            containerProperties.isMissingTopicsFatal = true
            containerProperties.setShutdownTimeout(60_000)
            setCommonErrorHandler(
                object : CommonContainerStoppingErrorHandler() {
                    override fun handleRemaining(
                        ex: Exception,
                        records: MutableList<ConsumerRecord<*, *>>,
                        consumer: Consumer<*, *>,
                        container: MessageListenerContainer
                    ) {
                        reporter.report("Kafka listener/container failure")
                        super.handleRemaining(
                            DeliveryException("Kafka listener failed", ex),
                            records,
                            consumer,
                            container
                        )
                    }

                    override fun handleOtherException(
                        ex: Exception,
                        consumer: Consumer<*, *>,
                        container: MessageListenerContainer,
                        batchListener: Boolean
                    ) {
                        reporter.report("Kafka consumer/broker failure")
                        super.handleOtherException(
                            DeliveryException("Kafka consumer failed", ex),
                            consumer,
                            container,
                            batchListener
                        )
                    }
                }
            )
        }
    }
}
