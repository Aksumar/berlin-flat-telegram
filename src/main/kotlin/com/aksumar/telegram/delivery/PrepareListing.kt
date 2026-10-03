package com.aksumar.telegram.delivery

import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.maps.MapGenerationException
import com.aksumar.telegram.maps.mapFailureDetails
import com.aksumar.telegram.model.Listing
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class PreparedListing(val text: String, val listingUrl: String, val map: ListingMap?, val mapUrl: String?)

@Component
class PrepareListing(private val formatter: MessageFormatter, private val maps: ListingMaps) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun prepare(item: Listing): PreparedListing {
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
        return PreparedListing(text, item.url, map, mapUrl)
    }
}
