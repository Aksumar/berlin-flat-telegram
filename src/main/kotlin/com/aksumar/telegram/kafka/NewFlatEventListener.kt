package com.aksumar.telegram.kafka

import com.aksumar.telegram.delivery.DeliverListing
import com.aksumar.telegram.exception.DeliveryException
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class NewFlatEventListener(
    private val contract: ListingContract,
    private val delivery: DeliverListing,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(id = "listings", topics = ["\${app.topic}"], groupId = "\${app.group-id}")
    fun onRecord(record: ConsumerRecord<String, String>) {
        // Return only after delivery completes so Kafka retains acknowledgement/error handling.
        receive(record).join()
    }

    fun receive(record: ConsumerRecord<String, String>): CompletableFuture<Void> {
        val item = try {
            contract.decode(record.value()).also {
                if (!mapper.matchesListingKey(record.key(), it.source, it.id)) {
                    throw DeliveryException("Invalid Kafka event identity")
                }
            }
        } catch (error: Exception) {
            log.error("Skipping invalid listing: topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key(), error)
            return CompletableFuture.completedFuture(null)
        }
        return delivery.deliver(item).whenComplete { _, error ->
            if (error != null) {
                log.error("Delivery failed; leaving Kafka record uncommitted: topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), error)
            }
        }
    }
}
