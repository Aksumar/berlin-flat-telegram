package com.aksumar.telegram.maps

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory

internal class GeoapifyStaticMaps(private val client: GeoapifyClient, private val endpoint: String) {
    private val logger = LoggerFactory.getLogger(GeoapifyStaticMaps::class.java)

    fun detail(location: GeocodedLocation, deadlineNanos: Long): BufferedImage =
        fetchImage(
            commonParameters(location) + mapOf(
                "width" to MapLayout.DETAIL_WIDTH.toString(),
                "height" to MapLayout.DETAIL_HEIGHT.toString(),
                "zoom" to location.detailZoom.toString(),
                "styleCustomization" to BASE_MAP_STYLE,
            ),
            "detail map",
            deadlineNanos,
        )

    fun overviewOrNull(location: GeocodedLocation, deadlineNanos: Long): BufferedImage? =
        try {
            fetchImage(
                commonParameters(location) + mapOf(
                    "style" to "positron",
                    "width" to MapLayout.OVERVIEW_WIDTH.toString(),
                    "height" to MapLayout.OVERVIEW_HEIGHT.toString(),
                    "zoom" to "9",
                    "marker" to "lonlat:${location.longitude},${location.latitude};type:circle;color:#e53935;size:12",
                ),
                "overview map",
                deadlineNanos,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Exception) {
            logger.warn("Overview unavailable; keeping main map")
            null
        }

    private fun commonParameters(location: GeocodedLocation): Map<String, String> {
        val center = "lonlat:${location.longitude},${location.latitude}"
        val marker = if (location.approximate) "$center;type:circle;color:#e53935;size:20"
            else "$center;color:#e53935;size:36;icon:home;icontype:material;contentcolor:#ffffff;whitecircle:no"
        return mapOf(
            "style" to "osm-bright",
            "format" to "png",
            "lang" to "de",
            "center" to center,
            "marker" to marker,
            "attribution" to "default",
        )
    }

    private fun fetchImage(
        parameters: Map<String, String>,
        operation: String,
        deadlineNanos: Long,
    ): BufferedImage {
        val bytes = client.get(endpoint, parameters, operation, deadlineNanos)
        val image = ImageIO.read(bytes.inputStream()) ?: error("Invalid map image")
        require(image.width == parameters.getValue("width").toInt())
        require(image.height == parameters.getValue("height").toInt())
        return image
    }

    private companion object {
        const val BASE_MAP_STYLE =
            "poi-level-1:none|poi-level-2:none|poi-level-3:none|poi-railway:none"
    }
}
