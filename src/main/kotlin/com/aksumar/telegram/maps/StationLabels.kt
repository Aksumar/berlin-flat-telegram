package com.aksumar.telegram.maps

import java.awt.FontMetrics

internal data class StationLineRow(val kind: LandmarkKind, val lines: List<TransitLine>)

internal data class StationLabel(
    val station: Landmark,
    val titleLines: List<String>,
    val lineRows: List<StationLineRow>,
    val width: Int,
    val height: Int,
) {
    val titleHeight: Int get() = titleLines.size * TEXT_LINE_HEIGHT + VERTICAL_PADDING * 2

    companion object {
        const val TEXT_LINE_HEIGHT = 18
        const val VERTICAL_PADDING = 8
        const val HORIZONTAL_PADDING = 10
        const val TRANSPORT_ROW_HEIGHT = 28
        const val BADGE_COLUMN_WIDTH = 36
        const val CHIP_PADDING = 10
        const val CHIP_GAP = 3
    }
}

/** Measures complete station names and keeps S-Bahn and U-Bahn lines in separate rows. */
internal class StationLabels(private val transitCache: VbbTransitCache) {
    fun distinctStations(stations: List<Landmark>): List<Landmark> {
        val result = mutableListOf<Landmark>()
        val railKeys = mutableSetOf<String>()
        for (station in stations) {
            if (station.kind == LandmarkKind.BUS) {
                val stop = transitCache.busStopFor(station)
                val duplicate = result.any { previous ->
                    if (previous.kind != LandmarkKind.BUS) false
                    else {
                        val previousStop = transitCache.busStopFor(previous)
                        if (stop != null || previousStop != null) stop != null && stop.id == previousStop?.id
                        else busStopKey(station.name) == busStopKey(previous.name) && nearby(station, previous)
                    }
                }
                if (!duplicate) result.add(station)
            } else {
                val key = when {
                    station.kind.isRail && transitCache.linesFor(station.name).isNotEmpty() -> "rail:${stationKey(station.name)}"
                    station.kind.isRail -> "${station.kind}:${stationKey(station.name)}"
                    else -> "${station.kind}:${station.name}"
                }
                if (railKeys.add(key)) result.add(station)
            }
        }
        return result
    }

    private fun nearby(a: Landmark, b: Landmark): Boolean =
        if (a.longitude != null && a.latitude != null && b.longitude != null && b.latitude != null)
            distanceMeters(a.longitude, a.latitude, b.longitude, b.latitude) <= 180
        else a.x == b.x && a.y == b.y

    fun measure(station: Landmark, metrics: FontMetrics, preferredWidth: Int = 300): StationLabel {
        val lines = if (station.kind.isRail) transitCache.linesFor(station.name) else emptyList()
        val rows = if (station.kind == LandmarkKind.BUS) {
            busRows(transitCache.busStopFor(station)?.lines.orEmpty(), metrics, preferredWidth)
        } else listOf(LandmarkKind.SUBURBAN_RAIL, LandmarkKind.SUBWAY).mapNotNull { kind ->
            lines.filter { it.name.startsWith(kind.badge) }
                .takeIf { it.isNotEmpty() }
                ?.let { StationLineRow(kind, it) }
        }
        val measuredRowsWidth = rows.maxOfOrNull { row ->
            StationLabel.BADGE_COLUMN_WIDTH + row.lines.sumOf {
                metrics.stringWidth(it.name) + StationLabel.CHIP_PADDING + StationLabel.CHIP_GAP
            }
        } ?: 0
        val horizontalPadding = StationLabel.HORIZONTAL_PADDING * 2
        // Rail rows stay intact; bus rows are already wrapped to the available width.
        val contentWidth = maxOf(preferredWidth - horizontalPadding, measuredRowsWidth)
        val title = if (station.kind.isRail) stationDisplayName(station.name) else station.name
        val titleBadgeWidth = if (rows.isEmpty()) StationLabel.BADGE_COLUMN_WIDTH else 0
        val titleLines = wrapTitle(title, metrics, contentWidth - titleBadgeWidth)
        val measuredTitleWidth = titleLines.maxOf { metrics.stringWidth(it) } + titleBadgeWidth
        val width = maxOf(measuredTitleWidth, measuredRowsWidth) + horizontalPadding
        val height = maxOf(36, titleLines.size * StationLabel.TEXT_LINE_HEIGHT +
            StationLabel.VERTICAL_PADDING * 2 + rows.size * StationLabel.TRANSPORT_ROW_HEIGHT)
        return StationLabel(station, titleLines, rows, width, height)
    }

    private fun busRows(lines: List<TransitLine>, metrics: FontMetrics, preferredWidth: Int): List<StationLineRow> {
        val rows = mutableListOf<StationLineRow>()
        val available = preferredWidth - StationLabel.HORIZONTAL_PADDING * 2 - StationLabel.BADGE_COLUMN_WIDTH
        var row = mutableListOf<TransitLine>()
        var width = 0
        for (line in lines) {
            val chipWidth = metrics.stringWidth(line.name) + StationLabel.CHIP_PADDING + StationLabel.CHIP_GAP
            if (row.isNotEmpty() && width + chipWidth > available) {
                rows.add(StationLineRow(LandmarkKind.BUS, row))
                row = mutableListOf()
                width = 0
            }
            row.add(line)
            width += chipWidth
        }
        if (row.isNotEmpty()) rows.add(StationLineRow(LandmarkKind.BUS, row))
        return rows
    }

    private fun wrapTitle(title: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        val result = mutableListOf<String>()
        var remaining = title.trim()
        while (metrics.stringWidth(remaining) > maxWidth) {
            val fittingLength = (1..remaining.length).last { metrics.stringWidth(remaining.take(it)) <= maxWidth }
            val wordBreak = remaining.lastIndexOf(' ', fittingLength)
            val splitAt = if (wordBreak > 0) wordBreak else fittingLength
            result.add(remaining.take(splitAt).trimEnd())
            remaining = remaining.drop(splitAt).trimStart()
        }
        result.add(remaining)
        return result
    }
}
