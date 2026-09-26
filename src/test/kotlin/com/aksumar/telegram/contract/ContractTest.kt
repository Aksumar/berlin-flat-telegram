package com.aksumar.telegram.contract

import com.aksumar.telegram.support.fixture
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class ContractTest {
    private val contract = ListingContract()

    @Test
    fun `accepts WBM listing source`() {
        val tree = jsonMapper().readTree(fixture()) as ObjectNode
        tree.put("source", "wbm")
        tree.put("provider", "WBM")
        tree.put("id", "50-867500/10/144")
        assertEquals("wbm", contract.decode(tree.toString()).source)
    }

    @Test
    fun `rejects unsupported versions and malformed known fields`() {
        val invalid = listOf(
            "null",
            "[]",
            "{}",
            fixture().replace("\"version\": 2", "\"version\": true"),
            fixture().replace("\"version\": 2", "\"version\": 3"),
            fixture().replace("\"rooms\": 2", "\"rooms\": \"2\""),
            fixture().replace("\"rooms\": 2", "\"rooms\": -1"),
            fixture().replace("\"rooms\": 2", "\"rooms\": true"),
            fixture().replace("2026-11-01", "2026-99-01"),
            fixture().replace("\"currency\": \"EUR\"", "\"currency\": \"USD\""),
            fixture().replace("\"source\": \"gewobag\"", "\"source\": \"unknown\"")
        )

        invalid.forEach {
            assertThrows(DeliveryException::class.java) { contract.decode(it) }
        }

        val missing = jsonMapper().readTree(fixture()) as ObjectNode
        missing.remove("rooms")
        assertThrows(DeliveryException::class.java) { contract.decode(missing.toString()) }
    }

    @Test
    fun `unknown values and additive fields are safe`() {
        val tree = jsonMapper().readTree(fixture()) as ObjectNode
        tree.putNull("rooms")
        tree.putNull("area_m2")
        tree.put("details", "DO NOT SHOW")
        (tree["rent"] as ObjectNode).putNull("cold").put("warm_from", true)

        val item = contract.decode(tree.toString())

        assertNull(item.rooms)
        assertNull(item.areaM2)
        assertNull(item.rent.cold)
        assertEquals(true, item.rent.warmFrom)
    }
}
