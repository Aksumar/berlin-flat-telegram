package com.aksumar.telegram.config

import com.aksumar.telegram.contract.exceptions.DeliveryException
import com.aksumar.telegram.kafka.RunControl
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer

@Configuration
@EnableKafka
class KafkaConfiguration {
    @Bean
    fun consumerFactory(properties: AppProperties) =
        DefaultKafkaConsumerFactory<String, String>(properties.kafkaConfig())

    @Bean
    fun kafkaListenerContainerFactory(
        factory: DefaultKafkaConsumerFactory<String, String>,
        control: RunControl
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            consumerFactory = factory
            setConcurrency(1)
            setAutoStartup(false)
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
                        control.fail("Kafka listener/container failure")
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
                        control.fail("Kafka consumer/broker failure")
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
