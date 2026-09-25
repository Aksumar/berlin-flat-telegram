package com.aksumar.telegram.contract

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListingKeyTest {
    @Test
    fun `listing key is valid JSON pair`() {
        assertTrue(matchesListingKey(listingKey("gewobag", "123"), "gewobag", "123"))
        assertTrue(matchesListingKey("[\"rbb\",\"ä🏠\\n\\\"\\\\\"]", "rbb", "ä🏠\n\"\\"))
    }

    @Test
    fun `listing key rejects different or malformed identities`() {
        assertFalse(matchesListingKey("[\"gewobag\",\"456\"]", "gewobag", "123"))
        assertFalse(matchesListingKey("[\"gewobag\"]", "gewobag", "123"))
        assertFalse(matchesListingKey("wrong", "gewobag", "123"))
        assertFalse(matchesListingKey(null, "gewobag", "123"))
    }
}
