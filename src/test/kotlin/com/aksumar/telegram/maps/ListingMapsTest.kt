package com.aksumar.telegram.maps

import com.aksumar.telegram.contract.Address
import com.aksumar.telegram.support.event

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import javax.imageio.ImageIO

class ListingMapsTest {
    private class Provider : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var geocode = """{"results":[{"lon":13.38,"lat":52.53,"result_type":"building","rank":{"confidence":1,"confidence_building_level":1,"match_type":"full_match"}}]}"""
        var mapStatus = 200
        var invalidImage = false
        init {
            server.createContext("/") { exchange ->
                val query = exchange.requestURI.rawQuery.split('&').associate {
                    val (key, value) = it.split('=', limit = 2)
                    key to URLDecoder.decode(value, Charsets.UTF_8)
                }
                val path = exchange.requestURI.path
                requests += path to query
                val body = if (path == "/geocode") geocode.toByteArray() else if (invalidImage) "bad image".toByteArray() else {
                    val image = BufferedImage(query.getValue("width").toInt(), query.getValue("height").toInt(), BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.color = if (image.width == 640) Color.GREEN else Color.BLUE
                    g.fillRect(0, 0, image.width, image.height)
                    g.dispose()
                    ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
                }
                exchange.sendResponseHeaders(if (path == "/geocode") 200 else mapStatus, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }
        fun maps(key: String = "test-secret") = GeoapifyMaps(key,
            "http://127.0.0.1:${server.address.port}/geocode", "http://127.0.0.1:${server.address.port}/staticmap")
        override fun close() { server.stop(0) }
    }

    @Test fun `composes street map and overview and keeps attribution edge visible`() {
        Provider().use { p ->
            val map = requireNotNull(p.maps().create(event()))
            assertFalse(map.approximate)
            assertEquals("https://www.openstreetmap.org/?mlat=52.53&mlon=13.38#map=14/52.53/13.38", map.url)
            assertFalse(map.url.contains("test-secret"))
            val image = ImageIO.read(map.png.inputStream())
            assertEquals(640, image.width)
            assertEquals(400, image.height)
            assertEquals(Color.GREEN.rgb, image.getRGB(100, 100))
            assertEquals(Color.BLUE.rgb, image.getRGB(500, 250))
            assertEquals(Color.GREEN.rgb, image.getRGB(620, 390))
            assertEquals(3, p.requests.size)
            assertEquals("osm-carto", p.requests[1].second["style"])
            assertEquals("14", p.requests[1].second["zoom"])
            assertTrue(p.requests[2].second.getValue("marker").contains("type:circle"))
            assertEquals(event().address.full, p.requests[0].second["text"])
            assertEquals("rect:13.08,52.33,13.77,52.68", p.requests[0].second["filter"])
            assertTrue(p.requests.drop(1).all { it.second["attribution"] == "default" })
        }
    }

    @Test fun `district and unconfirmed buildings are labelled approximate`() {
        Provider().use { p ->
            val exact = p.geocode
            p.geocode = exact.replace("building", "district")
            assertTrue(requireNotNull(p.maps().create(event())).approximate)
            assertEquals("12", p.requests[1].second["zoom"])
            p.geocode = exact.replace("confidence_building_level\":1", "confidence_building_level\":0.5")
            assertTrue(requireNotNull(p.maps().create(event())).approximate)
        }
    }

    @Test fun `missing key and missing address never call provider`() {
        Provider().use { p ->
            assertNull(p.maps("").create(event()))
            assertNull(p.maps().create(event().copy(address = Address(null, null, null, null, null, null))))
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `uncertain broad or out of area matches do not produce misleading maps`() {
        Provider().use { p ->
            val valid = p.geocode
            for (body in listOf("{\"results\":[]}", valid.replace("13.38", "11.58"),
                valid.replace("confidence\":1", "confidence\":0.4"), valid.replace("building", "city"),
                valid.replace("\"lon\":13.38,", ""))) {
                p.requests.clear()
                p.geocode = body
                assertNull(p.maps().create(event()))
                assertEquals(1, p.requests.size)
            }
        }
    }

    @Test fun `provider quota errors malformed responses and invalid images degrade to text`() {
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
