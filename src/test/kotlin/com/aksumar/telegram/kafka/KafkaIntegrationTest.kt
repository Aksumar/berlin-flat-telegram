package com.aksumar.telegram.kafka

import com.aksumar.telegram.Application
import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.support.fixture
import com.aksumar.telegram.support.testMapper
import com.aksumar.telegram.subscriptions.FilterCommands
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

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
        @Bean @Primary fun testSender() = TestSender()
    }

    private fun context(topic: String, group: String) =
        SpringApplicationBuilder(Application::class.java, Overrides::class.java)
            .run(
                "--app.bootstrap-servers=${kafka.bootstrapServers}",
                "--app.topic=$topic",
                "--app.group-id=$group",
                "--app.chat-ids=123,456",
                "--app.bot-token=fake",
                "--app.telegram-updates-enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:$group",
                "--server.port=0",
            )

    private fun topic(): String {
        val name = "test-${UUID.randomUUID()}"
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use {
            it.createTopics(listOf(NewTopic(name, 1, 1))).all().get(30, TimeUnit.SECONDS)
        }
        return name
    }

    private fun publish(topic: String, payload: String = fixture()) {
        KafkaProducer<String, String>(
                mapOf(
                    "bootstrap.servers" to kafka.bootstrapServers,
                    "key.serializer" to StringSerializer::class.java,
                    "value.serializer" to StringSerializer::class.java,
                )
            )
            .use {
                it.send(
                        ProducerRecord(
                            topic,
                            testMapper.listingKey(
                                testMapper.readTree(payload)["source"].asText(),
                                testMapper.readTree(payload)["id"].asText(),
                            ),
                            payload,
                        )
                    )
                    .get(30, TimeUnit.SECONDS)
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
    fun `enriched Degewo Python event is delivered and acknowledged`() {
        val topic = topic()
        val group = "test-${UUID.randomUUID()}"
        publish(topic, javaClass.getResource("/degewo-charlottenburg-v2.json")!!.readText())
        context(topic, group).use { context ->
            await { offset(group, topic) == 1L }
            assertEquals(listOf("123", "456"), context.getBean(TestSender::class.java).chats)
        }
    }

    @Test
    fun `saved search selects Kafka recipients through Spring command handler`() {
        val topic = topic()
        val group = "test-${UUID.randomUUID()}"
        context(topic, group).use { context ->
            val commands = context.getBean(FilterCommands::class.java)
            var updateId = 0L
            fun command(chat: Long, text: String) {
                commands.handle(testMapper.valueToTree(mapOf(
                    "update_id" to ++updateId,
                    "message" to mapOf("text" to text, "from" to mapOf("id" to chat),
                        "chat" to mapOf("id" to chat, "type" to "private")),
                )))
            }
            command(123, "/warm 800")
            command(456, "/stop")
            for (text in listOf("/start", FilterCommands.SETUP, "Только без WBS", "60", "900", FilterCommands.SAVE)) {
                command(789, text)
            }
            publish(topic)
            await { offset(group, topic) == 1L }
            assertEquals(listOf("789"), context.getBean(TestSender::class.java).chats)
        }
    }

    @Test
    fun `transient Telegram failure keeps offset for retry`() {
        val topic = topic()
        val group = "test-${UUID.randomUUID()}"
        context(topic, group).use { context ->
            val sender = context.getBean(TestSender::class.java)
            sender.failSecond.set(true)

            publish(topic)
            val registry = context.getBean(KafkaListenerEndpointRegistry::class.java)
            await { !registry.getListenerContainer("listings")!!.isRunning }

            assertNotEquals(1L, offset(group, topic))
        }

        context(topic, group).use { context ->
            val sender = context.getBean(TestSender::class.java)
            sender.failSecond.set(false)

            await { offset(group, topic) == 1L }
            assertEquals(listOf("123", "456"), sender.chats)
        }
    }
}
