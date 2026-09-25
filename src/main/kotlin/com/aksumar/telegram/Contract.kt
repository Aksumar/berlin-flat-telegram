package com.aksumar.telegram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

fun jsonMapper(): ObjectMapper = jacksonObjectMapper()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

data class Address(val full: String?, val street: String?, val houseNumber: String?, val postalCode: String?, val city: String?, val district: String?)
data class Rent(val currency: String, val cold: BigDecimal?, val warm: BigDecimal?, val operatingCosts: BigDecimal?, val heatingCosts: BigDecimal?, val deposit: BigDecimal?, val warmFrom: Boolean?)
data class Availability(val date: String?, val text: String?)
data class Wbs(val required: Boolean?, val text: String?)
data class Features(val balcony: Boolean?, val elevator: Boolean?, val builtInKitchen: Boolean?)
data class Listing(val version: Int, val source: String, val id: String, val title: String, val url: String,
    val address: Address, val areaM2: BigDecimal?, val rooms: BigDecimal?, val floor: String?,
    val rent: Rent, val availability: Availability, val wbs: Wbs, val features: Features, val provider: String?)

class DeliveryException(message: String) : RuntimeException(message)

@Component
class Contract {
    private val mapper = jsonMapper()
    fun decode(payload: String?): Listing = try {
        val node = mapper.readTree(payload ?: error("Missing event"))
        require(node.isObject && node["version"]?.isIntegralNumber == true && node["version"].asText() == "2")
        listOf("source", "id", "title", "url").forEach { require(node[it]?.isTextual == true) }
        require(node["source"].asText() in setOf("allod", "rbb", "berlinhaus", "berlinovo", "gewobag", "inberlinwohnen"))
        fields(node, "string", "floor", "provider")
        fields(node, "number", "area_m2", "rooms")
        fields(node.required("address"), "string", "full", "street", "house_number", "postal_code", "city", "district")
        val rent = node.required("rent")
        require(rent["currency"]?.isTextual == true && rent["currency"].asText() == "EUR")
        fields(rent, "number", "cold", "warm", "operating_costs", "heating_costs", "deposit")
        fields(rent, "boolean", "warm_from")
        fields(node.required("availability"), "string", "date", "text")
        fields(node.required("wbs"), "boolean", "required")
        fields(node.required("wbs"), "string", "text")
        fields(node.required("features"), "boolean", "balcony", "elevator", "built_in_kitchen")
        node["availability"]["date"].takeUnless { it.isNull }?.asText()?.let { require(LocalDate.parse(it).toString() == it) }
        mapper.treeToValue(node, Listing::class.java)
    } catch (_: Exception) {
        throw DeliveryException("Invalid Kafka v2 event")
    }

    private fun fields(node: JsonNode, kind: String, vararg names: String) {
        require(node.isObject)
        names.forEach { name ->
            val value = node.required(name)
            require(value.isNull || when (kind) {
                "string" -> value.isTextual
                "boolean" -> value.isBoolean
                else -> value.isNumber && value.doubleValue().isFinite() && value.decimalValue().signum() >= 0
            })
        }
    }
}

// Python json.dumps([source,id], separators=(",", ":")) uses ASCII escapes.
// Preserve its exact keys, including lower-case Unicode escapes, in sent.json.
fun listingKey(source: String, id: String): String {
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            append(when (c) {
                '"' -> "\\\""; '\\' -> "\\\\"; '\b' -> "\\b"; '\u000c' -> "\\f"
                '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
                else -> if (c.code < 32 || c.code > 126) "\\u%04x".format(c.code) else c.toString()
            })
        }
        append('"')
    }
    return "[${quote(source)},${quote(id)}]"
}
