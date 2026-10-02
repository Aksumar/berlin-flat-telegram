package com.aksumar.telegram.maps

import com.aksumar.telegram.support.testMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("map-examples")
class BusMapExample {
    @Test
    fun mariendorf() {
        val apiKey = System.getenv("GEOAPIFY_API_KEY")?.takeIf { it.isNotBlank() }
            ?: Files.readAllLines(Path.of(".env")).first { it.startsWith("GEOAPIFY_API_KEY=") }
                .substringAfter('=').trim().trim('"', '\'')
        val client = GeoapifyClient(apiKey)
        val maps = GeoapifyStaticMaps(client, "https://maps.geoapify.com/v1/staticmap")
        val location = GeocodedLocation(13.396, 52.4158, approximate = false, detailZoom = 15.5)
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        val detail = maps.detail(location, deadline)
        val overview = requireNotNull(maps.overviewOrNull(location, deadline))
        val layout = MapLayout(true, false)
        val stations = GeoapifyPlaces(client, testMapper, "https://api.geoapify.com/v2/places").findVisible(
            MapViewport(location.longitude, location.latitude, location.detailZoom), layout, deadline)
        val cache = VbbTransitCache()
        val visible = StationLabels(cache).distinctStations(stations)
        val image = LandmarkRenderer(cache).draw(detail, stations, layout)
        val output = Path.of("build/map-diagnostics/bus-routes-mariendorf.png")
        Files.createDirectories(output.parent)
        val bytes = MapComposer().compose(image, overview, layout)
        Files.write(output, bytes)
        assertNotNull(ImageIO.read(bytes.inputStream()))
        Files.write(output.resolveSibling("bus-stops.json"), testMapper.writeValueAsBytes(stations))
        visible.forEach { println("${it.name}: ${cache.stopFor(it)?.lines?.joinToString { line -> line.name }}") }
        assertEquals(5, visible.size)
        assertTrue(visible.all { it.kind == LandmarkKind.BUS })
        assertTrue(visible.all { cache.stopFor(it)?.lines?.isNotEmpty() == true },
            "Unmatched stops: ${visible.filter { cache.stopFor(it) == null }.map { it.name }}")
    }
}
