package com.aksumar.telegram.maps

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Configuration

@AutoConfigureObservability
@SpringBootTest(classes = [PrometheusEndpointTest.Config::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:metrics-test"])
class PrometheusEndpointTest {
    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [KafkaAutoConfiguration::class])
    class Config

    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var http: TestRestTemplate

    @Test
    fun `prometheus endpoint exports timer buckets counts and durations`() {
        MapMetrics(registry).measure("telegram.maps.generation", "generation") { Unit }
        val response = http.getForEntity("/actuator/prometheus", String::class.java)
        assertEquals(200, response.statusCode.value())
        val body = requireNotNull(response.body)
        assertTrue(body.contains("telegram_maps_generation_seconds_bucket{"))
        assertTrue(body.contains("telegram_maps_generation_seconds_count{"))
        assertTrue(body.contains("telegram_maps_generation_seconds_sum{"))
        assertTrue(body.contains("le=\"30.0\""))
        assertTrue(body.contains("application=\"berlin-flat-telegram\""))
        assertEquals(404, http.getForEntity("/actuator/env", String::class.java).statusCode.value())
    }
}
