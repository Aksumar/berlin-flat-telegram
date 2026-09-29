package com.aksumar.telegram.maps

import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationLabelsTest {
    private val labels = StationLabels(VbbTransitCache())
    private val metrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().let { graphics ->
        try {
            graphics.getFontMetrics(Font(Font.SANS_SERIF, Font.BOLD, 15))
        } finally {
            graphics.dispose()
        }
    }

    @Test
    fun `interchange has separate S and U rows and retains every line`() {
        val label = labels.measure(Landmark("S+U Alexanderplatz Bhf", LandmarkKind.SUBURBAN_RAIL, 200, 200), metrics)

        assertEquals(listOf("Alexanderplatz Bhf"), label.titleLines)
        assertEquals(listOf(LandmarkKind.SUBURBAN_RAIL, LandmarkKind.SUBWAY), label.lineRows.map { it.kind })
        assertEquals(listOf("S3", "S5", "S7", "S9"), label.lineRows[0].lines.map { it.name })
        assertEquals(listOf("U2", "U5", "U8"), label.lineRows[1].lines.map { it.name })
    }

    @Test
    fun `all S and U lines stay in one row per mode even for narrow labels`() {
        val cache = VbbTransitCache()
        for (name in listOf("Friedrichstraße", "Gesundbrunnen")) {
            for (preferredWidth in listOf(220, 264, 300)) {
                val station = Landmark(name, LandmarkKind.SUBURBAN_RAIL, 200, 200)
                val label = labels.measure(station, metrics, preferredWidth)
                assertEquals(listOf(LandmarkKind.SUBURBAN_RAIL, LandmarkKind.SUBWAY), label.lineRows.map { it.kind })
                for (row in label.lineRows) {
                    assertEquals(cache.linesFor(name).filter { it.name.startsWith(row.kind.badge) }, row.lines)
                    val rowWidth = 56 + row.lines.sumOf { metrics.stringWidth(it.name) + 13 }
                    assertTrue(rowWidth <= label.width)
                }
                assertTrue(label.width > preferredWidth)
            }
        }
    }

    @Test
    fun `wide transport rows in overflow legend do not overlap or leave the image`() {
        val stations = crowdedStations() + listOf("Friedrichstraße", "Gesundbrunnen", "Hauptbahnhof", "Alexanderplatz").map {
            Landmark(it, LandmarkKind.SUBURBAN_RAIL, 480, 220)
        }
        val arranged = StationLabelArrangement(labels).arrange(stations, metrics, MapLayout(true, true))
        val legend = arranged.labels.filter { it.number != null }
        assertEquals(stations.toSet(), arranged.labels.map { it.label.station }.toSet())
        assertTrue(legend.any { it.label.lineRows.isNotEmpty() && it.label.width > 300 })
        val canvas = Rectangle(0, 0, 960, arranged.imageHeight)
        legend.forEachIndexed { index, positioned ->
            val entry = Rectangle(positioned.bounds.x - 36, positioned.bounds.y, positioned.bounds.width + 36, positioned.bounds.height)
            assertTrue(canvas.contains(entry))
            assertTrue(legend.take(index).none { it.bounds.intersects(entry) })
            assertEquals(positioned.label.lineRows.size, positioned.label.lineRows.map { it.kind }.distinct().size)
        }
    }

    @Test
    fun `long names wrap without losing text or overflowing the label`() {
        val name = "Very long station name with several words that must remain completely readable"
        val label = labels.measure(Landmark(name, LandmarkKind.SUBWAY, 2, 2), metrics)

        assertTrue(label.titleLines.size > 1)
        assertEquals(name, label.titleLines.joinToString(" "))
        assertTrue(label.width <= 300)
        assertTrue(label.titleLines.all { metrics.stringWidth(it) + 56 <= label.width })
        assertTrue(label.height >= label.titleLines.size * metrics.height)
    }

    @Test
    fun `known interchange returned as both S and U produces one complete label`() {
        val stations = listOf(
            Landmark("S+U Alexanderplatz Bhf", LandmarkKind.SUBURBAN_RAIL, 200, 200),
            Landmark("U Alexanderplatz", LandmarkKind.SUBWAY, 205, 205),
        )

        val unique = labels.distinctStations(stations)

        assertEquals(1, unique.size)
        assertEquals(2, labels.measure(unique.single(), metrics).lineRows.size)
    }

    @Test
    fun `edge and crowded stations fit without overlapping each other or reserved areas`() {
        val layout = MapLayout(hasOverview = true, approximate = true)
        val placement = StationLabelPlacement(layout)
        val stations = listOf(
            Landmark("S+U Alexanderplatz Bhf", LandmarkKind.SUBURBAN_RAIL, 2, 2),
            Landmark("U Stadtmitte", LandmarkKind.SUBWAY, 955, 4),
            Landmark("S+U Friedrichstraße Bhf", LandmarkKind.SUBURBAN_RAIL, 2, 574),
            Landmark("U Kochstraße / Checkpoint Charlie", LandmarkKind.SUBWAY, 620, 568),
        ) + (1..8).map { Landmark("Nearby station $it with a longer name", LandmarkKind.SUBWAY, 470 + it, 230) }
        val boxes = mutableListOf<Rectangle>()
        for (station in stations) {
            val label = labels.measure(station, metrics)
            val box = requireNotNull(placement.place(label)) { "No room for ${station.name}" }
            assertTrue(Rectangle(8, 8, 944, 564).contains(box))
            assertTrue(layout.reservedAreas().none { it.intersects(box) })
            assertTrue(boxes.none { it.intersects(box) })
            boxes.add(box)
        }
    }

    @Test
    fun `overflow keeps every station in non overlapping labels below the map`() {
        val stations = crowdedStations()
        val layout = MapLayout(hasOverview = true, approximate = true)
        val arranged = StationLabelArrangement(labels).arrange(stations, metrics, layout)

        assertEquals(stations.toSet(), arranged.labels.map { it.label.station }.toSet())
        assertEquals(stations.size, arranged.labels.size)
        val legend = arranged.labels.filter { it.number != null }
        assertTrue(legend.isNotEmpty())
        assertEquals((1..legend.size).toList(), legend.map { it.number })
        assertTrue(legend.all { it.bounds.y > 600 })
        val canvas = Rectangle(0, 0, 960, arranged.imageHeight)
        arranged.labels.forEachIndexed { index, positioned ->
            assertTrue(canvas.contains(positioned.bounds))
            assertTrue(arranged.labels.take(index).none { it.bounds.intersects(positioned.bounds) })
        }
    }

    @Test
    fun `composed PNG includes the last overflow label and preserves map attribution`() {
        val stations = crowdedStations()
        val layout = MapLayout(hasOverview = true, approximate = true)
        val image = BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(235, 238, 232)
            graphics.fillRect(0, 0, 960, 600)
            graphics.color = Color.BLUE
            graphics.fillRect(0, 580, 960, 20)
        } finally {
            graphics.dispose()
        }
        val arranged = StationLabelArrangement(labels).arrange(stations, metrics, layout)
        val annotated = LandmarkRenderer(VbbTransitCache()).draw(image, stations, layout)
        val bytes = MapComposer().compose(annotated, BufferedImage(288, 240, BufferedImage.TYPE_INT_RGB), layout)
        val composed = ImageIO.read(bytes.inputStream())
        assertEquals(arranged.imageHeight, composed.height)
        assertTrue(composed.height > 600)
        assertEquals(Color.BLUE.rgb, composed.getRGB(950, 590))
        val lastLabel = arranged.labels.last().bounds
        assertEquals(annotated.getRGB(lastLabel.x + 12, lastLabel.y + 12), composed.getRGB(lastLabel.x + 12, lastLabel.y + 12))
        assertTrue((lastLabel.y until lastLabel.y + lastLabel.height).any { y ->
            (lastLabel.x until lastLabel.x + lastLabel.width).any { x ->
                composed.getRGB(x, y) == Color(45, 55, 60).rgb
            }
        })
        Files.write(Path.of("build/station-labels-overflow-preview.png"), bytes)
    }

    private fun crowdedStations(): List<Landmark> = (1..45).map { index ->
        Landmark(
            "Station $index with a long name that needs several lines",
            LandmarkKind.SUBWAY,
            30 + (index % 9) * 65,
            70 + (index / 9) * 80,
        )
    }

    @Test
    fun `renderer combines duplicate interchange while retaining edge and nearby labels`() {
        val image = BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(235, 238, 232)
            graphics.fillRect(0, 0, 960, 600)
            graphics.color = Color.WHITE
            for (x in 0..960 step 70) graphics.fillRect(x, 0, 8, 600)
            for (y in 0..600 step 70) graphics.fillRect(0, y, 960, 8)
            graphics.color = Color(229, 57, 53)
            graphics.fillOval(462, 264, 36, 36)
        } finally {
            graphics.dispose()
        }
        val stations = listOf(
            Landmark("S+U Alexanderplatz Bhf", LandmarkKind.SUBURBAN_RAIL, 150, 180),
            Landmark("U Alexanderplatz", LandmarkKind.SUBWAY, 154, 181),
            Landmark("S+U Friedrichstraße Bhf", LandmarkKind.SUBURBAN_RAIL, 475, 190),
            Landmark("U Stadtmitte", LandmarkKind.SUBWAY, 470, 200),
            Landmark("U Kochstraße / Checkpoint Charlie", LandmarkKind.SUBWAY, 480, 210),
            Landmark("U Very long station name near the edge of the map", LandmarkKind.SUBWAY, 954, 5),
            Landmark("S+U Gesundbrunnen Bhf", LandmarkKind.SUBURBAN_RAIL, 4, 576),
        )
        val layout = MapLayout(hasOverview = true, approximate = true)
        val withoutDuplicate = BufferedImage(image.colorModel, image.copyData(null), image.isAlphaPremultiplied, null)
        val renderer = LandmarkRenderer(VbbTransitCache())
        val annotated = renderer.draw(image, stations, layout)
        val annotatedWithoutDuplicate = renderer.draw(withoutDuplicate, stations.filterIndexed { index, _ -> index != 1 }, layout)
        assertArrayEquals(
            annotated.getRGB(0, 0, 960, annotated.height, null, 0, 960),
            annotatedWithoutDuplicate.getRGB(0, 0, 960, annotatedWithoutDuplicate.height, null, 0, 960),
        )
        val overview = BufferedImage(288, 240, BufferedImage.TYPE_INT_RGB)
        val bytes = MapComposer().compose(annotated, overview, layout)
        val output = Path.of("build/station-labels-preview.png")
        Files.write(output, bytes)
        assertNotNull(ImageIO.read(bytes.inputStream()))
    }
}
