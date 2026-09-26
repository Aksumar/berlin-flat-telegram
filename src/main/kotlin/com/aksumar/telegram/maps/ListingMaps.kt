package com.aksumar.telegram.maps

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.Font
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
) : ListingMaps {
    @Autowired
    constructor(
        properties: AppProperties,
        mapper: ObjectMapper,
    ) : this(properties.geoapifyApiKey, mapper)

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
                    "street" -> "14"
                    else -> "12"
                }
            val common =
                mapOf(
                    "style" to "osm-carto",
                    "format" to "png",
                    "lang" to "de",
                    "marker" to
                        "lonlat:$lon,$lat;color:#e53935;size:48;icon:home;icontype:material;contentcolor:#ffffff;whitecircle:no",
                    "attribution" to "default",
                )
            val detail =
                readMap(
                    get(
                        staticMapUrl,
                        common +
                            mapOf(
                                "width" to "640",
                                "height" to "400",
                                "center" to "lonlat:$lon,$lat",
                                "zoom" to zoom,
                            ),
                    ),
                    640,
                    400,
                )
            val overview =
                readMap(
                    get(
                        staticMapUrl,
                        common +
                            mapOf(
                                "width" to "192",
                                "height" to "160",
                                "area" to "rect:13.08,52.33,13.77,52.68",
                                "marker" to "lonlat:$lon,$lat;type:circle;color:#e53935;size:12",
                            ),
                    ),
                    192,
                    160,
                )
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
        val image = BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g.drawImage(detail, 0, 0, null)
            // Leave the main map's attribution along its bottom edge unobscured.
            g.color = Color.WHITE
            g.fillRect(436, 182, 196, 186)
            g.color = Color(65, 80, 90)
            g.drawRect(436, 182, 196, 186)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
            g.drawString("БЕРЛИН", 445, 199)
            g.drawImage(overview, 438, 205, null)
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
