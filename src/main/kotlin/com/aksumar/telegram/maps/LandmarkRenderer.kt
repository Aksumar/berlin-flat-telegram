package com.aksumar.telegram.maps

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal class LandmarkRenderer(private val transitCache: VbbTransitCache) {
    fun draw(image: BufferedImage, landmarks: List<Landmark>, layout: MapLayout) {
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
            val occupied = layout.reservedAreas()
            drawTransport(graphics, landmarks, occupied)
        } finally {
            graphics.dispose()
        }
    }

    private fun drawTransport(graphics: Graphics2D, stations: List<Landmark>, occupied: MutableList<Rectangle>) {
        val drawnStations = mutableSetOf<String>()
        for (station in stations) {
            val key = if (station.kind.isRail) "${station.kind}:${stationKey(station.name)}"
                else "${station.kind}:${station.name}"
            if (key in drawnStations) continue
            val lines = if (station.kind.isRail) transitCache.linesFor(station.name) else emptyList()
            val title = (if (lines.isNotEmpty()) stationDisplayName(station.name) else station.name).take(36)
            val lineChipsWidth = lines.sumOf { graphics.fontMetrics.stringWidth(it.name) + 10 } + lines.size * 3
            val labelWidth = graphics.fontMetrics.stringWidth(title) + 52 + lineChipsWidth
            val box = findLabelSpace(station, labelWidth, occupied)
            val color = lines.firstOrNull()?.color ?: transportColor(station.kind)
            if (box == null) {
                if (station.kind.isRail) {
                    occupied.add(drawRailMarker(graphics, station, color))
                    drawnStations.add(key)
                }
                continue
            }
            drawStationLabel(graphics, station, title, box, color)
            val firstChipX = box.x + 40 + graphics.fontMetrics.stringWidth(title) + 9
            drawLineChips(graphics, lines, firstChipX, box.y)
            occupied.add(Rectangle(box.x - 5, box.y - 5, box.width + 10, box.height + 10))
            drawnStations.add(key)
        }
    }

    private fun findLabelSpace(station: Landmark, width: Int, occupied: List<Rectangle>): Rectangle? =
        listOf(
            Rectangle(station.x + 12, station.y - 18, width, 36),
            Rectangle(station.x - width - 12, station.y - 18, width, 36),
            Rectangle(station.x + 12, station.y + 18, width, 36),
            Rectangle(station.x - width - 12, station.y - 54, width, 36),
        ).firstOrNull { candidate ->
            candidate.x >= 8 && candidate.y >= 8 &&
                candidate.x + candidate.width < MapLayout.DETAIL_WIDTH - 8 &&
                candidate.y + candidate.height < MapLayout.DETAIL_HEIGHT - 28 &&
                occupied.none { it.intersects(candidate) }
        }

    private fun drawRailMarker(graphics: Graphics2D, station: Landmark, color: Color): Rectangle {
        val markerX = station.x.coerceIn(13, MapLayout.DETAIL_WIDTH - 13)
        val markerY = station.y.coerceIn(13, MapLayout.DETAIL_HEIGHT - 33)
        val badge = station.kind.badge
        graphics.color = Color.WHITE
        graphics.fillOval(markerX - 13, markerY - 13, 26, 26)
        graphics.color = color
        graphics.fillOval(markerX - 11, markerY - 11, 22, 22)
        graphics.color = Color.WHITE
        graphics.drawString(badge, markerX - graphics.fontMetrics.stringWidth(badge) / 2, markerY + 6)
        return Rectangle(markerX - 14, markerY - 14, 28, 28)
    }

    private fun drawStationLabel(
        graphics: Graphics2D,
        station: Landmark,
        title: String,
        box: Rectangle,
        color: Color,
    ) {
        graphics.color = color
        graphics.drawLine(station.x, station.y, if (box.x > station.x) box.x else box.x + box.width, box.y + 18)
        graphics.fillOval(station.x - 3, station.y - 3, 6, 6)
        graphics.color = Color(255, 255, 255, 245)
        graphics.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10)
        graphics.color = color
        graphics.fillOval(box.x + 4, box.y + 4, 28, 28)
        graphics.drawString(title, box.x + 40, box.y + 24)
        graphics.color = Color.WHITE
        val badge = station.kind.badge
        graphics.drawString(badge, box.x + 18 - graphics.fontMetrics.stringWidth(badge) / 2, box.y + 24)
    }

    private fun drawLineChips(graphics: Graphics2D, lines: List<TransitLine>, startX: Int, labelY: Int) {
        var chipX = startX
        for (line in lines) {
            val chipWidth = graphics.fontMetrics.stringWidth(line.name) + 10
            graphics.color = line.color
            graphics.fillRoundRect(chipX, labelY + 8, chipWidth, 20, 7, 7)
            graphics.color = contrastingTextColor(line.color)
            graphics.drawString(line.name, chipX + 5, labelY + 23)
            chipX += chipWidth + 3
        }
    }

    private fun transportColor(kind: LandmarkKind): Color =
        when (kind) {
            LandmarkKind.SUBWAY -> Color(35, 107, 158)
            LandmarkKind.TRAM, LandmarkKind.BUS -> Color(118, 78, 151)
            LandmarkKind.SUBURBAN_RAIL -> Color(44, 126, 76)
        }

    private fun contrastingTextColor(background: Color): Color {
        val brightness = background.red * 299 + background.green * 587 + background.blue * 114
        return if (brightness > 140_000) Color.BLACK else Color.WHITE
    }
}
