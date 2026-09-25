package com.aksumar.telegram

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
class RunControl {
    val failed = AtomicBoolean(false)
    val closing = AtomicBoolean(false)
    private val done = CountDownLatch(1)
    fun finish() { done.countDown() }
    fun fail() { failed.set(true); done.countDown() }
    fun await(seconds: Long) {
        require(seconds >= 0) { "Duration must be nonnegative" }
        if (seconds == 0L) done.await() else done.await(seconds, TimeUnit.SECONDS)
    }
}

@Configuration
@EnableKafka
class KafkaConfiguration {
    @Bean fun consumerFactory(properties: AppProperties) = DefaultKafkaConsumerFactory<String, String>(properties.kafkaConfig())

    @Bean fun kafkaListenerContainerFactory(factory: DefaultKafkaConsumerFactory<String, String>, control: RunControl): ConcurrentKafkaListenerContainerFactory<String, String> {
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            consumerFactory = factory
            setConcurrency(1)
            setAutoStartup(false)
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            containerProperties.isSyncCommits = true
            containerProperties.isMissingTopicsFatal = true
            containerProperties.setShutdownTimeout(60_000)
            setCommonErrorHandler(object : CommonContainerStoppingErrorHandler() {
                override fun handleRemaining(ex: Exception, records: MutableList<ConsumerRecord<*, *>>, consumer: Consumer<*, *>, container: MessageListenerContainer) {
                    control.fail()
                    super.handleRemaining(DeliveryException("Delivery failed; progress retained"), records, consumer, container)
                }
                override fun handleOtherException(ex: Exception, consumer: Consumer<*, *>, container: MessageListenerContainer, batchListener: Boolean) {
                    control.fail()
                    super.handleOtherException(DeliveryException("Kafka consumer failed"), consumer, container, batchListener)
                }
            })
        }
    }
}

@Component
class ListingListener(private val contract: Contract, private val formatter: MessageFormatter,
    private val state: DeliveryState, private val sender: TelegramSender, private val properties: AppProperties) {
    @KafkaListener(id = "listings", topics = ["\${app.topic}"], groupId = "\${app.group-id}")
    fun receive(record: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        try {
            val item = contract.decode(record.value())
            val key = listingKey(item.source, item.id)
            if (record.key() != key) throw DeliveryException("Invalid Kafka event identity")
            if (!state.isSent(key)) {
                val text = formatter.format(item)
                for (chat in properties.chats()) {
                    if (chat !in state.deliveredChats(key)) {
                        sender.send(chat, text)
                        state.recordChat(key, chat)
                    }
                }
                state.complete(key)
            }
            acknowledgment.acknowledge()
        } catch (_: Exception) {
            throw DeliveryException("Listing delivery failed; progress retained")
        }
    }
}

@Component
class DeliveryRuntime(private val registry: KafkaListenerEndpointRegistry,
    private val factory: DefaultKafkaConsumerFactory<String, String>, private val properties: AppProperties,
    private val control: RunControl) {
    fun run(): Int {
        try {
            require(properties.duration >= 0)
            factory.createConsumer().use { consumer ->
                require(!consumer.partitionsFor(properties.topic, Duration.ofSeconds(30)).isNullOrEmpty())
            }
            registry.start()
            control.await(properties.duration)
        } catch (_: Exception) {
            control.fail()
        } finally {
            control.closing.set(true)
            val stopped = CountDownLatch(1)
            registry.stop { stopped.countDown() }
            if (!stopped.await(60, TimeUnit.SECONDS)) control.fail()
        }
        return if (control.failed.get()) 1 else 0
    }

    @EventListener fun consumerStopped(event: ConsumerStoppedEvent) {
        if (!control.closing.get()) control.fail()
    }
}
