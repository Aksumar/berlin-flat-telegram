package com.aksumar.telegram.maps

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

data class ListingMap(val png: ByteArray, val url: String, val approximate: Boolean)

fun interface ListingMaps {
    fun create(item: Listing): ListingMap?
}

/**
 * Optional enrichment: missing keys, uncertain locations and provider outages never suppress a
 * listing.
 */
@Component
class GeoapifyMaps(
    private val apiKey: String,
    private val mapper: ObjectMapper,
    private val geocodeUrl: String = "https://api.geoapify.com/v1/geocode/search",
    private val staticMapUrl: String = "https://maps.geoapify.com/v1/staticmap",
    private val placesUrl: String = "https://api.geoapify.com/v2/places",
    private val transitCache: VbbTransitCache = VbbTransitCache(),
) : ListingMaps {
    @Autowired
    constructor(
        properties: AppProperties,
        mapper: ObjectMapper,
        transitCache: VbbTransitCache,
    ) : this(properties.geoapifyApiKey, mapper, transitCache = transitCache)

    private val logger = LoggerFactory.getLogger(GeoapifyMaps::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun create(item: Listing): ListingMap? {
        if (apiKey.isBlank()) return null
        val deadline = System.nanoTime() + MAP_TIMEOUT.toNanos()
        val a = item.address
        val query =
            a.full?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(
                        listOfNotNull(a.street, a.houseNumber)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { null },
                        a.postalCode,
                        a.district,
                        a.city,
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
        if (query.isBlank()) return null
        return try {
            val data =
                mapper.readTree(
                    get(
                        geocodeUrl,
                        mapOf(
                            "text" to query,
                            "filter" to "rect:13.08,52.33,13.77,52.68",
                            "limit" to "1",
                            "format" to "json",
                            "lang" to "de",
                        ),
                        "geocoding",
                        deadline,
                    )
                )
            val location = data.path("results").firstOrNull() ?: return null
            val lon = location.path("lon").asDouble(Double.NaN)
            val lat = location.path("lat").asDouble(Double.NaN)
            val rank = location.path("rank")
            val type = location.path("result_type").asText()
            // Do not invent a precise pin from a city-level or low-confidence match.
            if (
                lon !in 13.08..13.77 ||
                    lat !in 52.33..52.68 ||
                    rank.path("confidence").asDouble(0.0) < 0.8 ||
                    type !in setOf("building", "street", "suburb", "district", "postcode")
            )
                return null
            val exact =
                type == "building" &&
                    rank.path("confidence_building_level").asDouble(0.0) >= 0.95 &&
                    rank.path("match_type").asText() == "full_match"
            val zoom =
                when (type) {
                    "building",
                    "street" -> "15.5"
                    else -> "12.5"
                }
            val common =
                mapOf(
                    "style" to "osm-bright",
                    "format" to "png",
                    "lang" to "de",
                    "marker" to
                        if (exact)
                            "lonlat:$lon,$lat;color:#e53935;size:32;icon:home;icontype:material;contentcolor:#ffffff;whitecircle:no"
                        else "lonlat:$lon,$lat;type:circle;color:#e53935;size:20",
                    "attribution" to "default",
                )
            val detail =
                readMap(
                    get(
                        staticMapUrl,
                        common +
                            mapOf(
                                "width" to "960",
                                "height" to "600",
                                "center" to "lonlat:$lon,$lat",
                                "zoom" to zoom,
                                "styleCustomization" to BASE_MAP_STYLE,
                            ),
                        "detail map",
                        deadline,
                    ),
                    960,
                    600,
                )
            val overview =
                try {
                    readMap(
                        get(
                            staticMapUrl,
                            common +
                                mapOf(
                                    "style" to "positron",
                                    "width" to OVERVIEW_WIDTH.toString(),
                                    "height" to OVERVIEW_HEIGHT.toString(),
                                    "center" to "lonlat:$lon,$lat",
                                    "zoom" to "9",
                                    "marker" to "lonlat:$lon,$lat;type:circle;color:#e53935;size:12",
                                ),
                            "overview map",
                            deadline,
                        ),
                        OVERVIEW_WIDTH,
                        OVERVIEW_HEIGHT,
                    )
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } catch (_: Exception) {
                    logger.warn("Overview unavailable; keeping main map")
                    null
                }
            addLandmarks(detail, lon, lat, zoom.toDouble(), overview != null, !exact, deadline)
            ListingMap(
                compose(detail, overview, !exact),
                if (exact) "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"
                else {
                    val viewZoom = if (type == "building" || type == "street") 15 else 12
                    "https://www.google.com/maps/@?api=1&map_action=map&center=$lat%2C$lon&zoom=$viewZoom"
                },
                !exact,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Exception) {
            // URLs contain the API key; never log response bodies, URIs or original exceptions.
            logger.warn("Map unavailable; sending listing without image")
            null
        }
    }

    private fun addLandmarks(
        image: BufferedImage,
        lon: Double,
        lat: Double,
        zoom: Double,
        hasOverview: Boolean,
        approximate: Boolean,
        deadline: Long,
    ) {
        // Geoapify vector maps use 512-pixel tiles at zoom zero.
        val world = 512.0 * 2.0.pow(zoom)
        fun mercatorY(latitude: Double): Double {
            val radians = Math.toRadians(latitude)
            return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
        }
        fun latitude(y: Double) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
        val halfLon = image.width / 2.0 / world * 360.0
        val halfY = image.height / 2.0 / world
        val bounds =
            "rect:${lon-halfLon},${latitude(mercatorY(lat)+halfY)},${lon+halfLon},${latitude(mercatorY(lat)-halfY)}"
        data class Landmark(val name: String, val badge: String, val x: Int, val y: Int)

        fun visibleLandmark(place: JsonNode): Landmark? {
            val badge = landmarkBadge(place) ?: return null
            val placeLon = place.path("lon").asDouble(Double.NaN)
            val placeLat = place.path("lat").asDouble(Double.NaN)
            if (!placeLon.isFinite() || !placeLat.isFinite() || abs(placeLat) >= 85) return null
            val x = (image.width / 2.0 + (placeLon - lon) / 360.0 * world).roundToInt()
            val y =
                (image.height / 2.0 + (mercatorY(placeLat) - mercatorY(lat)) * world)
                    .roundToInt()
            if (x !in 0 until image.width || y !in 0 until image.height - 20) return null
            if (hasOverview && OVERVIEW_BOUNDS.contains(x, y)) return null
            return Landmark(place.path("name").asText().trim(), badge, x, y)
        }

        var placesAvailable = true
        fun nearby(categories: String, maxResults: Int = PLACES_PAGE_SIZE * MAX_PLACES_PAGES): List<JsonNode> {
            if (!placesAvailable || System.nanoTime() >= deadline) return emptyList()
            val results = mutableListOf<JsonNode>()
            val seenIds = mutableSetOf<String>()
            var previousPage: List<JsonNode> = emptyList()
            try {
                for (pageIndex in 0 until MAX_PLACES_PAGES) {
                    val pageSize = minOf(PLACES_PAGE_SIZE, maxResults - pageIndex * PLACES_PAGE_SIZE)
                    if (pageSize <= 0) break
                    val page =
                        mapper
                            .readTree(
                                get(
                                    placesUrl,
                                    mapOf(
                                        "categories" to categories,
                                        "filter" to bounds,
                                        "bias" to "proximity:$lon,$lat",
                                        "limit" to pageSize.toString(),
                                        "offset" to (pageIndex * PLACES_PAGE_SIZE).toString(),
                                        "lang" to "de",
                                    ),
                                    "places",
                                    deadline,
                                )
                            )
                            .path("features")
                            .map { it.path("properties") }
                    // Protect against a provider ignoring pagination.
                    if (page.isEmpty() || page == previousPage) break
                    for (place in page) {
                        val id = place.path("place_id").asText("")
                        if (id.isBlank() || seenIds.add(id)) results.add(place)
                    }
                    previousPage = page
                    if (page.size < pageSize) break
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (_: Exception) {
                placesAvailable = false
                logger.warn("Nearby landmarks unavailable; keeping available map data")
            }
            return results
        }
        val places =
            nearby("public_transport.subway,public_transport.train,public_transport.light_rail")
                .mapNotNull(::visibleLandmark)
                .toMutableList()
        if (places.none { it.badge == "S" || it.badge == "U" }) {
            val stops = nearby("public_transport.tram,public_transport.bus")
                .mapNotNull(::visibleLandmark)
            val preferred = if (stops.any { it.badge == "T" }) "T" else "B"
            places.addAll(stops.filter { it.badge == preferred })
        }
        val amenities = nearby(SELECTED_POI_CATEGORIES, 200).mapNotNull(::visibleLandmark)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
            val occupied =
                mutableListOf(
                    Rectangle(458, 260, 44, 50), // House marker.
                )
            if (hasOverview) {
                occupied.add(
                    Rectangle(
                        OVERVIEW_BOUNDS.x - 8,
                        OVERVIEW_BOUNDS.y - 8,
                        OVERVIEW_BOUNDS.width + 16,
                        OVERVIEW_BOUNDS.height + 16,
                    )
                )
            }
            if (approximate) occupied.add(Rectangle(0, 0, 300, 40))
            val drawn = mutableSetOf<String>()
            for (p in places) {
                val name = p.name
                val badge = p.badge
                val rail = badge == "S" || badge == "U"
                val lines = if (rail) transitCache.linesFor(name) else emptyList()
                val title = (if (lines.isNotEmpty()) stationDisplayName(name) else name).take(36)
                val key = if (rail) "$badge:${stationKey(name)}" else "$badge:$name"
                if (key in drawn) continue
                val x = p.x
                val y = p.y
                val lineChipWidth = lines.sumOf { g.fontMetrics.stringWidth(it.name) + 10 } + lines.size * 3
                val width = g.fontMetrics.stringWidth(title) + 52 + lineChipWidth
                val candidates =
                    listOf(
                        Rectangle(x + 12, y - 18, width, 36),
                        Rectangle(x - width - 12, y - 18, width, 36),
                        Rectangle(x + 12, y + 18, width, 36),
                        Rectangle(x - width - 12, y - 54, width, 36),
                    )
                val box =
                    candidates.firstOrNull { r ->
                        r.x >= 8 &&
                            r.y >= 8 &&
                            r.x + r.width < image.width - 8 &&
                            r.y + r.height < image.height - 28 &&
                            occupied.none { it.intersects(r) }
                    }
                val color = lines.firstOrNull()?.color ?:
                    when (badge) {
                        "U" -> Color(35, 107, 158)
                        "T",
                        "B" -> Color(118, 78, 151)
                        else -> Color(44, 126, 76)
                    }
                if (box == null) {
                    if (rail) {
                        val markerX = x.coerceIn(13, image.width - 13)
                        val markerY = y.coerceIn(13, image.height - 33)
                        g.color = Color.WHITE
                        g.fillOval(markerX - 13, markerY - 13, 26, 26)
                        g.color = color
                        g.fillOval(markerX - 11, markerY - 11, 22, 22)
                        g.color = Color.WHITE
                        g.drawString(
                            badge,
                            markerX - g.fontMetrics.stringWidth(badge) / 2,
                            markerY + 6,
                        )
                        occupied.add(Rectangle(markerX - 14, markerY - 14, 28, 28))
                        drawn.add(key)
                    }
                    continue
                }
                g.color = color
                g.drawLine(x, y, if (box.x > x) box.x else box.x + box.width, box.y + 18)
                g.fillOval(x - 3, y - 3, 6, 6)
                g.color = Color(255, 255, 255, 245)
                g.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10)
                g.color = color
                g.fillOval(box.x + 4, box.y + 4, 28, 28)
                val titleX = box.x + 40
                g.drawString(title, titleX, box.y + 24)
                g.color = Color.WHITE
                g.drawString(badge, box.x + 18 - g.fontMetrics.stringWidth(badge) / 2, box.y + 24)
                var chipX = titleX + g.fontMetrics.stringWidth(title) + 9
                for (line in lines) {
                    val chipWidth = g.fontMetrics.stringWidth(line.name) + 10
                    g.color = line.color
                    g.fillRoundRect(chipX, box.y + 8, chipWidth, 20, 7, 7)
                    g.color = if (line.color.red * 299 + line.color.green * 587 + line.color.blue * 114 > 140_000) Color.BLACK else Color.WHITE
                    g.drawString(line.name, chipX + 5, box.y + 23)
                    chipX += chipWidth + 3
                }
                occupied.add(Rectangle(box.x - 5, box.y - 5, box.width + 10, box.height + 10))
                drawn.add(key)
            }
            for (amenity in amenities) {
                val icon = Rectangle(amenity.x - 7, amenity.y - 7, 14, 14)
                if (
                    icon.x < 4 || icon.y < 4 ||
                        icon.x + icon.width >= image.width - 4 ||
                        icon.y + icon.height >= image.height - 28 ||
                        occupied.any { it.intersects(icon) }
                ) continue
                drawAmenityIcon(g, amenity.badge, amenity.x, amenity.y)
                occupied.add(icon)
            }
        } finally {
            g.dispose()
        }
    }

    private fun drawAmenityIcon(g: Graphics2D, badge: String, x: Int, y: Int) {
        g.color = Color(62, 65, 66)
        when (badge) {
            "A" -> {
                g.fillRoundRect(x - 5, y - 2, 10, 5, 5, 5)
                g.color = Color.WHITE
                g.drawLine(x, y - 1, x, y + 2)
            }
            "H" -> {
                g.fillRect(x - 1, y - 5, 3, 10)
                g.fillRect(x - 5, y - 1, 10, 3)
            }
            "P" -> {
                g.fillPolygon(intArrayOf(x - 5, x, x + 5), intArrayOf(y + 2, y - 5, y + 2), 3)
                g.fillRect(x - 1, y + 1, 2, 4)
            }
            "G" -> {
                g.fillRoundRect(x - 5, y - 2, 10, 7, 2, 2)
                g.drawArc(x - 3, y - 5, 6, 7, 0, 180)
            }
        }
    }

    private fun landmarkBadge(p: JsonNode): String? {
        val name = p.path("name").asText("").trim()
        val categories = p.path("categories").map { it.asText() }
        if (name.isBlank() && categories.any { it.startsWith("public_transport.") }) return null
        val raw = p.path("datasource").path("raw")
        return when {
            categories.any { it == "education.school" || it == "childcare.kindergarten" } -> null
            "public_transport.subway" in categories && raw.path("railway").asText() == "station" ->
                "U"
            ("public_transport.train" in categories ||
                "public_transport.light_rail" in categories) &&
                (raw.path("station").asText() == "light_rail" ||
                    name.startsWith("S ") ||
                    name.startsWith("S+") ||
                    name.startsWith("S/U ")) -> "S"
            "public_transport.tram" in categories -> "T"
            "public_transport.bus" in categories -> "B"
            "healthcare.hospital" in categories -> "H"
            "healthcare.pharmacy" in categories ||
                "commercial.health_and_beauty.pharmacy" in categories -> "A"
            "leisure.park" in categories -> "P"
            categories.any {
                it == "commercial.supermarket" ||
                    it == "commercial.convenience"
            } -> "G"
            else -> null
        }
    }

    private fun get(
        endpoint: String,
        parameters: Map<String, String>,
        operation: String,
        deadline: Long,
    ): ByteArray {
        val remaining = deadline - System.nanoTime()
        check(remaining > 0) { "Map time budget exceeded" }
        val query =
            (parameters + ("apiKey" to apiKey)).entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("$endpoint?$query"))
                .timeout(Duration.ofNanos(minOf(remaining, REQUEST_TIMEOUT.toNanos())))
                .GET()
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Geoapify {} request failed", operation)
                throw error
            }
        if (response.statusCode() != 200) {
            logger.warn("Geoapify {} returned HTTP {}", operation, response.statusCode())
            error("Geoapify request failed")
        }
        if (response.body().size > 5_000_000) {
            logger.warn("Geoapify {} response exceeded 5 MB", operation)
            error("Geoapify response too large")
        }
        return response.body()
    }

    private fun readMap(bytes: ByteArray, width: Int, height: Int): BufferedImage {
        val image = ImageIO.read(bytes.inputStream()) ?: error("Invalid map image")
        require(image.width == width && image.height == height)
        return image
    }

    private fun compose(
        detail: BufferedImage,
        overview: BufferedImage?,
        approximate: Boolean,
    ): ByteArray {
        val image = BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(detail, 0, 0, null)
            if (overview != null) {
                // Leave the main map's attribution along its bottom edge unobscured.
                g.color = Color.WHITE
                g.fillRoundRect(
                    OVERVIEW_BOUNDS.x,
                    OVERVIEW_BOUNDS.y,
                    OVERVIEW_BOUNDS.width,
                    OVERVIEW_BOUNDS.height,
                    14,
                    14,
                )
                g.color = Color(65, 80, 90)
                g.drawRoundRect(
                    OVERVIEW_BOUNDS.x,
                    OVERVIEW_BOUNDS.y,
                    OVERVIEW_BOUNDS.width,
                    OVERVIEW_BOUNDS.height,
                    14,
                    14,
                )
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                g.drawString("БЕРЛИН", OVERVIEW_BOUNDS.x + 10, OVERVIEW_BOUNDS.y + 19)
                g.drawImage(overview, OVERVIEW_BOUNDS.x + 6, OVERVIEW_BOUNDS.y + 27, null)
            }
            if (approximate) {
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                g.color = Color.WHITE
                g.fillRoundRect(10, 10, 280, 28, 8, 8)
                g.color = Color(65, 80, 90)
                g.drawString("Примерное расположение", 20, 29)
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, "png", out))
            out.toByteArray()
        }
    }

    private companion object {
        val MAP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val PLACES_PAGE_SIZE = 500
        const val MAX_PLACES_PAGES = 2
        const val OVERVIEW_WIDTH = 288
        const val OVERVIEW_HEIGHT = 240
        val OVERVIEW_BOUNDS = Rectangle(652, 294, OVERVIEW_WIDTH + 12, OVERVIEW_HEIGHT + 34)
        const val SELECTED_POI_CATEGORIES =
            "healthcare.pharmacy,commercial.health_and_beauty.pharmacy,healthcare.hospital," +
                "leisure.park,commercial.supermarket,commercial.convenience"
        const val BASE_MAP_STYLE =
            "poi-level-1:none|poi-level-2:none|poi-level-3:none|poi-railway:none"
    }
}
