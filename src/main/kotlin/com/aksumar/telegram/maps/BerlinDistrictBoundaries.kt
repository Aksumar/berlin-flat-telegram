package com.aksumar.telegram.maps

import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.pow
import org.springframework.core.io.ClassPathResource

/** Bundled, simplified Berlin borough boundaries, drawn in the overview map's projection. */
internal class BerlinDistrictBoundaries {
    private val polygons = load()

    fun draw(image: BufferedImage, center: GeocodedLocation) {
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(134, 122, 142, 110)
            graphics.stroke = BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            polygons.forEach { polygon ->
                val path = Path2D.Double()
                polygon.forEach { ring ->
                    ring.forEachIndexed { index, point ->
                        val (x, y) = project(point.first, point.second, center, image)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.closePath()
                }
                graphics.draw(path)
            }

            // Keep the center marker clear where a district boundary crosses it.
            val (x, y) = project(center.longitude, center.latitude, center, image)
            graphics.color = Color.WHITE
            graphics.fillOval(x.toInt() - 6, y.toInt() - 6, 12, 12)
            graphics.color = Color(229, 57, 53)
            graphics.fillOval(x.toInt() - 4, y.toInt() - 4, 8, 8)
        } finally {
            graphics.dispose()
        }
    }

    private fun project(
        longitude: Double,
        latitude: Double,
        center: GeocodedLocation,
        image: BufferedImage,
    ): Pair<Double, Double> {
        val worldSize = 512.0 * 2.0.pow(OVERVIEW_ZOOM)
        val centerY = mercatorY(center.latitude)
        val x = image.width / 2.0 + (longitude - center.longitude) / 360.0 * worldSize
        val y = image.height / 2.0 + (mercatorY(latitude) - centerY) * worldSize
        return x to y
    }

    private fun mercatorY(latitude: Double): Double =
        (1.0 - ln(tan(Math.toRadians(latitude)) + 1.0 / cos(Math.toRadians(latitude))) / PI) / 2.0

    private fun load(): List<List<List<Pair<Double, Double>>>> {
        val mapper = ObjectMapper()
        val root = ClassPathResource("berlin_district_boundaries.json").inputStream.use { mapper.readTree(it) }
        require(root.path("features").size() == 12) { "Expected 12 Berlin district boundaries" }
        return root.path("features").flatMap { feature ->
            val geometry = feature.path("geometry")
            when (geometry.path("type").asText()) {
                "Polygon" -> listOf(readPolygon(geometry.path("coordinates")))
                "MultiPolygon" -> geometry.path("coordinates").map(::readPolygon)
                else -> error("Unsupported district boundary geometry")
            }
        }
    }

    private fun readPolygon(coordinates: com.fasterxml.jackson.databind.JsonNode): List<List<Pair<Double, Double>>> =
        coordinates.map { ring -> ring.map { point -> point[0].asDouble() to point[1].asDouble() } }

    private companion object {
        const val OVERVIEW_ZOOM = 11.0
    }
}
