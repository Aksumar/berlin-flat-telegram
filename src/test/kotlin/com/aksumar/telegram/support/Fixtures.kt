package com.aksumar.telegram.support

import com.aksumar.telegram.config.JacksonConfig
import com.aksumar.telegram.kafka.ListingContract
import com.aksumar.telegram.model.Listing

val testMapper = JacksonConfig().objectMapper()

fun fixture(): String = object {}.javaClass.getResource("/listing-v2.json")!!.readText()

fun event(): Listing = ListingContract(testMapper).decode(fixture())
