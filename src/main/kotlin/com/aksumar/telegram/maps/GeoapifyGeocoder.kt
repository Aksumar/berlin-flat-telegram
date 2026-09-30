package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Address
import com.fasterxml.jackson.databind.ObjectMapper

internal data class GeocodedLocation(
    val longitude: Double,
    val latitude: Double,
    val approximate: Boolean,
    val detailZoom: Double,
) {
    val mapUrl: String
        get() =
            if (!approximate) "https://www.google.com/maps/search/?api=1&query=$latitude%2C$longitude"
            else {
                val viewZoom = detailZoom.toInt()
                "https://www.google.com/maps/@?api=1&map_action=map&center=$latitude%2C$longitude&zoom=$viewZoom"
            }
}

internal class GeoapifyGeocoder(
    private val client: GeoapifyClient,
    private val mapper: ObjectMapper,
    private val endpoint: String,
) {
    fun locate(address: Address, deadlineNanos: Long): GeocodedLocation? {
        val query = address.searchQuery()
        if (query.isBlank()) return null
        val response = client.get(
            endpoint,
            mapOf(
                "text" to query,
                "filter" to "rect:13.08,52.33,13.77,52.68",
                "limit" to "1",
                "format" to "json",
                "lang" to "de",
            ),
            "geocoding",
            deadlineNanos,
        )
        val result = mapper.readTree(response).path("results").firstOrNull() ?: return null
        val longitude = result.path("lon").asDouble(Double.NaN)
        val latitude = result.path("lat").asDouble(Double.NaN)
        val rank = result.path("rank")
        val resultType = result.path("result_type").asText()
        if (
            longitude !in 13.08..13.77 || latitude !in 52.33..52.68 ||
                rank.path("confidence").asDouble(0.0) < MIN_LOCATION_CONFIDENCE ||
                resultType !in SUPPORTED_RESULT_TYPES
        ) return null

        val exact = resultType == "building" &&
            rank.path("confidence_building_level").asDouble(0.0) >= MIN_BUILDING_CONFIDENCE &&
            rank.path("match_type").asText() == "full_match"
        val detailZoom = if (resultType == "building" || resultType == "street") 15.5 else 12.5
        return GeocodedLocation(longitude, latitude, approximate = !exact, detailZoom = detailZoom)
    }

    private companion object {
        const val MIN_LOCATION_CONFIDENCE = 0.8
        const val MIN_BUILDING_CONFIDENCE = 0.95
        val SUPPORTED_RESULT_TYPES = setOf("building", "street", "suburb", "district", "postcode")
    }
}
