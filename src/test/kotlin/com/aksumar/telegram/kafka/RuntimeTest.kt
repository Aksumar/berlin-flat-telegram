package com.aksumar.telegram.kafka

import org.apache.kafka.clients.consumer.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory

class RuntimeTest {
    @Test
    fun `broker preflight failure stops run before starting listener`() {
        @Suppress("UNCHECKED_CAST")
        val factory = mock(DefaultKafkaConsumerFactory::class.java)
            as DefaultKafkaConsumerFactory<String, String>
        @Suppress("UNCHECKED_CAST")
        val consumer = mock(Consumer::class.java) as Consumer<String, String>
        val registry = mock(KafkaListenerEndpointRegistry::class.java)

        `when`(factory.createConsumer()).thenReturn(consumer)
        doAnswer {
            (it.arguments[0] as Runnable).run()
            null
        }.`when`(registry).stop(any(Runnable::class.java))

        val messages = mutableListOf<String>()
        val control = RunControl(FatalErrorReporter { messages += it })

        val properties = com.aksumar.telegram.config.AppProperties()

        val result = DeliveryRuntime(registry, factory, properties, control).run()

        assertEquals(1, result)
        assertTrue(control.failed.get())
        assertEquals(listOf("Kafka startup or broker health check failed"), messages)
        verify(registry, never()).start()
        verify(consumer).close()
    }
}
