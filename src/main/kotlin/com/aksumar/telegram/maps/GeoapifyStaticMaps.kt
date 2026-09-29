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
                (commonParameters(location) - "marker") + mapOf(
                    "style" to "positron",
                    "width" to MapLayout.OVERVIEW_WIDTH.toString(),
                    "height" to MapLayout.OVERVIEW_HEIGHT.toString(),
                    "zoom" to "10",
                    // Circle markers are offset vertically by the provider; geometry is centred on the coordinates.
                    "geometry" to "circle:${location.longitude},${location.latitude},6;fillcolor:#e53935;fillopacity:1;linecolor:#ffffff;linewidth:1",
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
            // Keep local names: forcing lang=de suppresses street labels in Geoapify tiles.
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
        // Keep priority POIs, railway station and airport labels; hide lower-priority POIs.
        const val BASE_MAP_STYLE =
            "poi-level-2:none|poi-level-3:none"
    }
}
