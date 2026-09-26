package com.aksumar.telegram.contract

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.time.LocalDate
import com.aksumar.telegram.contract.exceptions.DeliveryException

fun jsonMapper(): ObjectMapper = jacksonObjectMapper()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

@Component
class ListingContract {
    private val mapper = jsonMapper()

    fun decode(payload: String?): Listing = try {
        val node = mapper.readTree(payload ?: error("Missing event"))

        require(node.isObject)
        require(node["version"]?.isIntegralNumber == true)
        require(node["version"].asInt() == 2)

        requireText(node, "source", "id", "title", "url")
        require(node["source"].asText() in SUPPORTED_SOURCES)

        requireNullableText(node, "floor", "provider")
        requireNullableNumbers(node, "area_m2", "rooms")
        requireNullableText(
            node.requiredObject("address"),
            "full",
            "street",
            "house_number",
            "postal_code",
            "city",
            "district"
        )

        val rent = node.requiredObject("rent")
        requireText(rent, "currency")
        require(rent["currency"].asText() == "EUR")
        requireNullableNumbers(
            rent,
            "cold",
            "warm",
            "operating_costs",
            "heating_costs",
            "deposit"
        )
        val availability = node.requiredObject("availability")
        requireNullableText(availability, "date", "text")

        val wbs = node.requiredObject("wbs")
        requireNullableBooleans(wbs, "required")
        requireNullableText(wbs, "text")

        val features = node.requiredObject("features")
        requireNullableBooleans(features, "balcony", "elevator", "built_in_kitchen")

        availability["date"]
            .takeUnless { it.isNull }
            ?.asText()
            ?.let { require(LocalDate.parse(it).toString() == it) }

        mapper.treeToValue(node, Listing::class.java)
    } catch (error: Exception) {
        throw DeliveryException("Invalid Kafka v2 event", error)
    }

    private fun JsonNode.requiredObject(name: String): JsonNode =
        required(name).also { require(it.isObject) }

    private fun requireText(node: JsonNode, vararg names: String) {
        require(node.isObject)
        names.forEach { name ->
            require(node.required(name).isTextual)
        }
    }

    private fun requireNullableText(node: JsonNode, vararg names: String) {
        requireNullable(node, names) { it.isTextual }
    }

    private fun requireNullableBooleans(node: JsonNode, vararg names: String) {
        requireNullable(node, names) { it.isBoolean }
    }

    private fun requireNullableNumbers(node: JsonNode, vararg names: String) {
        requireNullable(node, names) { value ->
            value.isNumber &&
                value.doubleValue().isFinite() &&
                value.decimalValue().signum() >= 0
        }
    }

    private fun requireNullable(
        node: JsonNode,
        names: Array<out String>,
        predicate: (JsonNode) -> Boolean
    ) {
        require(node.isObject)
        names.forEach { name ->
            val value = node.required(name)
            require(value.isNull || predicate(value))
        }
    }

    companion object {
        private val SUPPORTED_SOURCES = setOf(
            "allod",
            "rbb",
            "berlinhaus",
            "berlinovo",
            "gewobag",
            "wbm",
            "degewo",
            "inberlinwohnen"
        )
    }
}
