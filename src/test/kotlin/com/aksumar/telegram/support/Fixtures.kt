package com.aksumar.telegram.support

import com.aksumar.telegram.model.Listing
import com.aksumar.telegram.config.JacksonConfig
import com.aksumar.telegram.kafka.ListingContract

val testMapper = JacksonConfig().objectMapper()

fun fixture(): String =
    object {}.javaClass.getResource("/listing-v2.json")!!.readText()

fun event(): Listing =
    ListingContract(testMapper).decode(fixture())
