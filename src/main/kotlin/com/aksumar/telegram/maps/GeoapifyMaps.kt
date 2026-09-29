package com.aksumar.telegram.maps

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** Optional enrichment: provider outages and uncertain locations never suppress a listing. */
@Component
class GeoapifyMaps(
    private val apiKey: String,
    mapper: ObjectMapper,
    geocodeUrl: String = "https://api.geoapify.com/v1/geocode/search",
    staticMapUrl: String = "https://maps.geoapify.com/v1/staticmap",
    placesUrl: String = "https://api.geoapify.com/v2/places",
    transitCache: VbbTransitCache = VbbTransitCache(),
) : ListingMaps {
    @Autowired
    constructor(
        properties: AppProperties,
        mapper: ObjectMapper,
        transitCache: VbbTransitCache,
    ) : this(properties.geoapifyApiKey, mapper, transitCache = transitCache)

    private val logger = LoggerFactory.getLogger(GeoapifyMaps::class.java)
    private val client = GeoapifyClient(apiKey)
    private val geocoder = GeoapifyGeocoder(client, mapper, geocodeUrl)
    private val staticMaps = GeoapifyStaticMaps(client, staticMapUrl)
    private val places = GeoapifyPlaces(client, mapper, placesUrl)
    private val landmarkRenderer = LandmarkRenderer(transitCache)
    private val composer = MapComposer()

    override fun create(item: Listing): ListingMap? {
        if (apiKey.isBlank()) return null
        val deadlineNanos = System.nanoTime() + MAP_TIMEOUT.toNanos()
        return try {
            val location = geocoder.locate(item.address, deadlineNanos) ?: return null
            val detail = staticMaps.detail(location, deadlineNanos)
            val overview = staticMaps.overviewOrNull(location, deadlineNanos)
            val layout = MapLayout(hasOverview = overview != null, approximate = location.approximate)
            val viewport = MapViewport(location.longitude, location.latitude, location.detailZoom)
            val landmarks = places.findVisible(viewport, layout, deadlineNanos)
            val annotatedDetail = landmarkRenderer.draw(detail, landmarks, layout)
            ListingMap(composer.compose(annotatedDetail, overview, layout), location.mapUrl, location.approximate)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Exception) {
            // Provider URLs contain the API key; never log original exceptions or response bodies.
            logger.warn("Map unavailable; sending listing without image")
            null
        }
    }

    private companion object {
        val MAP_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
