package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Address
import com.aksumar.telegram.support.event
import com.aksumar.telegram.support.testMapper
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ListingMapsTest {
    @Test
    fun `vbb station cache returns every line with its supplied official color`() {
        val cache = VbbTransitCache()

        val lines = cache.linesFor("S+U Alexanderplatz Bhf")

        assertEquals(
            setOf("S3", "S5", "S7", "S9", "U2", "U5", "U8"),
            lines.map { it.name }.toSet(),
        )
        assertEquals(Color.decode("#DA421E"), lines.single { it.name == "U2" }.color)
        assertEquals(Color.decode("#EB7405"), lines.single { it.name == "S5" }.color)
    }

    private class Provider : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var geocode =
            """{"results":[{"lon":13.38,"lat":52.53,"result_type":"building","rank":{"confidence":1,"confidence_building_level":1,"match_type":"full_match"}}]}"""
        var mapStatus = 200
        var invalidImage = false
        var places = "{\"features\":[]}"
        var placesStatus = 200
        var stops = "{\"features\":[]}"

        init {
            server.createContext("/") { exchange ->
                val query =
                    exchange.requestURI.rawQuery.split('&').associate {
                        val (key, value) = it.split('=', limit = 2)
                        key to URLDecoder.decode(value, Charsets.UTF_8)
                    }
                val path = exchange.requestURI.path
                requests += path to query
                val body =
                    if (path == "/geocode") geocode.toByteArray()
                    else if (path == "/places")
                        (if (query.getValue("categories").contains("public_transport.bus")) stops
                            else places)
                            .toByteArray()
                    else if (invalidImage) "bad image".toByteArray()
                    else {
                        val image =
                            BufferedImage(
                                query.getValue("width").toInt(),
                                query.getValue("height").toInt(),
                                BufferedImage.TYPE_INT_RGB,
                            )
                        val g = image.createGraphics()
                        g.color = if (image.width == 960) Color.GREEN else Color.BLUE
                        g.fillRect(0, 0, image.width, image.height)
                        g.dispose()
                        ByteArrayOutputStream().use { out ->
                            ImageIO.write(image, "png", out)
                            out.toByteArray()
                        }
                    }
                exchange.sendResponseHeaders(
                    if (path == "/geocode") 200
                    else if (path == "/places") placesStatus else mapStatus,
                    body.size.toLong(),
                )
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        fun maps(key: String = "test-secret") =
            GeoapifyMaps(
                key,
                testMapper,
                "http://127.0.0.1:${server.address.port}/geocode",
                "http://127.0.0.1:${server.address.port}/staticmap",
                "http://127.0.0.1:${server.address.port}/places",
            )

        override fun close() {
            server.stop(0)
        }
    }

    @Test
    fun `composes street map and overview and keeps attribution edge visible`() {
        Provider().use { p ->
            val map = requireNotNull(p.maps().create(event()))
            assertFalse(map.approximate)
            assertEquals(
                "https://www.openstreetmap.org/?mlat=52.53&mlon=13.38#map=14.5/52.53/13.38",
                map.url,
            )
            assertFalse(map.url.contains("test-secret"))
            val image = ImageIO.read(map.png.inputStream())
            assertEquals(960, image.width)
            assertEquals(600, image.height)
            assertEquals(Color.GREEN.rgb, image.getRGB(100, 100))
            assertEquals(Color.BLUE.rgb, image.getRGB(810, 450))
            assertEquals(Color.GREEN.rgb, image.getRGB(940, 590))
            assertEquals(5, p.requests.size)
            assertEquals("osm-bright", p.requests[1].second["style"])
            assertEquals("14.5", p.requests[1].second["zoom"])
            assertTrue(p.requests[1].second.getValue("marker").contains("size:32;icon:home"))
            assertTrue(p.requests[2].second.getValue("marker").contains("type:circle"))
            assertEquals(event().address.full, p.requests[0].second["text"])
            assertEquals("rect:13.08,52.33,13.77,52.68", p.requests[0].second["filter"])
            assertTrue(
                p.requests
                    .filter { it.first == "/staticmap" }
                    .all { it.second["attribution"] == "default" }
            )
        }
    }

    @Test
    fun `station labels use nearby place coordinates and places failure keeps map`() {
        Provider().use { p ->
            p.places =
                """{"features":[{"properties":{"name":"U Test Station","lon":13.375,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}]}"""
            val labelled = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(Color(35, 107, 158).rgb, labelled.getRGB(315, 300))
            p.placesStatus = 503
            val fallback = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(Color.GREEN.rgb, fallback.getRGB(315, 300))
        }
    }

    @Test
    fun `bus is queried only when no rail station is found`() {
        Provider().use { p ->
            val station =
                """{"properties":{"name":"U Station","lon":13.385,"lat":52.533,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
            p.places = """{"features":[$station]}"""
            p.maps().create(event())
            assertEquals(1, p.requests.count { it.first == "/places" })
            p.requests.clear()
            p.places = """{"features":[]}"""
            p.stops =
                """{"features":[{"properties":{"name":"Bus stop","lon":13.385,"lat":52.533,"categories":["public_transport.bus"]}}]}"""
            val fallback = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(2, p.requests.count { it.first == "/places" })
            assertTrue(
                fallback.getRGB(0, 0, 960, 600, null, 0, 960).any { it == Color(118, 78, 151).rgb }
            )
        }
    }

    @Test
    fun `all visible subway stations are marked and duplicate names are collapsed`() {
        Provider().use { p ->
            fun station(name: String, lon: Double) =
                """{"properties":{"name":"$name","lon":$lon,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
            val first = station("U First", 13.375)
            val second = station("U Second", 13.385)
            p.places = """{"features":[$first,$second]}"""
            val map = requireNotNull(p.maps().create(event()))
            val image = ImageIO.read(map.png.inputStream())
            assertEquals(Color(35, 107, 158).rgb, image.getRGB(315, 300))
            assertEquals(Color(35, 107, 158).rgb, image.getRGB(645, 300))
            val request = p.requests.first { it.first == "/places" }.second
            assertTrue(request.getValue("filter").startsWith("rect:"))
            assertEquals("500", request["limit"])
            p.places = """{"features":[$first,$second,${station("U First", 13.374)}]}"""
            assertArrayEquals(map.png, requireNotNull(p.maps().create(event())).png)
        }
    }

    @Test
    fun `district and unconfirmed buildings are labelled approximate`() {
        Provider().use { p ->
            val exact = p.geocode
            p.geocode = exact.replace("building", "district")
            assertTrue(requireNotNull(p.maps().create(event())).approximate)
            assertEquals("12.5", p.requests[1].second["zoom"])
            p.geocode =
                exact.replace("confidence_building_level\":1", "confidence_building_level\":0.5")
            assertTrue(requireNotNull(p.maps().create(event())).approximate)
        }
    }

    @Test
    fun `missing key and missing address never call provider`() {
        Provider().use { p ->
            assertNull(p.maps("").create(event()))
            assertNull(
                p.maps().create(event().copy(address = Address(null, null, null, null, null, null)))
            )
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test
    fun `uncertain broad or out of area matches do not produce misleading maps`() {
        Provider().use { p ->
            val valid = p.geocode
            for (body in
                listOf(
                    "{\"results\":[]}",
                    valid.replace("13.38", "11.58"),
                    valid.replace("confidence\":1", "confidence\":0.4"),
                    valid.replace("building", "city"),
                    valid.replace("\"lon\":13.38,", ""),
                )) {
                p.requests.clear()
                p.geocode = body
                assertNull(p.maps().create(event()))
                assertEquals(1, p.requests.size)
            }
        }
    }

    @Test
    fun `provider quota errors malformed responses and invalid images degrade to text`() {
        Provider().use { p ->
            p.mapStatus = 429
            assertNull(p.maps().create(event()))
            p.mapStatus = 200
            p.invalidImage = true
            assertNull(p.maps().create(event()))
            p.geocode = "invalid JSON"
            assertNull(p.maps().create(event()))
        }
    }
}
