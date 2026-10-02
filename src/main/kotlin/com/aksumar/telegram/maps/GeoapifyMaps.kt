package com.aksumar.telegram.maps

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
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
    registry: MeterRegistry = Metrics.globalRegistry,
) : ListingMaps {
    @Autowired
    constructor(
        properties: AppProperties,
        mapper: ObjectMapper,
        transitCache: VbbTransitCache,
        registry: MeterRegistry,
    ) : this(properties.geoapifyApiKey, mapper, transitCache = transitCache, registry = registry)

    private val metrics = MapMetrics(registry)
    private val client = GeoapifyClient(apiKey, metrics)
    private val geocoder = GeoapifyGeocoder(client, mapper, geocodeUrl)
    private val staticMaps = GeoapifyStaticMaps(client, staticMapUrl)
    private val places = GeoapifyPlaces(client, mapper, placesUrl, transitCache)
    private val landmarkRenderer = LandmarkRenderer(transitCache)
    private val composer = MapComposer()

    override fun create(item: Listing): ListingMap = create(item) {}

    override fun create(item: Listing, onDistrictResolved: (String) -> Unit): ListingMap =
        metrics.measure("telegram.maps.generation", "generation") { generate(item, onDistrictResolved) }

    private fun generate(item: Listing, onDistrictResolved: (String) -> Unit): ListingMap {
        val startedNanos = System.nanoTime()
        val deadlineNanos = startedNanos + MAP_TIMEOUT.toNanos()
        var stage = "configuration"
        return try {
            if (apiKey.isBlank()) throw MapGenerationException("не настроен API-ключ Geoapify")
            stage = "geocoding"
            val location = geocoder.locate(item.address, deadlineNanos)
            location.district?.let(onDistrictResolved)
            stage = "detail map"
            val detail = staticMaps.detail(location, deadlineNanos)
            stage = "overview map"
            val overview = staticMaps.overviewOrNull(location, deadlineNanos)
            val layout = MapLayout(hasOverview = overview != null, approximate = location.approximate)
            val viewport = MapViewport(location.longitude, location.latitude, location.detailZoom)
            stage = "places"
            val landmarks = places.findVisible(viewport, layout, deadlineNanos)
            stage = "rendering"
            val annotatedDetail = landmarkRenderer.draw(detail, landmarks, layout)
            stage = "composition"
            ListingMap(composer.compose(annotatedDetail, overview, layout), location.approximate)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            val reason = when (error) {
                is MapGenerationException -> error.reason
                is JsonProcessingException -> "Geoapify вернул некорректный ответ с координатами"
                else -> "произошла ошибка обработки изображения карты"
            }
            throw MapGenerationException(reason,
                "stage=$stage, generationElapsedMs=${Duration.ofNanos(System.nanoTime() - startedNanos).toMillis()}, " +
                    "generationBudgetMs=${MAP_TIMEOUT.toMillis()}, ${error.mapFailureDetails()}",
                (error as? MapGenerationException)?.outcome ?: "error")
        }
    }

    private companion object {
        val MAP_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
