package com.aksumar.telegram.support

import com.aksumar.telegram.contract.Listing
import com.aksumar.telegram.contract.ListingContract

fun fixture(): String =
    object {}.javaClass.getResource("/listing-v2.json")!!.readText()

fun event(): Listing =
    ListingContract().decode(fixture())
