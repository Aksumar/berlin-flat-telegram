package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Address
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.math.abs

internal data class GeocodedLocation(
    val longitude: Double,
    val latitude: Double,
    val approximate: Boolean,
    val detailZoom: Double,
    val district: String? = null,
)

internal class GeoapifyGeocoder(
    private val client: GeoapifyClient,
    private val mapper: ObjectMapper,
    private val endpoint: String,
) {
    fun locate(address: Address, deadlineNanos: Long): GeocodedLocation {
        val query = address.searchQuery()
        if (query.isBlank()) throw MapGenerationException("в объявлении не указан адрес")
        val expected = address.withFullAddressParts()
        // Berlin contains duplicate street names. A city-wide match is not enough.
        if (expected.postalCode.isNullOrBlank() && expected.district.isNullOrBlank()) {
            throw MapGenerationException("в адресе не указан почтовый индекс или район")
        }
        val response = client.get(
            endpoint,
            mapOf(
                "text" to query,
                "filter" to "rect:13.08,52.33,13.77,52.68",
                "limit" to "5",
                "format" to "json",
                "lang" to "de",
            ),
            "geocoding",
            deadlineNanos,
        )
        val candidates = mapper.readTree(response).path("results")
            .filter { matches(it, expected) }
        val result = candidates.firstOrNull()
            ?: throw MapGenerationException("не удалось достоверно определить координаты адреса в Берлине")
        // Do not silently choose between different locations for an incomplete address.
        if (candidates.any {
                abs(it.path("lon").asDouble() - result.path("lon").asDouble()) > 0.001 ||
                    abs(it.path("lat").asDouble() - result.path("lat").asDouble()) > 0.001
            }) throw MapGenerationException("адрес соответствует нескольким разным местам")
        val longitude = result.path("lon").asDouble(Double.NaN)
        val latitude = result.path("lat").asDouble(Double.NaN)
        val rank = result.path("rank")
        val resultType = result.path("result_type").asText()
        val exact = resultType == "building" && !expected.houseNumber.isNullOrBlank() &&
            rank.path("confidence_building_level").asDouble(0.0) >= MIN_BUILDING_CONFIDENCE &&
            rank.path("match_type").asText() == "full_match"
        val detailZoom = if (resultType == "building" || resultType == "street") 15.5 else 12.5
        val district = expected.district?.trim()?.takeIf { it.isNotBlank() }
            ?: listOf("district", "suburb", "quarter", "neighbourhood")
                .firstNotNullOfOrNull { result.path(it).asText("").trim().takeIf(String::isNotBlank) }
        return GeocodedLocation(longitude, latitude, approximate = !exact, detailZoom = detailZoom, district = district)
    }

    private fun matches(result: JsonNode, address: Address): Boolean {
        val longitude = result.path("lon").asDouble(Double.NaN)
        val latitude = result.path("lat").asDouble(Double.NaN)
        val resultType = result.path("result_type").asText()
        if (
            longitude !in 13.08..13.77 || latitude !in 52.33..52.68 ||
                result.path("rank").path("confidence").asDouble(0.0) < MIN_LOCATION_CONFIDENCE ||
                resultType !in SUPPORTED_RESULT_TYPES
        ) return false
        if (!address.postalCode.isNullOrBlank() &&
            result.path("postcode").asText() != address.postalCode.trim()) return false
        if (!address.city.isNullOrBlank() &&
            normalized(result.path("city").asText()) != normalized(address.city)) return false

        // Providers use district for both boroughs and smaller neighbourhoods.
        // With a postcode, do not reject a valid address due to different area names.
        if (address.postalCode.isNullOrBlank() &&
            listOf("district", "suburb", "quarter", "neighbourhood", "state").none {
                normalized(result.path(it).asText()) == normalized(address.district.orEmpty())
            }) return false
        if (resultType == "street" || resultType == "building") {
            if (address.street.isNullOrBlank() ||
                normalizedStreet(result.path("street").asText()) != normalizedStreet(address.street)) return false
        }
        if (resultType == "building" && (address.houseNumber.isNullOrBlank() ||
                normalizedHouse(result.path("housenumber").asText()) != normalizedHouse(address.houseNumber))) return false
        return true
    }

    /** Old v2 events may contain only full; do not lose their postcode or street. */
    private fun Address.withFullAddressParts(): Address {
        val text = full.orEmpty().trim()
        val postal = Regex("\\b\\d{5}\\b").find(text)
        val streetLine = (postal?.let { text.substring(0, it.range.first) } ?: text)
            .trim(' ', ',').removeSuffix(" in")
        val house = Regex("^(.*?)\\s+(\\d+\\s*[a-zA-Z]?(?:\\s*[-/]\\s*\\d+\\s*[a-zA-Z]?)?)$")
            .matchEntire(streetLine)
        val fullCity = postal?.let { text.substring(it.range.last + 1).trimStart(' ', ',').substringBefore(',') }
            ?.removeSuffix(" Deutschland")?.trim()?.takeIf { it.isNotBlank() }
        val cityText = city?.takeIf { it.isNotBlank() } ?: fullCity
        val berlin = cityText?.let { Regex("Berlin\\s*[/\\-]\\s*(.+)").matchEntire(it) }
        return copy(
            postalCode = postalCode?.takeIf { it.isNotBlank() } ?: postal?.value,
            city = if (berlin != null) "Berlin" else cityText,
            district = district?.takeIf { it.isNotBlank() } ?: berlin?.groupValues?.get(1),
            street = street?.takeIf { it.isNotBlank() } ?: house?.groupValues?.get(1),
            houseNumber = houseNumber?.takeIf { it.isNotBlank() } ?: house?.groupValues?.get(2),
        )
    }

    private fun normalized(value: String): String = value.lowercase().replace("ß", "ss")
        .replace(Regex("[\\s.\\-]"), "")

    private fun normalizedStreet(value: String): String = normalized(value.replace(Regex("(?i)str\\.(?=\\s|$)"), "straße"))

    private fun normalizedHouse(value: String): String = value.lowercase().replace(Regex("\\s+"), "")

    private companion object {
        const val MIN_LOCATION_CONFIDENCE = 0.8
        const val MIN_BUILDING_CONFIDENCE = 0.95
        val SUPPORTED_RESULT_TYPES = setOf("building", "street", "suburb", "district", "postcode")
    }
}
