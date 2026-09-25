package com.aksumar.telegram.contract

import java.math.BigDecimal

data class Address(
    val full: String?,
    val street: String?,
    val houseNumber: String?,
    val postalCode: String?,
    val city: String?,
    val district: String?
)

data class Rent(
    val currency: String,
    val cold: BigDecimal?,
    val warm: BigDecimal?,
    val operatingCosts: BigDecimal?,
    val heatingCosts: BigDecimal?,
    val deposit: BigDecimal?,
    val warmFrom: Boolean?
)

data class Availability(val date: String?, val text: String?)
data class Wbs(val required: Boolean?, val text: String?)
data class Features(val balcony: Boolean?, val elevator: Boolean?, val builtInKitchen: Boolean?)

data class Listing(
    val version: Int,
    val source: String,
    val id: String,
    val title: String,
    val url: String,
    val address: Address,
    val areaM2: BigDecimal?,
    val rooms: BigDecimal?,
    val floor: String?,
    val rent: Rent,
    val availability: Availability,
    val wbs: Wbs,
    val features: Features,
    val provider: String?
)
