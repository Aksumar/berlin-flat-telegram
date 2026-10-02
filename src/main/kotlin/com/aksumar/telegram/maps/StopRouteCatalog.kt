package com.aksumar.telegram.maps

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource

internal data class StopPlatform(
    val id: String,
    val parent: String,
    val name: String,
    val longitude: Double,
    val latitude: Double,
    val lines: List<String>,
)

internal data class RouteStop(val id: String, val platforms: List<StopPlatform>) {
    val lines: List<TransitLine> = platforms.flatMap { it.lines }.distinct().sortedWith(stopLineOrder)
        .map { TransitLine(it, java.awt.Color(118, 78, 151)) }
}

internal val stopLineOrder: Comparator<String> = compareBy<String> { it.startsWith("N") }
    .thenBy { it.takeWhile(Char::isLetter) }
    .thenBy { it.dropWhile(Char::isLetter).toIntOrNull() ?: Int.MAX_VALUE }
    .thenBy { it }

internal fun stopKey(name: String): String = stationDisplayName(name)
    .lowercase().removePrefix("berlin, ").replace(Regex("\\s*\\[tram\\]$"), "")
    .replace(Regex("\\s*\\(berlin\\)$"), "")
    .replace("ß", "ss").replace("str.", "strasse")
    .replace(Regex("[\\s./+-]"), "")

/** Intersections can be named as "Street A / Street B" by the map provider. */
internal fun stopKeys(name: String): Set<String> =
    (listOf(name) + name.split('/')).map(::stopKey).filter(String::isNotBlank).toSet()

internal fun distanceMeters(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
    val latitude = Math.toRadians(lat2 - lat1)
    val longitude = Math.toRadians(lon2 - lon1)
    val a = sin(latitude / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(longitude / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Local scheduled routes, shared across both directions; never queried over the network. */
internal class StopRouteCatalog(platforms: List<StopPlatform>) {
    private val stopsByName: Map<String, List<RouteStop>>

    init {
        val groups = mutableMapOf<String, MutableList<MutableList<StopPlatform>>>()
        platforms.sortedBy { it.id }.forEach { platform ->
            val key = if (platform.parent.isNotBlank()) "parent:${platform.parent}" else "name:${stopKey(platform.name)}"
            val candidates = groups.getOrPut(key) { mutableListOf() }
            val group = candidates.firstOrNull { members ->
                platform.parent.isNotBlank() || members.all { distanceMeters(it.longitude, it.latitude, platform.longitude, platform.latitude) <= 180 }
            }
            if (group == null) candidates.add(mutableListOf(platform)) else group.add(platform)
        }
        stopsByName = groups.values.flatten().map { members -> RouteStop(members.first().parent.ifBlank { members.first().id }, members) }
            .flatMap { stop -> stop.platforms.flatMap { platform -> stopKeys(platform.name).map { it to stop } }.distinct() }
            .groupBy({ it.first }, { it.second })
    }

    fun find(name: String, longitude: Double?, latitude: Double?): RouteStop? {
        if (longitude == null || latitude == null || !longitude.isFinite() || !latitude.isFinite()) return null
        // Multiple possible groups are deliberately left unmatched instead of guessing a route.
        return stopKeys(name).flatMap { stopsByName[it].orEmpty() }.distinctBy { it.id }.filter { stop ->
            stop.platforms.any { distanceMeters(longitude, latitude, it.longitude, it.latitude) <= 180 }
        }.singleOrNull()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StopRouteCatalog::class.java)

        fun load(resource: Resource): StopRouteCatalog = try {
            val root = resource.inputStream.use { ObjectMapper().readTree(it) }
            require(root.path("stops").isArray)
            val until = LocalDate.parse(root.path("validUntil").asText())
            if (LocalDate.now(ZoneId.of("Europe/Berlin")).isAfter(until)) {
                logger.warn("Stop route catalog {} covers schedules through {}; using last available routes. Refresh the VBB catalog.", resource.description, until)
            }
            val platforms = root.path("stops").map { row ->
                val longitude = row.path("lon").asDouble(Double.NaN)
                val latitude = row.path("lat").asDouble(Double.NaN)
                require(longitude.isFinite() && longitude in -180.0..180.0 && latitude.isFinite() && latitude in -90.0..90.0)
                StopPlatform(row.path("id").asText(), row.path("parent").asText(), row.path("name").asText(),
                    longitude, latitude, row.path("lines").map { it.asText() })
            }
            logger.info("Loaded {} platforms from VBB catalog {} ({})", platforms.size, resource.description, root.path("generatedAt").asText())
            StopRouteCatalog(platforms)
        } catch (_: Exception) {
            logger.warn("VBB stop route catalog {} unavailable; keeping stop labels without route numbers", resource.description)
            StopRouteCatalog(emptyList())
        }
    }
}
