package com.aksumar.telegram.delivery

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessException
import java.util.concurrent.ExecutionException

@Component
class DeliveryWorker(
    private val queue: DeliveryQueue,
    private val prepare: PrepareListing,
    private val sender: TelegramSender,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sent = registry.counter("telegram.delivery.results", "outcome", "sent")
    private val retried = registry.counter("telegram.delivery.results", "outcome", "retry")
    private val rejected = registry.counter("telegram.delivery.results", "outcome", "rejected")

    init {
        Gauge.builder("telegram.delivery.queue.pending", queue) { it.pendingCount().toDouble() }.register(registry)
        Gauge.builder("telegram.delivery.queue.oldest.seconds", queue) { it.oldestPendingAgeSeconds() }.register(registry)
    }

    // Fixed delay prevents overlapping invocations. H2 file mode permits only this application instance.
    @Scheduled(fixedDelayString = "\${app.delivery-poll-delay-ms:1000}")
    fun poll() {
        val job = try { queue.next() } catch (error: DataAccessException) {
            log.warn("Delivery queue is unavailable; Kafka intake can continue only while H2 is healthy", error)
            return
        } ?: return
        try {
            val content = job.prepared ?: prepare.prepare(job.listing).also { queue.savePrepared(job.eventKey, it) }
            sender.sendQueuedListing(job.chatId, content.text, content.listingUrl, content.map, content.mapUrl).get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return // Still pending; recover after restart.
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            if (cause is TelegramDeliveryException && !cause.retryable) {
                queue.finish(job, rejected = true)
                rejected.increment()
                log.warn("Telegram permanently rejected delivery: event={}, chat={}", job.eventKey, job.chatId)
            } else {
                queue.retry(job, (cause as? TelegramDeliveryException)?.retryAfterSeconds)
                retried.increment()
                log.warn("Delivery deferred: event={}, chat={}, attempt={}", job.eventKey, job.chatId, job.attempts + 1)
            }
            return
        }
        // A failure to persist success must leave the job pending, even though Telegram may have received it.
        queue.finish(job, rejected = false)
        sent.increment()
    }

    @Scheduled(fixedDelay = 3_600_000)
    fun cleanup() = queue.cleanup()
}
