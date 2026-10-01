package com.aksumar.telegram.model

import com.aksumar.telegram.format.MessageFormatter
import com.aksumar.telegram.support.event
import java.net.URLDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddressTest {
    @Test
    fun `short full address does not hide postcode or district from maps`() {
        val address = Address("Helmholtzstraße 34", null, null, "10587", "Berlin", "Charlottenburg")
        val expected = "Helmholtzstraße 34, 10587 Berlin, Charlottenburg"
        assertEquals(expected, address.searchQuery())
        val url = requireNotNull(MessageFormatter().mapUrl(event().copy(address = address)))
        assertEquals(expected, URLDecoder.decode(url.substringAfter("&query="), Charsets.UTF_8))
    }

    @Test
    fun `structured address takes precedence and full fallback keeps unparsed house number`() {
        assertEquals("Kirchsteig 111A, 12524 Berlin, Altglienicke",
            Address("Kirchsteig 111 A", "Kirchsteig", "111A", "12524", "Berlin", "Altglienicke").searchQuery())
        assertEquals("Kirchsteig 111 A, 12524 Berlin",
            Address("Kirchsteig 111 A, 12524 Berlin", "Kirchsteig", null, "12524", "Berlin", null).searchQuery())
    }

    @Test
    fun `full address supplements are not duplicated and city is never guessed`() {
        assertEquals("Helmholtzstraße 34, 10587 Berlin, Charlottenburg",
            Address("Helmholtzstraße 34, 10587 Berlin, Charlottenburg", null, null, "10587", "Berlin", "Charlottenburg").searchQuery())
        assertEquals("Eichenring 48, 16341 Panketal, Brandenburg",
            Address(null, "Eichenring", "48", "16341", "Panketal", "Brandenburg").searchQuery())
        assertEquals("10785 Berlin", Address(null, null, null, "10785", "Berlin", null).searchQuery())
        assertEquals("", Address(null, null, null, null, null, null).searchQuery())
    }
}
