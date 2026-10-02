package com.aksumar.telegram.maps

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal class MapComposer {
    fun compose(detail: BufferedImage, overview: BufferedImage?, layout: MapLayout): ByteArray {
        val image = BufferedImage(detail.width, detail.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(detail, 0, 0, null)
            if (overview != null) drawOverview(graphics, overview)
            if (layout.approximate) drawApproximateLocationNotice(graphics)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun drawOverview(graphics: Graphics2D, overview: BufferedImage) {
        val bounds = MapLayout.overviewBounds
        graphics.color = Color.WHITE
        graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14)
        graphics.color = Color(65, 80, 90)
        graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        graphics.drawString("БЕРЛИН", bounds.x + 10, bounds.y + 19)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(overview, bounds.x + 6, bounds.y + 27,
            MapLayout.OVERVIEW_WIDTH, MapLayout.OVERVIEW_HEIGHT, null)
    }

    private fun drawApproximateLocationNotice(graphics: Graphics2D) {
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        graphics.color = Color.WHITE
        graphics.fillRoundRect(10, 10, 280, 28, 8, 8)
        graphics.color = Color(65, 80, 90)
        graphics.drawString("Примерное расположение", 20, 29)
    }
}
