package com.aksumar.telegram.format

import com.aksumar.telegram.exception.DeliveryException
import com.aksumar.telegram.model.Listing
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.springframework.stereotype.Component

@Component
class MessageFormatter {
    fun mapUrl(item: Listing): String? =
        item.address.searchQuery().takeIf { it.isNotBlank() }?.let {
            "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
        }

    fun format(item: Listing): String {
        val lines = mutableListOf<String>()

        addHeader(lines, item)
        addRent(lines, item)
        addDetails(lines, item)
        addProviderAndSource(lines, item)

        return appendUrlWithinTelegramLimit(lines.joinToString("\n"), item.url)
    }

    private fun addHeader(lines: MutableList<String>, item: Listing) {
        lines += heading(item)
        lines += ""
        lines += "Адрес: ${text(location(item))}"
        lines += "Площадь: ${number(item.areaM2)}${if (item.areaM2 != null) " м²" else ""}"
        lines += "Комнат: ${number(item.rooms)}"
        item.floor?.let { lines += "Этаж: ${text(it)}" }
    }

    private fun addRent(lines: MutableList<String>, item: Listing) {
        lines += ""
        lines += "Warmmiete: ${price(item.rent.warm)}${if (item.rent.warm != null) "/мес." else ""}"
        lines += "Kaltmiete: ${price(item.rent.cold)}${if (item.rent.cold != null) "/мес." else ""}"

        addOptionalPrice(lines, "Коммунальные", item.rent.operatingCosts)
        addOptionalPrice(lines, "Отопление", item.rent.heatingCosts)
        addOptionalPrice(lines, "Залог", item.rent.deposit)
    }

    private fun addDetails(lines: MutableList<String>, item: Listing) {
        val details = mutableListOf<String>()

        val availability =
            item.availability.text?.takeIf { it.isNotEmpty() } ?: item.availability.date
        availability?.let { details += "Доступна: ${text(it)}" }

        if (item.wbs.required != null || !item.wbs.text.isNullOrEmpty()) {
            details += wbs(item)
        }

        addFeature(details, "Балкон", item.features.balcony)
        addFeature(details, "Лифт", item.features.elevator)
        addFeature(details, "Встроенная кухня", item.features.builtInKitchen)

        if (details.isNotEmpty()) {
            lines += ""
            lines += details
        }
    }

    private fun addProviderAndSource(lines: MutableList<String>, item: Listing) {
        val provider = item.provider?.takeIf { it.isNotBlank() }
        if (provider != null && !sourceName(item.source).equals(provider, ignoreCase = true)) {
            lines += ""
            lines += "Компания: ${text(provider)}"
        }
    }

    private fun heading(item: Listing): String {
        val district =
            item.address.district?.takeIf { it.isNotEmpty() }?.let { " · ${text(it)}" } ?: ""

        return "🏠 ${sourceName(item.source)}$district"
    }

    private fun location(item: Listing): String? {
        val address = item.address

        address.full
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }

        val street =
            listOfNotNull(address.street, address.houseNumber)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        val city =
            listOfNotNull(address.postalCode, address.city)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        return listOf(street, city).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    private fun wbs(item: Listing): String {
        val status =
            when (item.wbs.required) {
                true -> "требуется"
                false -> "не требуется"
                null -> "не указано"
            }

        val note = item.wbs.text?.takeIf { it.isNotEmpty() }?.let { " (${text(it)})" } ?: ""

        return "WBS: $status$note"
    }

    private fun addOptionalPrice(lines: MutableList<String>, label: String, value: BigDecimal?) {
        value?.let { lines += "$label: ${price(it)}" }
    }

    private fun addFeature(lines: MutableList<String>, label: String, value: Boolean?) {
        value?.let { lines += "$label: ${if (it) "есть" else "нет"}" }
    }

    private fun sourceName(source: String): String =
        when (source) {
            "allod" -> "Allod"
            "rbb" -> "RBB"
            "berlinhaus" -> "Berlinhaus"
            "berlinovo" -> "Berlinovo"
            "gewobag" -> "Gewobag"
            "wbm" -> "WBM"
            "degewo" -> "Degewo"
            "inberlinwohnen" -> "InBerlinWohnen"
            "deutschewohnen" -> "Deutsche Wohnen"
            "howoge" -> "HOWOGE"
            else -> error("Unsupported source: $source")
        }

    private fun text(value: String?): String =
        value?.replace(Regex("(?U)\\s+"), " ")?.trim() ?: "не указано"

    private fun number(value: BigDecimal?): String =
        value?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "не указано"

    private fun price(value: BigDecimal?, starting: Boolean? = false): String {
        if (value == null) {
            return "не указано"
        }

        val symbols =
            DecimalFormatSymbols(Locale.ROOT).apply {
                decimalSeparator = ','
                groupingSeparator = ' '
            }
        val format =
            DecimalFormat("#,##0.00", symbols).apply { roundingMode = RoundingMode.HALF_EVEN }

        val prefix = if (starting == true) "от " else ""
        return "$prefix${format.format(value)} €"
    }

    private fun appendUrlWithinTelegramLimit(body: String, url: String): String {
        val budget = TELEGRAM_MESSAGE_LIMIT - url.length - 1
        if (budget < 1) {
            throw DeliveryException("Listing URL exceeds Telegram limit")
        }

        if (body.length <= budget) {
            return "$body\n$url"
        }

        var end = budget - 1
        if (end > 0 && body[end - 1].isHighSurrogate()) {
            end--
        }

        return "${body.take(end)}…\n$url"
    }

    companion object {
        private const val TELEGRAM_MESSAGE_LIMIT = 4096
    }
}
