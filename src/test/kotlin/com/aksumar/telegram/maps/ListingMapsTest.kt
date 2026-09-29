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
        assertEquals(lines, cache.linesFor("S/U Alexanderplatz Bhf."))
    }

    private class Provider : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var geocode =
            """{"results":[{"lon":13.38,"lat":52.53,"result_type":"building","rank":{"confidence":1,"confidence_building_level":1,"match_type":"full_match"}}]}"""
        var mapStatus = 200
        var overviewStatus = 200
        var invalidImage = false
        var places = "{\"features\":[]}"
        var placesPage: ((String) -> String)? = null
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
                    else if (path == "/places") {
                        val categories = query.getValue("categories")
                        when {
                            categories.startsWith("public_transport.subway") ->
                                placesPage?.invoke(query.getValue("offset")) ?: places
                            categories.startsWith("public_transport.tram") -> stops
                            else -> "{\"features\":[]}"
                        }.toByteArray()
                    }
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
                    else if (path == "/places") placesStatus
                    else if (query["width"] == "288") overviewStatus else mapStatus,
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

        fun transportRequests() =
            requests.filter {
                it.first == "/places" &&
                    it.second["categories"]?.startsWith("public_transport") == true
            }

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
                "https://www.google.com/maps/search/?api=1&query=52.53%2C13.38",
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
            assertEquals("15.5", p.requests[1].second["zoom"])
            assertFalse(p.requests[1].second.getValue("styleCustomization").contains("highway"))
            assertFalse(p.requests[1].second.getValue("styleCustomization").contains("poi-level-1"))
            assertFalse(p.requests[1].second.getValue("styleCustomization").contains("poi-railway"))
            assertFalse(p.requests[1].second.getValue("styleCustomization").contains("airport"))
            assertTrue(p.requests[1].second.getValue("marker").contains("size:36;icon:home"))
            assertFalse(p.requests[2].second.containsKey("marker"))
            assertEquals(
                "circle:13.38,52.53,6;fillcolor:#e53935;fillopacity:1;linecolor:#ffffff;linewidth:1",
                p.requests[2].second["geometry"],
            )
            assertEquals("288", p.requests[2].second["width"])
            assertEquals("240", p.requests[2].second["height"])
            assertEquals("10", p.requests[2].second["zoom"])
            assertEquals("lonlat:13.38,52.53", p.requests[2].second["center"])
            assertEquals(event().address.full, p.requests[0].second["text"])
            assertEquals("rect:13.08,52.33,13.77,52.68", p.requests[0].second["filter"])
            assertTrue(
                p.requests
                    .filter { it.first == "/staticmap" }
                    .all { it.second["attribution"] == "default" && "lang" !in it.second }
            )
        }
    }

    @Test
    fun `station labels use nearby place coordinates and places failure keeps map`() {
        Provider().use { p ->
            p.places =
                """{"features":[{"properties":{"name":"U Test Station","lon":13.375,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}]}"""
            val labelled = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(Color(35, 107, 158).rgb, labelled.getRGB(150, 300))
            p.placesStatus = 503
            val earlierRequests = p.requests.size
            val fallback = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(Color.GREEN.rgb, fallback.getRGB(150, 300))
            assertEquals(1, p.requests.drop(earlierRequests).count { it.first == "/places" })
        }
    }

    @Test
    fun `places failure does not disable landmarks for subsequent listings`() {
        Provider().use { provider ->
            val maps = provider.maps()
            provider.places =
                """{"features":[{"properties":{"name":"U Test Station","lon":13.375,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}]}"""
            provider.placesStatus = 503
            val fallback = ImageIO.read(requireNotNull(maps.create(event())).png.inputStream())
            assertEquals(Color.GREEN.rgb, fallback.getRGB(150, 300))

            provider.placesStatus = 200
            val recovered = ImageIO.read(requireNotNull(maps.create(event())).png.inputStream())
            assertEquals(Color(35, 107, 158).rgb, recovered.getRGB(150, 300))
        }
    }

    @Test
    fun `tram takes priority over bus when there are no visible rail stations`() {
        Provider().use { provider ->
            val tram = """{"properties":{"name":"Tram stop","lon":13.375,"lat":52.53,"categories":["public_transport.tram"]}}"""
            val bus = """{"properties":{"name":"Bus stop","lon":13.3825,"lat":52.53,"categories":["public_transport.bus"]}}"""
            provider.stops = """{"features":[$tram]}"""
            val tramOnly = requireNotNull(provider.maps().create(event())).png

            provider.stops = """{"features":[$tram,$bus]}"""
            assertArrayEquals(tramOnly, requireNotNull(provider.maps().create(event())).png)
        }
    }

    @Test
    fun `address parts form geocoding query when full address is blank`() {
        Provider().use { provider ->
            val address = Address(" ", "Invalidenstraße", "42", "10115", "Berlin", "Mitte")
            assertNotNull(provider.maps().create(event().copy(address = address)))
            assertEquals(
                "Invalidenstraße 42, 10115, Mitte, Berlin",
                provider.requests.first().second["text"],
            )
        }
    }

    @Test
    fun `non transport places are neither requested nor drawn`() {
        Provider().use { provider ->
            val maps = provider.maps()
            val baseline = requireNotNull(maps.create(event())).png
            val categories = listOf(
                "commercial.supermarket",
                "commercial.convenience",
                "healthcare.pharmacy",
                "commercial.health_and_beauty.pharmacy",
                "healthcare.hospital",
                "leisure.park",
                "education.school",
                "childcare.kindergarten",
                "commercial.food_and_drink.coffee_and_tea",
            )
            val features = categories.joinToString(",") { category ->
                """{"properties":{"name":"Test place","lon":13.375,"lat":52.53,"categories":["$category"]}}"""
            }
            provider.places = """{"features":[$features]}"""
            provider.stops = provider.places

            assertArrayEquals(baseline, requireNotNull(maps.create(event())).png)
            val placeRequests = provider.requests.filter { it.first == "/places" }
            assertTrue(placeRequests.isNotEmpty())
            assertTrue(placeRequests.all { request ->
                request.second.getValue("categories").split(',').all { it.startsWith("public_transport.") }
            })
            val style = provider.requests.first { it.first == "/staticmap" }.second.getValue("styleCustomization")
            for (level in 2..3) assertTrue(style.contains("poi-level-$level:none"))
        }
    }

    @Test
    fun `bus is queried only when no rail station is found`() {
        Provider().use { p ->
            val station =
                """{"properties":{"name":"U Station","lon":13.385,"lat":52.5315,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
            p.places = """{"features":[$station]}"""
            p.maps().create(event())
            assertEquals(1, p.transportRequests().size)
            p.requests.clear()
            p.places = """{"features":[]}"""
            p.stops =
                """{"features":[{"properties":{"name":"Bus stop","lon":13.385,"lat":52.5315,"categories":["public_transport.bus"]}}]}"""
            val fallback = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())
            assertEquals(2, p.transportRequests().size)
            assertTrue(
                fallback.getRGB(0, 0, 960, 600, null, 0, 960).any { it == Color(118, 78, 151).rgb }
            )
        }
    }

    @Test
    fun `rail station hidden by overview does not suppress bus fallback`() {
        Provider().use { p ->
            p.places =
                """{"features":[{"properties":{"name":"U Hidden","lon":13.385,"lat":52.5285,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}]}"""
            p.stops =
                """{"features":[{"properties":{"name":"Bus stop","lon":13.375,"lat":52.53,"categories":["public_transport.bus"]}}]}"""

            val image = ImageIO.read(requireNotNull(p.maps().create(event())).png.inputStream())

            assertEquals(2, p.transportRequests().size)
            assertTrue(image.getRGB(0, 0, 960, 600, null, 0, 960).any { it == Color(118, 78, 151).rgb })
        }
    }

    @Test
    fun `places pagination is bounded even when every page is full`() {
        Provider().use { p ->
            p.placesPage = { offset ->
                val station =
                    """{"properties":{"place_id":"$offset","name":"U Station","lon":13.375,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
                """{"features":[$station,${List(499) { "{}" }.joinToString(",")}]}"""
            }

            assertNotNull(p.maps().create(event()))
            assertEquals(2, p.transportRequests().size)
        }
    }

    @Test
    fun `overview failure keeps the main map`() {
        Provider().use { p ->
            p.overviewStatus = 503

            val map = requireNotNull(p.maps().create(event()))
            val image = ImageIO.read(map.png.inputStream())

            assertEquals(Color.GREEN.rgb, image.getRGB(810, 450))
        }
    }

    @Test
    fun `all visible subway stations are marked and duplicate names are collapsed`() {
        Provider().use { p ->
            fun station(name: String, lon: Double) =
                """{"properties":{"name":"$name","lon":$lon,"lat":52.53,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
            val first = station("U First", 13.375)
            val second = station("U Second", 13.3825)
            p.places = """{"features":[$first,$second]}"""
            val map = requireNotNull(p.maps().create(event()))
            val image = ImageIO.read(map.png.inputStream())
            assertEquals(Color(35, 107, 158).rgb, image.getRGB(150, 300))
            assertEquals(Color(35, 107, 158).rgb, image.getRGB(645, 300))
            val request = p.requests.first { it.first == "/places" }.second
            assertTrue(request.getValue("filter").startsWith("rect:"))
            assertEquals("500", request["limit"])
            p.places = """{"features":[$first,$second,${station("First", 13.374)}]}"""
            assertArrayEquals(map.png, requireNotNull(p.maps().create(event())).png)
        }
    }

    @Test
    fun `crowded map includes the overflow legend in the returned PNG`() {
        Provider().use { provider ->
            val stations = (0 until 45).joinToString(",") { index ->
                val longitude = 13.375 + (index % 9) * 0.0008
                val latitude = 52.532 - (index / 9) * 0.0004
                """{"properties":{"name":"U Station $index with a long name requiring several lines","lon":$longitude,"lat":$latitude,"categories":["public_transport.subway"],"datasource":{"raw":{"railway":"station"}}}}"""
            }
            provider.places = """{"features":[$stations]}"""

            val result = requireNotNull(provider.maps().create(event()))
            val image = ImageIO.read(result.png.inputStream())

            assertEquals(960, image.width)
            assertTrue(image.height > 600)
            assertEquals(Color.GREEN.rgb, image.getRGB(940, 590))
        }
    }

    @Test
    fun `district and unconfirmed buildings are labelled approximate`() {
        Provider().use { p ->
            val exact = p.geocode
            p.geocode = exact.replace("building", "district")
            val districtMap = requireNotNull(p.maps().create(event()))
            assertTrue(districtMap.approximate)
            assertEquals(
                "https://www.google.com/maps/@?api=1&map_action=map&center=52.53%2C13.38&zoom=12",
                districtMap.url,
            )
            assertTrue(p.requests[1].second.getValue("marker").contains("type:circle"))
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
