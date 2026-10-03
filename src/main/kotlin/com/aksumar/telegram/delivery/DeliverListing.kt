package com.aksumar.telegram.delivery

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.subscriptions.SubscriptionStore
import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.maps.MapGenerationException
import com.aksumar.telegram.maps.mapFailureDetails
import com.aksumar.telegram.model.Listing
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DeliverListing(
    private val formatter: MessageFormatter,
    private val sender: TelegramSender,
    private val subscriptions: SubscriptionStore,
    private val maps: ListingMaps = ListingMaps { null },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun deliver(item: Listing): CompletableFuture<Void> {
        return try {
            val recipients = try {
                subscriptions.recipients(item)
            } catch (error: Exception) {
                // A storage outage must not acknowledge and lose a valid Kafka listing.
                return CompletableFuture.failedFuture(error)
            }
            if (recipients.isEmpty()) return CompletableFuture.completedFuture(null)
            val mapUrl = formatter.mapUrl(item)
            var resolvedItem = item
            var mapFailureReason: String? = null
            var mapFailureDetails = "result=null"
            val mapStartedNanos = System.nanoTime()
            val map =
                try {
                    maps.create(item) { district ->
                        if (item.address.district.isNullOrBlank()) {
                            resolvedItem = item.copy(address = item.address.copy(district = district))
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } catch (error: Exception) {
                    mapFailureDetails = error.mapFailureDetails()
                    mapFailureReason = (error as? MapGenerationException)?.reason
                        ?: "произошла непредвиденная ошибка генерации карты"
                    null
                }
            if (map == null) {
                mapFailureReason = mapFailureReason ?: "сервис генерации карты не вернул изображение"
                log.warn(
                    "Карта не сгенерирована, потому что {}; source={}, id={}, address={}, elapsedMs={}, {}",
                    mapFailureReason, item.source, item.id, item.address.searchQuery(),
                    (System.nanoTime() - mapStartedNanos) / 1_000_000, mapFailureDetails,
                )
            }
            if (resolvedItem.address.district.isNullOrBlank()) {
                log.warn(
                    "Не удалось определить район; source={}, id={}, address={}",
                    item.source, item.id, item.address.searchQuery(),
                )
            }
            val text = formatter.format(resolvedItem, mapFailureReason)
            val deliveries = recipients.map { sender.sendListing(it, text, item.url, map, mapUrl) }

            CompletableFuture.allOf(*deliveries.toTypedArray()).handle<Void> { _, _ ->
                val failures = deliveries.mapNotNull(::failure)

                if (failures.isEmpty()) {
                    return@handle null
                }

                val transient =
                    failures.firstOrNull { it !is TelegramDeliveryException || it.retryable }

                if (transient != null) {
                    log.error(
                        "Telegram delivery failed after retries: source={}, id={}",
                        item.source, item.id,
                        transient,
                    )
                    throw CompletionException(transient)
                }

                log.error(
                    "Skipping listing after permanent Telegram rejection: source={}, id={}",
                    item.source, item.id,
                    failures.first(),
                )
                null
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            CompletableFuture.failedFuture(error)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
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
