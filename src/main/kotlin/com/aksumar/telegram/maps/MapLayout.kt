package com.aksumar.telegram.maps

import java.awt.Rectangle

internal class MapLayout(val hasOverview: Boolean, val approximate: Boolean) {
    fun isVisible(x: Int, y: Int): Boolean =
        x in 0 until DETAIL_WIDTH && y in 0 until DETAIL_HEIGHT - ATTRIBUTION_HEIGHT &&
            !(hasOverview && overviewBounds.contains(x, y))

    fun reservedAreas(): MutableList<Rectangle> =
        mutableListOf(houseMarkerBounds).apply {
            if (hasOverview) add(overviewBounds.apply { grow(8, 8) })
            if (approximate) add(approximateNoticeBounds)
        }

    companion object {
        const val DETAIL_WIDTH = 960
        const val DETAIL_HEIGHT = 600
        const val OVERVIEW_WIDTH = 288
        const val OVERVIEW_HEIGHT = 240
        private const val ATTRIBUTION_HEIGHT = 20
        private val houseMarkerBounds: Rectangle
            get() = Rectangle(456, 256, 48, 54)
        private val approximateNoticeBounds: Rectangle
            get() = Rectangle(0, 0, 300, 40)
        // Leave the main map's attribution along its bottom edge unobscured.
        val overviewBounds: Rectangle
            get() = Rectangle(652, 294, OVERVIEW_WIDTH + 12, OVERVIEW_HEIGHT + 34)
    }
}
