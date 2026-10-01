package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Listing

data class ListingMap(val png: ByteArray, val approximate: Boolean)

fun interface ListingMaps {
    /** Throws [MapGenerationException] when generation fails with a known, user-facing reason. */
    fun create(item: Listing): ListingMap?

    /** Publishes the resolved district before rendering, so map failures do not discard it. */
    fun create(item: Listing, onDistrictResolved: (String) -> Unit): ListingMap? = create(item)
}
