package com.aksumar.telegram.maps

import com.aksumar.telegram.model.Listing

data class ListingMap(val png: ByteArray, val approximate: Boolean)

fun interface ListingMaps {
    fun create(item: Listing): ListingMap?
}
