package com.aksumar.telegram.kafka

import com.aksumar.telegram.support.testMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListingKeyTest {
    @Test
    fun `listing key is valid JSON pair`() {
        assertTrue(
            testMapper.matchesListingKey(testMapper.listingKey("gewobag", "123"), "gewobag", "123")
        )
        assertTrue(testMapper.matchesListingKey("[\"rbb\",\"ä🏠\\n\\\"\\\\\"]", "rbb", "ä🏠\n\"\\"))
    }

    @Test
    fun `listing key rejects different or malformed identities`() {
        assertFalse(testMapper.matchesListingKey("[\"gewobag\",\"456\"]", "gewobag", "123"))
        assertFalse(testMapper.matchesListingKey("[\"gewobag\"]", "gewobag", "123"))
        assertFalse(testMapper.matchesListingKey("wrong", "gewobag", "123"))
        assertFalse(testMapper.matchesListingKey(null, "gewobag", "123"))
    }
}
