package com.aksumar.telegram.format

import com.aksumar.telegram.model.Address
import com.aksumar.telegram.model.Availability
import com.aksumar.telegram.model.Features
import com.aksumar.telegram.model.Wbs
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
            "Musterstraße 12, 10115 Berlin, Mitte",
            URLDecoder.decode(url.substringAfter("&query="), Charsets.UTF_8),
        )
        val address = Address(null, "Straße & Platz", "12", "10115", "Berlin", "Mitte")
        val partialUrl = requireNotNull(formatter.mapUrl(item.copy(address = address)))
        assertEquals(
            "Straße & Platz 12, 10115 Berlin, Mitte",
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
        assertFalse(text.contains(item.url))
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
        assertTrue(text.contains("Лифт: не указано"))
    }

    @Test
    fun `always renders WBS with conditions only when required`() {
        val cases = listOf(
            Wbs(true, "WBS 100 oder 140") to "WBS: требуется (WBS 100 oder 140)",
            Wbs(true, null) to "WBS: требуется",
            Wbs(true, "  ") to "WBS: требуется",
            Wbs(false, "ohne WBS") to "WBS: не требуется",
            Wbs(null, "unknown restriction") to "WBS: не указано",
            Wbs(null, null) to "WBS: не указано",
        )
        for ((wbs, expected) in cases) {
            assertEquals(expected, formatter.format(event().copy(wbs = wbs)).lines().single { it.startsWith("WBS:") })
        }
    }

    @Test
    fun `keeps the same field order for missing values and every source`() {
        val base = event()
        val empty = base.copy(
            floor = " ", rooms = null, areaM2 = null,
            rent = base.rent.copy(warm = null, cold = null, operatingCosts = null, heatingCosts = null, deposit = null),
            availability = Availability(null, " "), wbs = Wbs(null, null),
            features = Features(null, null, null),
        )
        val expectedLabels = formatter.format(base).lines().filter { ':' in it }.map { it.substringBefore(':') }
        for (source in listOf("allod", "rbb", "berlinhaus", "berlinovo", "gewobag", "wbm", "degewo", "inberlinwohnen", "deutschewohnen", "howoge")) {
            val lines = formatter.format(empty.copy(source = source, provider = null)).lines().filter { ':' in it }
            assertEquals(expectedLabels, lines.map { it.substringBefore(':') })
            assertTrue(lines.filterNot { it.startsWith("Адрес:") }.all { it.endsWith("не указано") })
        }
    }

    @Test
    fun `formats normalized availability floors and features`() {
        val base = event()
        for ((availability, expected) in listOf(
            Availability("2026-10-31", "ab 31.10.2026") to "31.10.2026",
            Availability(null, "sofort") to "сразу",
            Availability(null, "nach Vereinbarung") to "nach Vereinbarung",
        )) {
            assertTrue(formatter.format(base.copy(availability = availability)).contains("Доступна: $expected\n"))
        }
        for ((floor, expected) in listOf("0" to "0", "1" to "1", "DG" to "мансарда", "UG" to "подвальный")) {
            assertTrue(formatter.format(base.copy(floor = floor)).contains("Этаж: $expected\n"))
        }
        val text = formatter.format(base.copy(features = Features(true, false, null)))
        assertTrue(text.contains("Балкон: есть\nЛифт: нет\nВстроенная кухня: не указано"))
    }

    @Test
    fun `limits UTF16 without splitting a surrogate`() {
        val base = event()
        val item = base.copy(address = base.address.copy(full = "🏠".repeat(5000)))
        val text = formatter.format(item)

        assertTrue(text.length <= 4096)
        assertTrue(text.endsWith('…'))
        assertFalse(text.contains(item.url))
        assertFalse(text.contains('\uFFFD'))
        val prefix = text.substringBefore('…')
        assertFalse(prefix.last().isHighSurrogate())
        assertEquals(text, formatter.format(item.copy(url = "x".repeat(4096))))
    }
}
