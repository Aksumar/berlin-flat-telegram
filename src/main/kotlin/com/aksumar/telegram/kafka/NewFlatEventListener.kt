package com.aksumar.telegram.kafka

import com.aksumar.telegram.client.TelegramDeliveryException
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.contract.DeliveryException
import com.aksumar.telegram.contract.ListingContract
import com.aksumar.telegram.contract.matchesListingKey
import com.aksumar.telegram.format.MessageFormatter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Component
class NewFlatEventListener(
    private val contract: ListingContract,
    private val formatter: MessageFormatter,
    private val sender: TelegramSender,
    private val properties: AppProperties,
    private val maps: ListingMaps = ListingMaps { null }
) {
    private val log = LoggerFactory.getLogger(NewFlatEventListener::class.java)

    @KafkaListener(
        id = "listings",
        topics = ["\${app.topic}"],
        groupId = "\${app.group-id}"
    )
    fun onRecord(record: ConsumerRecord<String, String>) {
        // Complete delivery before returning control to Kafka. Async listener
        // return values use different acknowledgement/error handling semantics.
        receive(record).join()
    }

    fun receive(record: ConsumerRecord<String, String>): CompletableFuture<Void> {
        return try {
            val item = contract.decode(record.value())
            if (!matchesListingKey(record.key(), item.source, item.id)) {
                throw DeliveryException("Invalid Kafka event identity")
            }

            val text = formatter.format(item)
            val map = runCatching { maps.create(item) }.getOrNull()
            val deliveries = properties.chats().map { sender.sendListing(it, text, item.url, map) }

            CompletableFuture.allOf(*deliveries.toTypedArray())
                .handle<Void> { _, _ ->
                    val failures = deliveries.mapNotNull(::failure)

                    if (failures.isEmpty()) {
                        return@handle null
                    }

                    val transient = failures.firstOrNull {
                        it !is TelegramDeliveryException || it.retryable
                    }

                    if (transient != null) {
                        log.error(
                            "Telegram delivery failed after retries; leaving Kafka record uncommitted: topic={}, partition={}, offset={}, key={}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            record.key(),
                            transient
                        )
                        throw CompletionException(transient)
                    }

                    log.error(
                        "Skipping listing after permanent Telegram rejection: topic={}, partition={}, offset={}, key={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        failures.first()
                    )
                    null
                }
        } catch (error: Exception) {
            log.error(
                "Skipping invalid listing: topic={}, partition={}, offset={}, key={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                error
            )
            CompletableFuture.completedFuture(null)
        }
    }

    private fun failure(future: CompletableFuture<Void>): Throwable? {
        if (!future.isCompletedExceptionally) {
            return null
        }

        return try {
            future.join()
            null
        } catch (error: CompletionException) {
            error.cause ?: error
        }
    }
}
