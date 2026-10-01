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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class ListingMapsTest {
    @Test
    fun `exhausted budget reports operation and timing without making a request`() {
        val error = assertThrows(MapGenerationException::class.java) {
            GeoapifyClient("test-secret").get("https://api.geoapify.com/v1/geocode/search",
                emptyMap(), "geocoding", System.nanoTime() - 1_000_000)
        }
        assertEquals("истекло время генерации карты", error.reason)
        assertTrue(error.details.contains("operation=geocoding"))
        assertTrue(error.details.contains("endpoint=api.geoapify.com/v1/geocode/search"))
        assertTrue(error.details.contains("timeoutMs=0"))
        assertTrue(error.details.contains("remainingBudgetMs=-"))
        assertTrue(error.details.contains("requestElapsedMs="))
        assertFalse(error.details.contains("test-secret"))
    }

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
            """{"results":[{"lon":13.38,"lat":52.53,"postcode":"10115","city":"Berlin","district":"Mitte","street":"Musterstraße","housenumber":"12","result_type":"building","rank":{"confidence":1,"confidence_building_level":1,"match_type":"full_match"}}]}"""
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
            assertFalse(p.requests[1].second.getValue("styleCustomization").contains("place-other:none"))
            assertTrue(p.requests[1].second.getValue("marker").contains("size:36;icon:home"))
            assertFalse(p.requests[2].second.containsKey("marker"))
            assertEquals(
                "circle:13.38,52.53,6;fillcolor:#e53935;fillopacity:1;linecolor:#ffffff;linewidth:1",
                p.requests[2].second["geometry"],
            )
            assertEquals("288", p.requests[2].second["width"])
            assertEquals("240", p.requests[2].second["height"])
            assertEquals("11", p.requests[2].second["zoom"])
            assertEquals("place_suburb:#4b5563;11|place_other:#4b5563;10", p.requests[2].second["styleCustomization"])
            assertEquals("lonlat:13.38,52.53", p.requests[2].second["center"])
            assertEquals("Musterstraße 12, 10115 Berlin, Mitte", p.requests[0].second["text"])
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
            provider.geocode = provider.geocode.replace("Musterstraße", "Invalidenstraße").replace("housenumber\":\"12", "housenumber\":\"42")
            assertNotNull(provider.maps().create(event().copy(address = address)))
            assertEquals(
                "Invalidenstraße 42, 10115 Berlin, Mitte",
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
    fun `overview failure keeps the main map`(output: CapturedOutput) {
        Provider().use { p ->
            p.overviewStatus = 503

            val map = requireNotNull(p.maps().create(event()))
            val image = ImageIO.read(map.png.inputStream())

            assertEquals(Color.GREEN.rgb, image.getRGB(810, 450))
            assertTrue(output.all.contains("Overview unavailable; keeping main map"))
            assertTrue(output.all.contains("operation=overview map"))
            assertTrue(output.all.contains("httpStatus=503"))
            assertTrue(output.all.contains("longitude=13.38, latitude=52.53"))
            assertFalse(output.all.contains("test-secret"))
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
            assertEquals("не настроен API-ключ Geoapify",
                assertThrows(MapGenerationException::class.java) { p.maps("").create(event()) }.reason)
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = Address(null, null, null, null, null, null))) }
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
                assertThrows(MapGenerationException::class.java) { p.maps().create(event()) }
                assertEquals(1, p.requests.size)
            }
        }
    }

    @Test
    fun `wrong postcode city street or house never produces a map even with full confidence`() {
        Provider().use { p ->
            val valid = p.geocode
            for (body in listOf(
                valid.replace("10115", "12459"),
                valid.replace("Berlin", "Panketal"),
                valid.replace("Musterstraße", "Andere Straße"),
                valid.replace("housenumber\":\"12", "housenumber\":\"34"),
                valid.replace("\"postcode\":\"10115\",", ""),
                valid.replace("\"city\":\"Berlin\",", ""),
            )) {
                p.requests.clear()
                p.geocode = body
                assertThrows(MapGenerationException::class.java) { p.maps().create(event()) }
                assertEquals(listOf("/geocode"), p.requests.map { it.first })
            }
        }
    }

    @Test
    fun `Helmholtzstrasse selects Charlottenburg instead of the first wrong postcode`() {
        Provider().use { p ->
            val wrong = """{"lon":13.5077344,"lat":52.4668988,"postcode":"12459","city":"Berlin","suburb":"Oberschöneweide","street":"Helmholtzstraße","housenumber":"34","result_type":"building","rank":{"confidence":1,"confidence_building_level":1,"match_type":"full_match"}}"""
            val right = wrong.replace("12459", "10587").replace("Oberschöneweide", "Charlottenburg")
                .replace("13.5077344", "13.3236068").replace("52.4668988", "52.5214705")
            val address = Address("Helmholtzstraße 34", "Helmholtzstraße", "34", "10587", "Berlin", "Charlottenburg")
            p.geocode = """{"results":[$wrong,$right]}"""
            assertFalse(requireNotNull(p.maps().create(event().copy(address = address))).approximate)
            assertEquals("5", p.requests.first().second["limit"])
            assertEquals("lonlat:13.3236068,52.5214705", p.requests[1].second["center"])
            assertEquals("lonlat:13.3236068,52.5214705", p.requests[2].second["center"])
            p.requests.clear()
            p.geocode = """{"results":[$wrong]}"""
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address)) }
            assertEquals(1, p.requests.size)
        }
    }

    @Test
    fun `postcode in legacy full address is validated and street abbreviations match`() {
        Provider().use { p ->
            p.geocode = p.geocode.replace("Musterstraße", "Musterstr.")
            val address = Address("Musterstraße 12, 10115 Berlin", null, null, null, null, null)
            assertFalse(requireNotNull(p.maps().create(event().copy(address = address))).approximate)
            p.geocode = p.geocode.replace("10115", "12459")
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address)) }
        }
    }

    @Test
    fun `missing postcode requires matching borough or neighbourhood and rejects ambiguity`() {
        Provider().use { p ->
            val address = event().address.copy(full = "Musterstraße 12", postalCode = null, district = "Mitte")
            assertNotNull(p.maps().create(event().copy(address = address)))
            p.geocode = p.geocode.replace("district\":\"Mitte", "suburb\":\"Mitte")
            assertNotNull(p.maps().create(event().copy(address = address)))
            val candidate = testMapper.readTree(p.geocode).path("results")[0].toString()
            p.geocode = """{"results":[$candidate,${candidate.replace("13.38", "13.50")}]}"""
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address)) }
            p.geocode = """{"results":[$candidate]}""".replace("Mitte", "Spandau")
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address)) }
            p.requests.clear()
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address.copy(district = null))) }
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test
    fun `house suffix whitespace is normalized but ranges are not collapsed`() {
        Provider().use { p ->
            val address = event().address.copy(houseNumber = "12 A")
            p.geocode = p.geocode.replace("housenumber\":\"12", "housenumber\":\"12A")
            assertFalse(requireNotNull(p.maps().create(event().copy(address = address))).approximate)
            p.geocode = p.geocode.replace("12A", "13")
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address.copy(houseNumber = "1-3"))) }
        }
    }

    @Test
    fun `postcode only address can show area but never a house`() {
        Provider().use { p ->
            val address = Address("10115, Berlin", null, null, "10115", "Berlin", null)
            assertThrows(MapGenerationException::class.java) { p.maps().create(event().copy(address = address)) }
            p.requests.clear()
            p.geocode = p.geocode.replace("building", "postcode")
            assertTrue(requireNotNull(p.maps().create(event().copy(address = address))).approximate)
            assertEquals("12.5", p.requests[1].second["zoom"])
            assertTrue(p.requests[1].second.getValue("marker").contains("type:circle"))
        }
    }

    @Test
    fun `provider quota errors malformed responses and invalid images degrade to text`() {
        Provider().use { p ->
            p.mapStatus = 429
            val quotaError = assertThrows(MapGenerationException::class.java) { p.maps().create(event()) }
            assertEquals("Geoapify вернул HTTP 429", quotaError.reason)
            for (field in listOf("stage=detail map", "generationElapsedMs=", "generationBudgetMs=30000",
                "operation=detail map", "httpStatus=429", "requestElapsedMs=", "timeoutMs=", "remainingBudgetMs=", "responseBytes=")) {
                assertTrue(quotaError.details.contains(field), quotaError.details)
            }
            assertFalse(quotaError.details.contains("test-secret"))
            p.mapStatus = 200
            p.invalidImage = true
            assertEquals("Geoapify вернул некорректное изображение карты",
                assertThrows(MapGenerationException::class.java) { p.maps().create(event()) }.reason)
            p.geocode = "invalid JSON apiKey=test-secret"
            val jsonError = assertThrows(MapGenerationException::class.java) { p.maps().create(event()) }
            assertEquals("Geoapify вернул некорректный ответ с координатами", jsonError.reason)
            assertTrue(jsonError.details.contains("stage=geocoding"))
            assertTrue(jsonError.details.contains("exception=com.fasterxml.jackson.core.JsonParseException"))
            assertFalse(jsonError.details.contains("test-secret"))
        }
    }
}
