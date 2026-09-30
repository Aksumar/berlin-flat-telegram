package com.aksumar.telegram.maps

import java.awt.FontMetrics
import java.awt.Rectangle

internal data class PositionedStationLabel(
    val label: StationLabel,
    val bounds: Rectangle,
    val number: Int? = null,
    val markerBounds: Rectangle? = null,
)

internal data class ArrangedStationLabels(val labels: List<PositionedStationLabel>, val imageHeight: Int)

/** Keeps every station: labels that cannot fit on the map continue in a numbered legend below it. */
internal class StationLabelArrangement(private val labels: StationLabels) {
    fun arrange(stations: List<Landmark>, metrics: FontMetrics, layout: MapLayout): ArrangedStationLabels {
        val uniqueStations = labels.distinctStations(stations)
        val placement = StationLabelPlacement(layout, uniqueStations.map(::stationNumberBounds))
        val positioned = mutableListOf<PositionedStationLabel>()
        val overflow = mutableListOf<StationLabel>()
        for (station in uniqueStations) {
            var label = labels.measure(station, metrics)
            var box = placement.place(label)
            if (box == null) {
                label = labels.measure(station, metrics, preferredWidth = 220)
                box = placement.place(label)
            }
            if (box != null) positioned.add(PositionedStationLabel(label, box))
            else overflow.add(labels.measure(station, metrics, preferredWidth = LEGEND_LABEL_WIDTH))
        }
        val markerPlacement = StationLabelPlacement(layout, positioned.map { it.bounds })
        var legendX = LEGEND_PADDING
        var legendY = MapLayout.DETAIL_HEIGHT + LEGEND_PADDING
        var rowHeight = 0
        overflow.forEachIndexed { index, label ->
            val entryWidth = NUMBER_WIDTH + label.width
            if (legendX > LEGEND_PADDING && legendX + entryWidth > MapLayout.DETAIL_WIDTH - LEGEND_PADDING) {
                legendX = LEGEND_PADDING
                legendY += rowHeight + LEGEND_PADDING
                rowHeight = 0
            }
            val marker = markerPlacement.place(label.station, NUMBER_WIDTH, 24) ?: stationNumberBounds(label.station)
            val bounds = Rectangle(legendX + NUMBER_WIDTH, legendY, label.width, label.height)
            positioned.add(PositionedStationLabel(label, bounds, index + 1, marker))
            legendX += entryWidth + LEGEND_PADDING
            rowHeight = maxOf(rowHeight, label.height)
        }
        val height = if (overflow.isEmpty()) MapLayout.DETAIL_HEIGHT else legendY + rowHeight + LEGEND_PADDING
        return ArrangedStationLabels(positioned, height)
    }

    companion object {
        private const val LEGEND_PADDING = 12
        const val NUMBER_WIDTH = 36
        private const val LEGEND_LABEL_WIDTH = 264

        fun stationNumberBounds(station: Landmark): Rectangle = Rectangle(
            (station.x - NUMBER_WIDTH / 2).coerceIn(0, MapLayout.DETAIL_WIDTH - NUMBER_WIDTH),
            (station.y - 12).coerceIn(0, MapLayout.DETAIL_HEIGHT - 20 - 24),
            NUMBER_WIDTH,
            24,
        )
    }
}
