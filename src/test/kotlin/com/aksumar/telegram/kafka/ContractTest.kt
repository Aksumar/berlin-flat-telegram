package com.aksumar.telegram.kafka

import com.aksumar.telegram.exception.DeliveryException
import com.aksumar.telegram.support.fixture
import com.aksumar.telegram.support.testMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractTest {
    private val contract = ListingContract(testMapper)

    @Test
    fun `accepts Degewo Python event`() {
        val payload = javaClass.getResource("/degewo-v2.json")!!.readText()
        val item = contract.decode(payload)
        assertEquals("degewo", item.source)
        assertEquals("W1300.42303.0131-0504", item.id)
    }

    @Test
    fun `accepts enriched Degewo address from Python without changing v2 contract`() {
        val item = contract.decode(javaClass.getResource("/degewo-charlottenburg-v2.json")!!.readText())
        assertEquals("W1150.01393.0030-0701", item.id)
        assertEquals("10587", item.address.postalCode)
        assertEquals("Helmholtzstraße", item.address.street)
        assertEquals("34", item.address.houseNumber)
        assertEquals("Helmholtzstraße 34, 10587 Berlin, Charlottenburg", item.address.searchQuery())
    }

    @Test
    fun `accepts WBM listing source`() {
        val tree = testMapper.readTree(fixture()) as ObjectNode
        tree.put("source", "wbm")
        tree.put("provider", "WBM")
        tree.put("id", "50-867500/10/144")
        assertEquals("wbm", contract.decode(tree.toString()).source)
    }

    @Test
    fun `accepts Deutsche Wohnen and HOWOGE listings`() {
        for ((source, displayName) in listOf(
            "deutschewohnen" to "Deutsche Wohnen",
            "howoge" to "HOWOGE",
        )) {
            val tree = testMapper.readTree(fixture()) as ObjectNode
            tree.put("source", source)
            tree.put("provider", displayName)

            val item = contract.decode(tree.toString())
            val message = com.aksumar.telegram.format.MessageFormatter().format(item)

            assertEquals(source, item.source)
            assertTrue(message.endsWith("Компания: $displayName"))
        }
    }

    @Test
    fun `rejects unsupported versions and malformed known fields`() {
        val invalid =
            listOf(
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
                fixture().replace("\"source\": \"gewobag\"", "\"source\": \"unknown\""),
            )

        invalid.forEach { assertThrows(DeliveryException::class.java) { contract.decode(it) } }

        val missing = testMapper.readTree(fixture()) as ObjectNode
        missing.remove("rooms")
        assertThrows(DeliveryException::class.java) { contract.decode(missing.toString()) }
    }

    @Test
    fun `unknown values and additive fields are safe`() {
        val tree = testMapper.readTree(fixture()) as ObjectNode
        tree.putNull("rooms")
        tree.putNull("area_m2")
        tree.put("details", "DO NOT SHOW")
        (tree["rent"] as ObjectNode).putNull("cold")

        val item = contract.decode(tree.toString())

        assertNull(item.rooms)
        assertNull(item.areaM2)
        assertNull(item.rent.cold)
    }
}
