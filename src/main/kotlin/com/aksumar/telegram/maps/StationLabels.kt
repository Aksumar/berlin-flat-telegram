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
    fun distinctStations(stations: List<Landmark>): List<Landmark> =
        stations.distinctBy {
            when {
                it.kind.isRail && transitCache.linesFor(it.name).isNotEmpty() -> "rail:${stationKey(it.name)}"
                it.kind.isRail -> "${it.kind}:${stationKey(it.name)}"
                else -> "${it.kind}:${it.name}"
            }
        }

    fun measure(station: Landmark, metrics: FontMetrics, preferredWidth: Int = 300): StationLabel {
        val lines = if (station.kind.isRail) transitCache.linesFor(station.name) else emptyList()
        val rows = listOf(LandmarkKind.SUBURBAN_RAIL, LandmarkKind.SUBWAY).mapNotNull { kind ->
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
        // Only names wrap. A transport row always stays intact, even in a narrow layout.
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
