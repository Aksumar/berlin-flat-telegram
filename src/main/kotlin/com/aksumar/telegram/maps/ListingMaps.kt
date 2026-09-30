package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Listing
import com.aksumar.telegram.model.Address
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ListingMap(val png: ByteArray, val url: String, val approximate: Boolean)

fun interface ListingMaps {
    fun create(item: Listing): ListingMap?
}

internal fun Address.searchQuery(): String =
    full?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(
            listOfNotNull(street, houseNumber).filter { it.isNotBlank() }
                .joinToString(" ").ifBlank { null },
            postalCode,
            district,
            city,
        ).filter { it.isNotBlank() }.joinToString(", ")

internal fun Address.mapSearchUrl(): String? =
    searchQuery().takeIf { it.isNotBlank() }?.let {
        "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
    }
