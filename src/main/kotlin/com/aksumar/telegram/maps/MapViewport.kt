package com.aksumar.telegram.maps

import java.awt.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator projection matching Geoapify's 512-pixel tiles at zoom zero. */
internal class MapViewport(val longitude: Double, val latitude: Double, zoom: Double) {
    private val worldSize = 512.0 * 2.0.pow(zoom)
    private val centerY = mercatorY(latitude)

    val boundsFilter: String
        get() {
            val halfLongitude = MapLayout.DETAIL_WIDTH / 2.0 / worldSize * 360.0
            val halfMercatorY = MapLayout.DETAIL_HEIGHT / 2.0 / worldSize
            val west = longitude - halfLongitude
            val east = longitude + halfLongitude
            val south = latitudeAt(centerY + halfMercatorY)
            val north = latitudeAt(centerY - halfMercatorY)
            return "rect:$west,$south,$east,$north"
        }

    fun project(longitude: Double, latitude: Double): Point? {
        if (!longitude.isFinite() || !latitude.isFinite() || abs(latitude) >= 85) return null
        val x = MapLayout.DETAIL_WIDTH / 2.0 + (longitude - this.longitude) / 360.0 * worldSize
        val y = MapLayout.DETAIL_HEIGHT / 2.0 + (mercatorY(latitude) - centerY) * worldSize
        return Point(x.roundToInt(), y.roundToInt())
    }

    private fun mercatorY(latitude: Double): Double {
        val radians = Math.toRadians(latitude)
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
    }

    private fun latitudeAt(mercatorY: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * mercatorY))))
}
