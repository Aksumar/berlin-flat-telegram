package com.aksumar.telegram.maps

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal class LandmarkRenderer(transitCache: VbbTransitCache) {
    private val arrangement = StationLabelArrangement(StationLabels(transitCache))

    fun draw(image: BufferedImage, landmarks: List<Landmark>, layout: MapLayout): BufferedImage {
        val measurementGraphics = image.createGraphics()
        val arranged = try {
            measurementGraphics.font = LABEL_FONT
            arrangement.arrange(landmarks, measurementGraphics.fontMetrics, layout)
        } finally {
            measurementGraphics.dispose()
        }
        val result = BufferedImage(image.width, arranged.imageHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        try {
            graphics.color = Color(235, 238, 242)
            graphics.fillRect(0, 0, result.width, result.height)
            graphics.drawImage(image, 0, 0, null)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.font = LABEL_FONT
            // Connectors are beneath all labels, so they cannot obscure station names.
            arranged.labels.forEach {
                drawConnector(graphics, it.label.station, it.markerBounds ?: it.bounds)
            }
            arranged.labels.forEach { positioned ->
                drawStationLabel(graphics, positioned.label, positioned.bounds)
                positioned.number?.let { number ->
                    drawNumber(graphics, number, requireNotNull(positioned.markerBounds))
                    drawNumber(graphics, number, Rectangle(
                        positioned.bounds.x - StationLabelArrangement.NUMBER_WIDTH,
                        positioned.bounds.y + 5,
                        StationLabelArrangement.NUMBER_WIDTH,
                        24,
                    ))
                }
            }
            if (arranged.labels.any { it.label.lineRows.any { row -> row.kind == LandmarkKind.BUS } }) {
                graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                val credit = "Маршруты: VBB · CC BY 4.0"
                graphics.color = Color(255, 255, 255, 230)
                graphics.fillRect(0, 580, graphics.fontMetrics.stringWidth(credit) + 12, 20)
                graphics.color = Color(45, 55, 60)
                graphics.drawString(credit, 6, 594)
            }
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun drawNumber(graphics: Graphics2D, number: Int, bounds: Rectangle) {
        graphics.color = Color(45, 55, 60)
        graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10)
        graphics.color = Color.WHITE
        val text = number.toString()
        graphics.drawString(text, bounds.x + (bounds.width - graphics.fontMetrics.stringWidth(text)) / 2, bounds.y + 18)
    }

    private fun drawConnector(graphics: Graphics2D, station: Landmark, box: Rectangle) {
        graphics.color = transportColor(station.kind)
        val connectorX = station.x.coerceIn(box.x, box.x + box.width)
        val connectorY = station.y.coerceIn(box.y, box.y + box.height)
        graphics.drawLine(station.x, station.y, connectorX, connectorY)
        graphics.fillOval(station.x - 3, station.y - 3, 6, 6)
    }

    private fun drawStationLabel(graphics: Graphics2D, label: StationLabel, box: Rectangle) {
        val station = label.station
        graphics.color = Color(255, 255, 255, 245)
        graphics.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10)
        val titleX = box.x + StationLabel.HORIZONTAL_PADDING +
            if (label.lineRows.isEmpty()) StationLabel.BADGE_COLUMN_WIDTH else 0
        graphics.color = Color(45, 55, 60)
        label.titleLines.forEachIndexed { index, title ->
            graphics.drawString(title, titleX, box.y + StationLabel.VERTICAL_PADDING + 14 + index * StationLabel.TEXT_LINE_HEIGHT)
        }
        if (label.lineRows.isEmpty()) {
            drawBadge(graphics, station.kind, box.x + StationLabel.HORIZONTAL_PADDING, box.y + 5)
        }
        label.lineRows.forEachIndexed { index, row ->
            val rowY = box.y + label.titleHeight + index * StationLabel.TRANSPORT_ROW_HEIGHT
            drawBadge(graphics, row.kind, box.x + StationLabel.HORIZONTAL_PADDING, rowY)
            drawLineChips(graphics, row.lines, box.x + StationLabel.HORIZONTAL_PADDING + StationLabel.BADGE_COLUMN_WIDTH, rowY)
        }
    }

    private fun drawBadge(graphics: Graphics2D, kind: LandmarkKind, x: Int, y: Int) {
        graphics.color = transportColor(kind)
        graphics.fillOval(x, y, 24, 24)
        graphics.color = Color.WHITE
        graphics.drawString(kind.badge, x + 12 - graphics.fontMetrics.stringWidth(kind.badge) / 2, y + 18)
    }

    private fun drawLineChips(graphics: Graphics2D, lines: List<TransitLine>, startX: Int, rowY: Int) {
        var chipX = startX
        for (line in lines) {
            val chipWidth = graphics.fontMetrics.stringWidth(line.name) + StationLabel.CHIP_PADDING
            graphics.color = line.color
            graphics.fillRoundRect(chipX, rowY + 2, chipWidth, 20, 7, 7)
            graphics.color = contrastingTextColor(line.color)
            graphics.drawString(line.name, chipX + 5, rowY + 17)
            chipX += chipWidth + StationLabel.CHIP_GAP
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
    private companion object {
        val LABEL_FONT = Font(Font.SANS_SERIF, Font.BOLD, 15)
    }
}
