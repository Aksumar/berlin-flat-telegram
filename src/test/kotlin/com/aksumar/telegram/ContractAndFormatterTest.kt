package com.aksumar.telegram

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

fun fixture(): String = object {}.javaClass.getResource("/listing-v2.json")!!.readText()
fun event(): Listing = Contract().decode(fixture())

class ContractAndFormatterTest {
    private val contract = Contract()
    private val formatter = MessageFormatter()

    @Test fun `renders the source first notification template`() {
        val expected = javaClass.getResource("/message.txt")!!.readText().trimEnd('\n')
        assertEquals(expected, formatter.format(event()))
    }
    @Test fun `source site is distinct from housing company`() {
        val text = formatter.format(event().copy(source = "inberlinwohnen", provider = "Gewobag"))
        assertTrue(text.startsWith("InBerlinWohnen · Mitte\n"))
        assertTrue(text.contains("Компания: Gewobag"))
        assertFalse(text.contains("Новая квартира"))
        assertFalse(text.contains("Источник:"))
    }
    @Test fun `accepts and renders WBM source`() {
        val tree = jsonMapper().readTree(fixture()) as ObjectNode
        tree.put("source", "wbm"); tree.put("provider", "WBM")
        tree.put("id", "50-867500/10/144")
        tree.put("url", "https://www.wbm.de/wohnungen-berlin/angebote/details/example/")
        val item = contract.decode(tree.toString())
        assertEquals("wbm", item.source)
        assertTrue(formatter.format(item).contains("WBM"))
        assertTrue(formatter.format(item).endsWith(item.url))
    }
    @Test fun `accepts and renders Degewo source`() {
        val tree = jsonMapper().readTree(fixture()) as ObjectNode
        tree.put("source", "degewo"); tree.put("provider", "Degewo")
        val item = contract.decode(tree.toString())
        assertEquals("degewo", item.source)
        assertTrue(formatter.format(item).contains("Degewo"))
        assertTrue(formatter.format(item).endsWith(item.url))
    }
    @Test fun `rejects old versions and malformed known fields`() {
        val invalid = listOf("null", "[]", "{}", "{\"version\":1}", fixture().replace("\"version\": 2", "\"version\": true"),
            fixture().replace("\"version\": 2", "\"version\": 1"), fixture().replace("\"rooms\": 2", "\"rooms\": \"2\""),
            fixture().replace("\"rooms\": 2", "\"rooms\": -1"), fixture().replace("\"rooms\": 2", "\"rooms\": true"),
            fixture().replace("2026-11-01", "2026-99-01"), fixture().replace("\"currency\": \"EUR\"", "\"currency\": \"USD\""),
            fixture().replace("\"source\": \"gewobag\"", "\"source\": \"unknown\""))
        invalid.forEach { assertThrows(DeliveryException::class.java) { contract.decode(it) } }
        val missing = jsonMapper().readTree(fixture()) as ObjectNode
        missing.remove("rooms")
        assertThrows(DeliveryException::class.java) { contract.decode(missing.toString()) }
    }
    @Test fun `unknown values and additive fields are safe`() {
        val tree = jsonMapper().readTree(fixture()) as ObjectNode
        tree.putNull("rooms"); tree.putNull("area_m2"); tree.put("details", "DO NOT SHOW")
        (tree["rent"] as ObjectNode).putNull("cold").put("warm_from", true)
        val text = formatter.format(contract.decode(tree.toString()))
        assertTrue(text.contains("Комнат: не указано"))
        assertTrue(text.contains("Площадь: не указано"))
        assertTrue(text.contains("Kaltmiete: не указано"))
        assertTrue(text.contains("Warmmiete: от 890,00"))
        assertFalse(text.contains("DO NOT SHOW"))
        assertFalse(text.contains("Лифт:"))
    }
    @Test fun `limits UTF16 without splitting a surrogate and retains URL`() {
        val item = event().copy(address = event().address.copy(full = "🏠".repeat(5000)))
        val text = formatter.format(item)
        assertTrue(text.length <= 4096)
        assertTrue(text.endsWith(item.url))
        assertFalse(text.contains('\uFFFD'))
        val prefix = text.substringBefore('…')
        assertFalse(prefix.last().isHighSurrogate())
        assertThrows(DeliveryException::class.java) { formatter.format(item.copy(url = "x".repeat(4096))) }
    }
    @Test fun `Python identity escaping stays compatible`() {
        assertEquals("[\"gewobag\",\"123\"]", listingKey("gewobag", "123"))
        assertEquals("[\"rbb\",\"\\u00e4\\ud83c\\udfe0\\n\\\"\\\\\"]", listingKey("rbb", "ä🏠\n\"\\"))
    }
}
