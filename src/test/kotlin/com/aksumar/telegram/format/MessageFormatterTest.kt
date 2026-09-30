package com.aksumar.telegram.format

import com.aksumar.telegram.exception.DeliveryException
import com.aksumar.telegram.model.Address
import com.aksumar.telegram.support.event
import java.net.URLDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageFormatterTest {
    private val formatter = MessageFormatter()

    @Test
    fun `map link uses the listing address without map generation`() {
        val item = event()
        val url = requireNotNull(formatter.mapUrl(item))
        assertEquals(
            item.address.full,
            URLDecoder.decode(url.substringAfter("&query="), Charsets.UTF_8),
        )
        val address = Address(null, "Straße & Platz", "12", "10115", "Berlin", "Mitte")
        val partialUrl = requireNotNull(formatter.mapUrl(item.copy(address = address)))
        assertEquals(
            "Straße & Platz 12, 10115, Mitte, Berlin",
            URLDecoder.decode(partialUrl.substringAfter("&query="), Charsets.UTF_8),
        )
        assertNull(formatter.mapUrl(item.copy(address = Address(null, " ", null, null, "", null))))
    }

    @Test
    fun `renders Degewo provider and source`() {
        val item = event().copy(source = "degewo", provider = "Degewo")
        assertTrue(formatter.format(item).contains("🏠 Degewo"))
        assertTrue(formatter.format(item.copy(provider = null)).contains("🏠 Degewo"))
    }

    @Test
    fun `renders WBM provider and source`() {
        val item =
            event()
                .copy(
                    source = "wbm",
                    provider = "WBM",
                    url = "https://www.wbm.de/wohnungen-berlin/angebote/details/example/",
                )
        val text = formatter.format(item)
        assertTrue(text.contains("🏠 WBM"))
        assertFalse(text.contains("Источник: WBM"))
        assertTrue(text.endsWith(item.url))
        assertTrue(formatter.format(item.copy(provider = null)).contains("🏠 WBM"))
    }

    @Test
    fun `renders expected message`() {
        val expected = javaClass.getResource("/message.txt")!!.readText()
        assertEquals(expected, formatter.format(event()))
    }

    @Test
    fun `shows source only when it differs from provider`() {
        val same = formatter.format(event())
        assertFalse(same.contains("Компания: Gewobag"))
        assertFalse(same.contains("Источник: Gewobag"))

        val different = formatter.format(event().copy(provider = "Deutsche Wohnen"))
        assertTrue(different.contains("Компания: Deutsche Wohnen"))
        assertTrue(different.startsWith("🏠 Gewobag"))
    }

    @Test
    fun `renders unknown values safely`() {
        val base = event()
        val item = base.copy(rooms = null, areaM2 = null, rent = base.rent.copy(cold = null))

        val text = formatter.format(item)

        assertTrue(text.contains("Комнат: не указано"))
        assertTrue(text.contains("Площадь: не указано"))
        assertTrue(text.contains("Kaltmiete: не указано"))
        assertTrue(text.contains("Warmmiete: 890,00"))
        assertFalse(text.contains("Лифт:"))
    }

    @Test
    fun `limits UTF16 without splitting a surrogate and retains URL`() {
        val base = event()
        val item = base.copy(address = base.address.copy(full = "🏠".repeat(5000)))
        val text = formatter.format(item)

        assertTrue(text.length <= 4096)
        assertTrue(text.endsWith(item.url))
        assertFalse(text.contains('\uFFFD'))
        val prefix = text.substringBefore('…')
        assertFalse(prefix.last().isHighSurrogate())
        assertThrows(DeliveryException::class.java) {
            formatter.format(item.copy(url = "x".repeat(4096)))
        }
    }
}
