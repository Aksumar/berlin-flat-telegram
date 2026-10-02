package com.aksumar.telegram.maps

import java.awt.Font
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource

internal class StopRouteCatalogTest {
    private fun platform(id: String, lon: Double, lines: List<String>, parent: String = "", name: String = "Teststr. (Berlin)") =
        StopPlatform(id, parent, name, lon, 52.5, lines)

    @Test
    fun `matches expanded street names and combines directions without duplicate routes`() {
        val catalog = StopRouteCatalog(listOf(
            platform("a", 13.4, listOf("100", "N2", "X10")),
            platform("b", 13.401, listOf("100", "M76", "N1", "20")),
            platform("c", 13.5, listOf("200")),
        ))
        assertEquals(listOf("20", "100", "M76", "X10", "N1", "N2"),
            catalog.find("Teststraße", 13.4, 52.5)!!.lines.map { it.name })
        assertEquals(listOf("200"), catalog.find("Teststr.", 13.5, 52.5)!!.lines.map { it.name })
        assertNull(catalog.find("Teststraße", 13.6, 52.5))
        assertNull(catalog.find("Other", 13.4, 52.5))
        assertNull(catalog.find("Teststraße", null, null))
        assertNull(catalog.find("Teststraße", Double.NaN, 52.5))
    }

    @Test
    fun `shared parent combines routes even when platforms have different names`() {
        val catalog = StopRouteCatalog(listOf(
            platform("a", 13.4, listOf("100"), "parent"),
            platform("b", 13.403, listOf("200"), "parent", "Other platform"),
        ))
        val stop = catalog.find("Teststraße", 13.4, 52.5)!!
        assertEquals("parent", stop.id)
        assertEquals(listOf("100", "200"), stop.lines.map { it.name })
    }

    @Test
    fun `ambiguous neighboring groups remain unmatched`() {
        val catalog = StopRouteCatalog(listOf(
            platform("a", 13.4, listOf("100"), "one"),
            platform("b", 13.401, listOf("200"), "two"),
        ))
        assertNull(catalog.find("Teststraße", 13.4005, 52.5))
    }

    @ParameterizedTest
    @EnumSource(LandmarkKind::class, names = ["BUS", "TRAM"])
    fun `stop label deduplication retains distant namesakes and fallback labels`(kind: LandmarkKind) {
        val catalog = StopRouteCatalog(listOf(
            platform("a", 13.4, listOf("100")), platform("b", 13.401, listOf("200")),
            platform("c", 13.5, listOf("300")),
        ))
        val cache = VbbTransitCache(catalog, catalog)
        val labels = StationLabels(cache)
        val stations = listOf(
            Landmark("Teststraße", kind, 100, 100, 13.4, 52.5),
            Landmark("Teststr.", kind, 105, 100, 13.401, 52.5),
            Landmark("Teststraße", kind, 500, 100, 13.5, 52.5),
        )
        assertEquals(listOf(stations[0], stations[2]), labels.distinctStations(stations))
        val fallback = StationLabels(VbbTransitCache(StopRouteCatalog(emptyList()), StopRouteCatalog(emptyList())))
        assertEquals(listOf(stations[0], stations[2]), fallback.distinctStations(stations))
        assertTrue(fallback.measure(stations[0], metrics()).lineRows.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(LandmarkKind::class, names = ["BUS", "TRAM"])
    fun `many stop routes wrap without lost numbers or overlapping labels`(kind: LandmarkKind) {
        val routes = (1..24).map { (it * 10).toString() } + listOf("M76", "X76", "N81")
        val catalog = StopRouteCatalog(listOf(platform("a", 13.4, routes)))
        val cache = VbbTransitCache(catalog, catalog)
        val labels = StationLabels(cache)
        val station = Landmark("Teststraße", kind, 600, 550, 13.4, 52.5)
        val label = labels.measure(station, metrics(), 220)
        assertTrue(label.width <= 220)
        assertTrue(label.lineRows.size > 1)
        assertTrue(label.lineRows.all { it.kind == kind })
        assertEquals(routes, label.lineRows.flatMap { it.lines }.map { it.name })
        val layout = MapLayout(true, false)
        val arranged = StationLabelArrangement(labels).arrange(listOf(station) + (1..20).map {
            Landmark("Unknown stop $it", kind, 590 + it, 550)
        }, metrics(), layout)
        val canvas = Rectangle(0, 0, 960, arranged.imageHeight)
        arranged.labels.forEachIndexed { index, item ->
            assertTrue(canvas.contains(item.bounds))
            assertTrue(arranged.labels.take(index).none { it.bounds.intersects(item.bounds) })
            assertTrue(layout.reservedAreas().none { it.intersects(item.bounds) })
        }
    }

    @Test
    fun `bus and tram routes at the same stop stay separate`() {
        val cache = VbbTransitCache(
            StopRouteCatalog(listOf(platform("shared", 13.4, listOf("100", "N1")))),
            StopRouteCatalog(listOf(platform("shared", 13.4, listOf("21", "M10")))),
        )
        val labels = StationLabels(cache)
        val bus = Landmark("Teststraße", LandmarkKind.BUS, 200, 200, 13.4, 52.5)
        val tram = bus.copy(kind = LandmarkKind.TRAM)
        assertEquals(listOf(bus, tram), labels.distinctStations(listOf(bus, tram)))
        assertEquals(listOf("100", "N1"), labels.measure(bus, metrics()).lineRows.flatMap { it.lines }.map { it.name })
        val row = labels.measure(tram, metrics()).lineRows.single()
        assertEquals(LandmarkKind.TRAM, row.kind)
        assertEquals(listOf("21", "M10"), row.lines.map { it.name })
    }

    @Test
    fun `bundled tram routes appear in rendered station labels`() {
        val cache = VbbTransitCache()
        val labels = StationLabels(cache)
        val stations = listOf(
            Landmark("S Hackescher Markt", LandmarkKind.TRAM, 250, 250, 13.403145, 52.522545),
            Landmark("U Alexanderplatz", LandmarkKind.TRAM, 650, 320, 13.414628, 52.522141),
        )
        for (station in stations) {
            val row = labels.measure(station, metrics()).lineRows.single()
            assertEquals(LandmarkKind.TRAM, row.kind)
            assertEquals(listOf("M4", "M5", "M6"), row.lines.map { it.name })
        }
        val rendered = LandmarkRenderer(cache).draw(
            BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB), stations, MapLayout(false, false),
        )
        assertEquals(960, rendered.width)
        assertEquals(600, rendered.height)
        val output = Path.of("build/tram-routes-preview.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(rendered, "png", output.toFile()))
    }

    @Test
    fun `stale catalog keeps route numbers and broken or missing catalog falls back`() {
        val json = """{
            "validUntil":"2000-01-01",
            "stops":[{"id":"a","parent":"","name":"Teststr.","lon":13.4,"lat":52.5,"lines":["100"]}]
        }"""
        val stale = StopRouteCatalog.load(ByteArrayResource(json.toByteArray()))
        assertEquals(listOf("100"), stale.find("Teststraße", 13.4, 52.5)!!.lines.map { it.name })
        val broken = StopRouteCatalog.load(ByteArrayResource("not JSON".toByteArray()))
        assertNull(broken.find("Teststraße", 13.4, 52.5))
        val missing = StopRouteCatalog.load(ClassPathResource("missing-bus-catalog.json"))
        assertNull(missing.find("Teststraße", 13.4, 52.5))
    }

    private fun metrics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().let {
        try { it.getFontMetrics(Font(Font.SANS_SERIF, Font.BOLD, 15)) } finally { it.dispose() }
    }
}
