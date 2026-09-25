package com.aksumar.telegram

import org.apache.kafka.clients.consumer.Consumer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory

class RuntimeTest {
    @Test fun `broker preflight failure stops run before starting listener`() {
        @Suppress("UNCHECKED_CAST")
        val factory = mock(DefaultKafkaConsumerFactory::class.java) as DefaultKafkaConsumerFactory<String, String>
        @Suppress("UNCHECKED_CAST")
        val consumer = mock(Consumer::class.java) as Consumer<String, String>
        val registry = mock(KafkaListenerEndpointRegistry::class.java)
        `when`(factory.createConsumer()).thenReturn(consumer)
        doAnswer { (it.arguments[0] as Runnable).run(); null }.`when`(registry).stop(any(Runnable::class.java))
        val control = RunControl()
        val result = DeliveryRuntime(registry, factory, AppProperties(), control).run()
        assertEquals(1, result)
        assertTrue(control.failed.get())
        verify(registry, never()).start()
        verify(consumer).close()
    }
}
