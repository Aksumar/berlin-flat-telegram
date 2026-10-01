package com.aksumar.telegram.maps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

internal class GeoapifyPlaces(
    private val client: GeoapifyClient,
    private val mapper: ObjectMapper,
    private val endpoint: String,
) {
    private val logger = LoggerFactory.getLogger(GeoapifyPlaces::class.java)

    fun findVisible(viewport: MapViewport, layout: MapLayout, deadlineNanos: Long): List<Landmark> {
        val search = Search(viewport, layout, deadlineNanos)
        val transport = search.nearby(RAIL_CATEGORIES).toMutableList()
        if (transport.none { it.kind.isRail }) {
            val stops = search.nearby(STOP_CATEGORIES)
            val preferred = if (stops.any { it.kind == LandmarkKind.TRAM }) LandmarkKind.TRAM else LandmarkKind.BUS
            transport.addAll(stops.filter { it.kind == preferred })
        }
        return transport
    }

    /** Availability belongs to one map request; a failed search must not disable later maps. */
    private inner class Search(
        private val viewport: MapViewport,
        private val layout: MapLayout,
        private val deadlineNanos: Long,
    ) {
        private var available = true

        fun nearby(categories: String): List<Landmark> {
            if (!available) return emptyList()
            if (System.nanoTime() >= deadlineNanos) {
                available = false
                logger.warn("Nearby landmarks skipped; keeping available map data: reason=map time budget exceeded, categories={}, viewport={}",
                    categories, viewport.boundsFilter)
                return emptyList()
            }
            val results = mutableListOf<Landmark>()
            val seenIds = mutableSetOf<String>()
            var previousPage: List<JsonNode> = emptyList()
            var offset = 0
            try {
                for (pageIndex in 0 until MAX_PAGES) {
                    offset = pageIndex * PAGE_SIZE
                    val page = fetchPage(categories, PAGE_SIZE, offset)
                    // Protect against a provider ignoring pagination.
                    if (page.isEmpty() || page == previousPage) break
                    for (place in page) {
                        val id = place.path("place_id").asText("")
                        if (id.isBlank() || seenIds.add(id)) visibleLandmark(place)?.let(results::add)
                    }
                    previousPage = page
                    if (page.size < PAGE_SIZE) break
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                available = false
                logger.warn("Nearby landmarks unavailable; keeping available map data: categories={}, offset={}, retainedCount={}, viewport={}, reason={}, {}",
                    categories, offset, results.size, viewport.boundsFilter,
                    (error as? MapGenerationException)?.reason ?: "places response processing failed", error.mapFailureDetails())
            }
            return results
        }

        private fun fetchPage(categories: String, limit: Int, offset: Int): List<JsonNode> =
            mapper.readTree(
                client.get(
                    endpoint,
                    mapOf(
                        "categories" to categories,
                        "filter" to viewport.boundsFilter,
                        "bias" to "proximity:${viewport.longitude},${viewport.latitude}",
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                        "lang" to "de",
                    ),
                    "places",
                    deadlineNanos,
                )
            ).path("features").map { it.path("properties") }

        private fun visibleLandmark(place: JsonNode): Landmark? {
            val kind = landmarkKind(place) ?: return null
            val position = viewport.project(
                place.path("lon").asDouble(Double.NaN),
                place.path("lat").asDouble(Double.NaN),
            ) ?: return null
            if (!layout.isVisible(position.x, position.y)) return null
            return Landmark(place.path("name").asText().trim(), kind, position.x, position.y,
                place.path("lon").asDouble(), place.path("lat").asDouble())
        }
    }

    private fun landmarkKind(place: JsonNode): LandmarkKind? {
        val name = place.path("name").asText("").trim()
        val categories = place.path("categories").map { it.asText() }
        if (name.isBlank() && categories.any { it.startsWith("public_transport.") }) return null
        val raw = place.path("datasource").path("raw")
        return when {
            categories.any { it == "education.school" || it == "childcare.kindergarten" } -> null
            "public_transport.subway" in categories && raw.path("railway").asText() == "station" ->
                LandmarkKind.SUBWAY
            ("public_transport.train" in categories ||
                "public_transport.light_rail" in categories) &&
                (raw.path("station").asText() == "light_rail" ||
                    name.startsWith("S ") ||
                    name.startsWith("S+") ||
                    name.startsWith("S/U ")) -> LandmarkKind.SUBURBAN_RAIL
            "public_transport.tram" in categories -> LandmarkKind.TRAM
            "public_transport.bus" in categories -> LandmarkKind.BUS
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_PAGES = 2
        const val RAIL_CATEGORIES = "public_transport.subway,public_transport.train,public_transport.light_rail"
        const val STOP_CATEGORIES = "public_transport.tram,public_transport.bus"
    }
}
