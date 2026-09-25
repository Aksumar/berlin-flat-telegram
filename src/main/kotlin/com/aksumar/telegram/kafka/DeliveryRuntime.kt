package com.aksumar.telegram.kafka

import com.aksumar.telegram.config.AppProperties
import org.springframework.context.event.EventListener
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Component
class DeliveryRuntime(
    private val registry: KafkaListenerEndpointRegistry,
    private val factory: DefaultKafkaConsumerFactory<String, String>,
    private val properties: AppProperties,
    private val control: RunControl
) {
    fun run(): Int {
        try {
            factory.createConsumer().use { consumer ->
                require(
                    !consumer.partitionsFor(
                        properties.topic,
                        Duration.ofSeconds(30)
                    ).isNullOrEmpty()
                )
            }

            registry.start()
            control.await()
        } catch (_: Exception) {
            control.fail("Kafka startup or broker health check failed")
        } finally {
            control.closing.set(true)

            val stopped = CountDownLatch(1)
            registry.stop { stopped.countDown() }

            if (!stopped.await(60, TimeUnit.SECONDS)) {
                control.fail("Kafka listener shutdown timed out")
            }
        }

        return if (control.failed.get()) 1 else 0
    }

    @EventListener
    fun consumerStopped(event: ConsumerStoppedEvent) {
        if (!control.closing.get()) {
            control.fail("Kafka consumer stopped unexpectedly")
        }
    }
}
