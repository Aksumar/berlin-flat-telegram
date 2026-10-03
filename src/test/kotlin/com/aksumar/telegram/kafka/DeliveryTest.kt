package com.aksumar.telegram.kafka

import com.aksumar.telegram.delivery.PrepareListing
import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.maps.ListingMaps
import com.aksumar.telegram.maps.MapGenerationException
import com.aksumar.telegram.support.fixture
import com.aksumar.telegram.support.testMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class DeliveryTest {
    private fun prepare(maps: ListingMaps = ListingMaps { null }) = PrepareListing(MessageFormatter(), maps)
    private fun item(source: String = "gewobag", district: String? = "Mitte") =
        ListingContract(testMapper).decode(fixture().replace("\"source\": \"gewobag\"", "\"source\": \"$source\"")
            .replace("\"district\": \"Mitte\"", district?.let { "\"district\": \"$it\"" } ?: "\"district\": null"))

    @Test
    fun `unresolved district is omitted while resolved district appears even if map fails`(output: CapturedOutput) {
        val unresolved = prepare().prepare(item(district = " "))
        assertEquals("Musterstraße 12, 10115 Berlin", unresolved.text.lineSequence().first())
        assertTrue(output.out.contains("Не удалось определить район; source=gewobag, id=123"))
        val maps = object : ListingMaps {
            override fun create(item: com.aksumar.telegram.model.Listing) = null
            override fun create(item: com.aksumar.telegram.model.Listing, onDistrictResolved: (String) -> Unit): com.aksumar.telegram.maps.ListingMap? {
                onDistrictResolved("Wedding")
                return null
            }
        }
        val resolved = prepare(maps).prepare(item(district = " "))
        assertEquals("Musterstraße 12, 10115 Berlin · Wedding", resolved.text.lineSequence().first())
        assertTrue(resolved.text.contains("Карта не сгенерирована"))
    }

    @Test
    fun `supplied district is preserved and map is prepared once`() {
        var calls = 0
        val content = prepare(ListingMaps { calls++; error("Geoapify secret should be redacted") })
            .prepare(item(district = "Kreuzberg"))
        assertEquals(1, calls)
        assertTrue(content.text.startsWith("Musterstraße 12, 10115 Berlin · Kreuzberg"))
        assertTrue(content.text.contains("произошла непредвиденная ошибка"))
        assertFalse(content.text.contains("secret"))
    }

    @Test
    fun `known map failure is shown and logged without leaking provider details`(output: CapturedOutput) {
        val content = prepare(ListingMaps { throw MapGenerationException("HTTP 429", "apiKey=secret") })
            .prepare(item())
        assertTrue(content.text.contains("HTTP 429"))
        assertFalse(content.text.contains("secret"))
        assertTrue(output.all.contains("apiKey=secret"))
    }
}
