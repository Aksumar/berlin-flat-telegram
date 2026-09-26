package com.aksumar.telegram.kafka

import com.aksumar.telegram.Application
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.contract.listingKey
import com.aksumar.telegram.contract.jsonMapper
import com.aksumar.telegram.support.fixture
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("integration")
class KafkaIntegrationTest {
    companion object {
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"))

        @BeforeAll
        @JvmStatic
        fun startKafka() {
            kafka.start()
        }

        @AfterAll
        @JvmStatic
        fun stopKafka() {
            kafka.stop()
        }
    }

    class TestSender : TelegramSender {
        val chats = CopyOnWriteArrayList<String>()
        val failSecond = AtomicBoolean(false)

        override fun send(chat: String, text: String): CompletableFuture<Void> {
            chats += chat
            return if (chat == "456" && failSecond.get()) {
                CompletableFuture.failedFuture(
                    TelegramDeliveryException("Test failure", retryable = true)
                )
            } else {
                CompletableFuture.completedFuture(null)
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Overrides {
        @Bean
        @Primary
        fun testSender() = TestSender()
    }

    private fun context(topic: String, group: String) =
        SpringApplicationBuilder(Application::class.java, Overrides::class.java)
            .run(
                "--app.bootstrap-servers=${kafka.bootstrapServers}",
                "--app.topic=$topic",
                "--app.group-id=$group",
                "--app.chat-ids=123,456",
                "--app.bot-token=fake"
            )

    private fun topic(): String {
        val name = "test-${UUID.randomUUID()}"
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use {
            it.createTopics(listOf(NewTopic(name, 1, 1)))
                .all()
                .get(30, TimeUnit.SECONDS)
        }
        return name
    }

    private fun publish(topic: String, payload: String = fixture()) {
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to kafka.bootstrapServers,
                "key.serializer" to StringSerializer::class.java,
                "value.serializer" to StringSerializer::class.java
            )
        ).use {
            it.send(
                ProducerRecord(
                    topic,
                    listingKey(jsonMapper().readTree(payload)["source"].asText(), jsonMapper().readTree(payload)["id"].asText()),
                    payload
                )
            ).get(30, TimeUnit.SECONDS)
        }
    }

    private fun offset(group: String, topic: String): Long? =
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use {
            it.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS)[TopicPartition(topic, 0)]
                ?.offset()
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                fail<Unit>("Condition timed out")
            }
            Thread.sleep(100)
        }
    }

    @Test
    fun `Degewo Python event is delivered and acknowledged`() {
        val topic = topic()
        val group = "test-${UUID.randomUUID()}"
        publish(topic, javaClass.getResource("/degewo-v2.json")!!.readText())
        context(topic, group).use { context ->
            val run = CompletableFuture.supplyAsync { context.getBean(DeliveryRuntime::class.java).run() }
            try {
                await { offset(group, topic) == 1L }
                assertEquals(listOf("123", "456"), context.getBean(TestSender::class.java).chats)
            } finally {
                context.getBean(RunControl::class.java).finish()
            }
            assertEquals(0, run.get(30, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `transient Telegram failure keeps offset for retry`() {
        val topic = topic()
        val group = "test-${UUID.randomUUID()}"
        publish(topic)

        context(topic, group).use { context ->
            val sender = context.getBean(TestSender::class.java)
            sender.failSecond.set(true)

            val run = CompletableFuture.supplyAsync {
                context.getBean(DeliveryRuntime::class.java).run()
            }

            assertEquals(1, run.get(30, TimeUnit.SECONDS))
            assertNotEquals(1L, offset(group, topic))
        }

        context(topic, group).use { context ->
            val sender = context.getBean(TestSender::class.java)
            sender.failSecond.set(false)

            val run = CompletableFuture.supplyAsync {
                context.getBean(DeliveryRuntime::class.java).run()
            }

            try {
                await { offset(group, topic) == 1L }
            } finally {
                context.getBean(RunControl::class.java).finish()
            }

            assertEquals(0, run.get(30, TimeUnit.SECONDS))
            assertEquals(listOf("123", "456"), sender.chats)
        }
    }
}
