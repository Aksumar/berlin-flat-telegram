package com.aksumar.telegram.maps

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.Font
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
                    "street" -> "14.5"
                    else -> "12.5"
                }
            val common =
                mapOf(
                    "style" to "osm-bright",
                    "format" to "png",
                    "lang" to "de",
                    "marker" to
                        "lonlat:$lon,$lat;color:#e53935;size:32;icon:home;icontype:material;contentcolor:#ffffff;whitecircle:no",
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
                            ),
                    ),
                    960,
                    600,
                )
            val overview =
                readMap(
                    get(
                        staticMapUrl,
                        common +
                            mapOf(
                                "style" to "positron",
                                "width" to "192",
                                "height" to "160",
                                "area" to "rect:13.08,52.33,13.77,52.68",
                                "marker" to "lonlat:$lon,$lat;type:circle;color:#e53935;size:12",
                            ),
                    ),
                    192,
                    160,
                )
            addLandmarks(detail, lon, lat, zoom.toDouble())
            ListingMap(
                compose(detail, overview, !exact),
                "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=$zoom/$lat/$lon",
                !exact,
            )
        } catch (_: Exception) {
            // URLs contain the API key; never log response bodies, URIs or original exceptions.
            logger.warn("Map unavailable; sending listing without image")
            null
        }
    }

    private fun addLandmarks(image: BufferedImage, lon: Double, lat: Double, zoom: Double) {
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
        fun nearby(categories: String): List<JsonNode> {
            val results = mutableListOf<JsonNode>()
            try {
                do {
                    val page =
                        mapper
                            .readTree(
                                get(
                                    placesUrl,
                                    mapOf(
                                        "categories" to categories,
                                        "filter" to bounds,
                                        "bias" to "proximity:$lon,$lat",
                                        "limit" to "500",
                                        "offset" to results.size.toString(),
                                        "lang" to "de",
                                    ),
                                )
                            )
                            .path("features")
                            .map { it.path("properties") }
                    // Protect against a provider ignoring pagination.
                    if (page.isNotEmpty() && results.takeLast(page.size) == page) break
                    results.addAll(page)
                } while (page.size == 500)
            } catch (_: Exception) {
                logger.warn("Nearby landmarks unavailable; keeping available map data")
            }
            return results
        }
        val places =
            nearby("public_transport.subway,public_transport.train,public_transport.light_rail")
                .toMutableList()
        if (places.none { landmarkBadge(it) in setOf("S", "U") }) {
            val stops = nearby("public_transport.tram,public_transport.bus")
            val preferred = if (stops.any { landmarkBadge(it) == "T" }) "T" else "B"
            places.addAll(stops.filter { landmarkBadge(it) == preferred })
        }
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
                    Rectangle(740, 366, 220, 210), // Overview inset.
                    Rectangle(0, 0, 300, 40), // Possible approximate-location note.
                )
            val drawn = mutableSetOf<String>()
            for (p in places) {
                val name = p.path("name").asText("").trim()
                val badge = landmarkBadge(p) ?: continue
                val rail = badge == "S" || badge == "U"
                val lines = if (rail) transitCache.linesFor(name) else emptyList()
                val title =
                    (if (lines.isNotEmpty()) name.removePrefix("U ").removePrefix("S ") else name)
                        .take(36)
                val key =
                    if (rail) "$badge:${name.removePrefix("U ").removePrefix("S ")}" else badge
                if (key in drawn) continue
                val placeLon = p.path("lon").asDouble(Double.NaN)
                val placeLat = p.path("lat").asDouble(Double.NaN)
                if (!placeLon.isFinite() || !placeLat.isFinite() || abs(placeLat) >= 85) continue
                val x = (image.width / 2.0 + (placeLon - lon) / 360.0 * world).roundToInt()
                val y =
                    (image.height / 2.0 + (mercatorY(placeLat) - mercatorY(lat)) * world)
                        .roundToInt()
                if (x !in 0 until image.width || y !in 0 until image.height - 20) continue
                // This part of the main map is covered by the overview.
                if (Rectangle(748, 374, 204, 194).contains(x, y)) continue
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
        } finally {
            g.dispose()
        }
    }

    private fun landmarkBadge(p: JsonNode): String? {
        val name = p.path("name").asText("").trim()
        if (name.isBlank()) return null
        val categories = p.path("categories").map { it.asText() }
        val raw = p.path("datasource").path("raw")
        return when {
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
            else -> null
        }
    }

    private fun get(endpoint: String, parameters: Map<String, String>): ByteArray {
        val query =
            (parameters + ("apiKey" to apiKey)).entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("$endpoint?$query"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200 && response.body().size <= 5_000_000)
        return response.body()
    }

    private fun readMap(bytes: ByteArray, width: Int, height: Int): BufferedImage {
        val image = ImageIO.read(bytes.inputStream()) ?: error("Invalid map image")
        require(image.width == width && image.height == height)
        return image
    }

    private fun compose(
        detail: BufferedImage,
        overview: BufferedImage,
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
            // Leave the main map's attribution along its bottom edge unobscured.
            g.color = Color.WHITE
            g.fillRoundRect(748, 374, 204, 194, 14, 14)
            g.color = Color(65, 80, 90)
            g.drawRoundRect(748, 374, 204, 194, 14, 14)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
            g.drawString("БЕРЛИН", 758, 393)
            g.drawImage(overview, 754, 401, null)
            if (approximate) {
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
}
