package com.aksumar.telegram.model

import java.math.BigDecimal

data class Address(
    /** Full address from the listing; null if unavailable. */
    val full: String?,
    /** Street name; null if unknown. */
    val street: String?,
    /** House number, including any letter suffix; null if unknown. */
    val houseNumber: String?,
    /** Postal code; null if unknown. */
    val postalCode: String?,
    /** City; null if unspecified. */
    val city: String?,
    /** City district; null if unspecified. */
    val district: String?,
) {
    fun searchQuery(): String {
        val parts = mutableListOf<String>()
        val structuredStreet = !street.isNullOrBlank() &&
            (!houseNumber.isNullOrBlank() || full.isNullOrBlank())
        val base = if (structuredStreet) {
            listOfNotNull(street, houseNumber).filter { it.isNotBlank() }.joinToString(" ")
        } else full.orEmpty().trim()
        if (base.isNotBlank()) parts += base
        val locality = listOfNotNull(postalCode, city).map { it.trim() }
            .filter { it.isNotBlank() && !containsPart(base, it) }.joinToString(" ")
        if (locality.isNotBlank()) parts += locality
        district?.trim()?.takeIf { it.isNotBlank() && !containsPart(parts.joinToString(", "), it) }
            ?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun containsPart(text: String, part: String): Boolean =
        Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(part)}(?![\\p{L}\\p{N}])").containsMatchIn(text)
}

data class Rent(
    /** Currency used for monetary amounts; the current contract supports EUR. */
    val currency: String,
    /** Monthly base rent excluding additional costs (Kaltmiete); null if unknown. */
    val cold: BigDecimal?,
    /** Monthly rent including additional costs and heating (Warmmiete); null if unknown. */
    val warm: BigDecimal?,
    /** Monthly operating costs (Betriebskosten); null if unknown. */
    val operatingCosts: BigDecimal?,
    /** Monthly heating costs; null if not listed separately. */
    val heatingCosts: BigDecimal?,
    /** Rental deposit (Kaution); null if unspecified. */
    val deposit: BigDecimal?,
)

data class Availability(
    /** Availability date in YYYY-MM-DD format; null if the exact date is unknown. */
    val date: String?,
    /** Availability details from the listing; null if absent. */
    val text: String?,
)

data class Wbs(
    /**
     * Whether a Wohnberechtigungsschein (eligibility certificate for subsidized housing) is
     * required; null if unknown.
     */
    val required: Boolean?,
    /** WBS eligibility requirements from the listing; null if absent. */
    val text: String?,
)

data class Features(
    /** Whether the apartment has a balcony; null if unspecified. */
    val balcony: Boolean?,
    /** Whether the building has an elevator; null if unspecified. */
    val elevator: Boolean?,
    /** Whether the apartment has a built-in kitchen; null if unspecified. */
    val builtInKitchen: Boolean?,
)

data class Listing(
    /** Listing payload format version; the current contract accepts version 2. */
    val version: Int,
    /** Listing source identifier, such as gewobag or degewo. */
    val source: String,
    /** Listing identifier assigned by the source. */
    val id: String,
    /** Listing title. */
    val title: String,
    /** URL of the listing page. */
    val url: String,
    /** Full address and its individual components. */
    val address: Address,
    /** Apartment area in square meters; null if unknown. */
    val areaM2: BigDecimal?,
    /** Number of rooms, including fractional values; null if unknown. */
    val rooms: BigDecimal?,
    /** Floor as written in the listing; null if unknown. */
    val floor: String?,
    /** Rent, additional costs, and deposit. */
    val rent: Rent,
    /** Apartment availability date and description. */
    val availability: Availability,
    /** WBS eligibility requirements for renting the apartment. */
    val wbs: Wbs,
    /** Information about the balcony, elevator, and built-in kitchen. */
    val features: Features,
    /** Housing company offering the apartment; null if unspecified. */
    val provider: String?,
)
