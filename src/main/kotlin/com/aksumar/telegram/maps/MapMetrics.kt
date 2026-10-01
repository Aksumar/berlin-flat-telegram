package com.aksumar.telegram.maps

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Fixed operation/outcome labels only: never addresses, URLs, IDs or exception messages. */
internal class MapMetrics(private val registry: MeterRegistry) {
    fun <T> measure(name: String, operation: String, action: () -> T): T {
        val started = System.nanoTime()
        var outcome = "success"
        try {
            return action()
        } catch (error: InterruptedException) {
            outcome = "interrupted"
            throw error
        } catch (error: Exception) {
            outcome = (error as? MapGenerationException)?.outcome ?: "error"
            throw error
        } finally {
            val elapsed = System.nanoTime() - started
            Timer.builder(name)
                .description("Elapsed wall time including failed attempts")
                .tags("operation", operation, "outcome", outcome)
                .serviceLevelObjectives(*BUCKETS)
                .register(registry)
                .record(elapsed, TimeUnit.NANOSECONDS)
        }
    }

    private companion object {
        val BUCKETS = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10000, 15000, 20000, 25000, 30000, 45000, 60000)
            .map(Duration::ofMillis).toTypedArray()
    }
}
