package com.aksumar.telegram.format

import com.aksumar.telegram.model.Listing
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

        return truncateWithinTelegramLimit(lines.joinToString("\n"))
    }

    private fun addHeader(lines: MutableList<String>, item: Listing) {
        lines += heading(item)
        lines += ""
        lines += "Адрес: ${text(location(item))}"
        lines += "Площадь: ${number(item.areaM2)}${if (item.areaM2 != null) " м²" else ""}"
        lines += "Комнат: ${number(item.rooms)}"
        lines += "Этаж: ${floor(item.floor)}"
    }

    private fun addRent(lines: MutableList<String>, item: Listing) {
        lines += ""
        lines += "Warmmiete: ${price(item.rent.warm)}${if (item.rent.warm != null) "/мес." else ""}"
        lines += "Kaltmiete: ${price(item.rent.cold)}${if (item.rent.cold != null) "/мес." else ""}"

        lines += "Коммунальные: ${price(item.rent.operatingCosts)}"
        lines += "Отопление: ${price(item.rent.heatingCosts)}"
        lines += "Залог: ${price(item.rent.deposit)}"
    }

    private fun addDetails(lines: MutableList<String>, item: Listing) {
        lines += ""
        val availability = item.availability.date?.let {
            LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
        } ?: when (item.availability.text?.trim()?.lowercase(Locale.ROOT)) {
            "sofort" -> "сразу"
            else -> text(item.availability.text)
        }
        lines += "Доступна: $availability"
        lines += wbs(item)
        lines += "Балкон: ${feature(item.features.balcony)}"
        lines += "Лифт: ${feature(item.features.elevator)}"
        lines += "Встроенная кухня: ${feature(item.features.builtInKitchen)}"
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

        val note = item.wbs.text
            ?.takeIf { item.wbs.required == true && it.isNotBlank() }
            ?.let { " (${text(it)})" } ?: ""

        return "WBS: $status$note"
    }

    private fun feature(value: Boolean?): String = when (value) {
        true -> "есть"
        false -> "нет"
        null -> "не указано"
    }

    private fun floor(value: String?): String = when (value?.trim()?.uppercase(Locale.ROOT)) {
        "EG" -> "0"
        "DG" -> "мансарда"
        "UG" -> "подвальный"
        else -> text(value)
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
        value?.replace(Regex("(?U)\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: "не указано"

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

    private fun truncateWithinTelegramLimit(body: String): String {
        if (body.length <= TELEGRAM_MESSAGE_LIMIT) {
            return body
        }

        var end = TELEGRAM_MESSAGE_LIMIT - 1
        if (end > 0 && body[end - 1].isHighSurrogate()) {
            end--
        }

        return "${body.take(end)}…"
    }

    companion object {
        private const val TELEGRAM_MESSAGE_LIMIT = 4096
    }
}
