package com.aksumar.telegram.maps

import java.awt.Rectangle
import kotlin.math.abs

/** Finds room for the entire label, including near map edges and crowded interchanges. */
internal class StationLabelPlacement(layout: MapLayout, stationMarkers: List<Rectangle> = emptyList()) {
    private val occupied = layout.reservedAreas().apply { addAll(stationMarkers) }

    fun place(label: StationLabel): Rectangle? = place(label.station, label.width, label.height)

    fun place(station: Landmark, width: Int, height: Int): Rectangle? {
        val maxX = MapLayout.DETAIL_WIDTH - MARGIN - width
        val maxY = MapLayout.DETAIL_HEIGHT - BOTTOM_MARGIN - height
        if (maxX < MARGIN || maxY < MARGIN) return null
        val nearby = listOf(
            station.x + 12 to station.y - height / 2,
            station.x - width - 12 to station.y - height / 2,
            station.x + 12 to station.y + 18,
            station.x - width - 12 to station.y - height - 18,
        ).map { (x, y) -> Rectangle(x.coerceIn(MARGIN, maxX), y.coerceIn(MARGIN, maxY), width, height) }
        val box = nearby.firstOrNull(::isFree) ?: run {
            // Search the usable map instead of replacing an unplaced label with a bare S/U badge.
            val candidates = sequence {
                for (y in MARGIN..maxY step SEARCH_STEP) {
                    for (x in MARGIN..maxX step SEARCH_STEP) {
                        yield(Rectangle(x, y, width, height))
                    }
                }
            }
            candidates.filter(::isFree).minByOrNull { candidate ->
                val dx = abs(station.x - candidate.centerX)
                val dy = abs(station.y - candidate.centerY)
                dx * dx + dy * dy
            }
        }
        if (box != null) occupied.add(Rectangle(box).apply { grow(LABEL_GAP, LABEL_GAP) })
        return box
    }

    private fun isFree(candidate: Rectangle): Boolean = occupied.none { it.intersects(candidate) }

    private companion object {
        const val MARGIN = 8
        const val BOTTOM_MARGIN = 28
        const val LABEL_GAP = 5
        const val SEARCH_STEP = 8
    }
}
