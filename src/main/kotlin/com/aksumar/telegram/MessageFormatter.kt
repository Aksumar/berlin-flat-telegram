package com.aksumar.telegram

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Component
class MessageFormatter {
    fun format(item: Listing): String {
        fun text(value: String?) = value?.replace(Regex("(?U)\\s+"), " ")?.trim() ?: "не указано"
        fun number(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString()?.replace('.', ',') ?: "не указано"
        fun price(value: BigDecimal?, starting: Boolean? = false): String {
            if (value == null) return "не указано"
            val symbols = DecimalFormatSymbols(Locale.ROOT).apply { decimalSeparator = ','; groupingSeparator = ' ' }
            val format = DecimalFormat("#,##0.00", symbols).apply { roundingMode = RoundingMode.HALF_EVEN }
            return (if (starting == true) "от " else "") + format.format(value) + " €"
        }
        val a = item.address
        val location = a.full?.takeIf { it.isNotEmpty() } ?: listOf(
            listOfNotNull(a.street, a.houseNumber).filter { it.isNotEmpty() }.joinToString(" "),
            listOfNotNull(a.postalCode, a.city).filter { it.isNotEmpty() }.joinToString(" ")
        ).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
        val source = mapOf("allod" to "Allod", "rbb" to "RBB", "berlinhaus" to "Berlinhaus", "berlinovo" to "Berlinovo",
            "degewo" to "Degewo", "wbm" to "WBM", "gewobag" to "Gewobag", "inberlinwohnen" to "InBerlinWohnen").getValue(item.source)
        val heading = source + (a.district?.takeIf { it.isNotEmpty() }?.let { " · ${text(it)}" } ?: "")
        val lines = mutableListOf(heading, "", "Адрес: ${text(location)}",
            "Площадь: ${number(item.areaM2)}" + if (item.areaM2 != null) " м²" else "", "Комнат: ${number(item.rooms)}")
        item.floor?.let { lines += "Этаж: ${text(it)}" }
        lines += listOf("", "Warmmiete: ${price(item.rent.warm, item.rent.warmFrom)}" + if (item.rent.warm != null) "/мес." else "",
            "Kaltmiete: ${price(item.rent.cold)}" + if (item.rent.cold != null) "/мес." else "")
        listOf("Коммунальные" to item.rent.operatingCosts, "Отопление" to item.rent.heatingCosts, "Залог" to item.rent.deposit)
            .forEach { (label, value) -> if (value != null) lines += "$label: ${price(value)}" }
        val extra = mutableListOf<String>()
        (item.availability.text?.takeIf { it.isNotEmpty() } ?: item.availability.date)?.let { extra += "Доступна: ${text(it)}" }
        if (item.wbs.required != null || !item.wbs.text.isNullOrEmpty()) {
            val status = when (item.wbs.required) { true -> "требуется"; false -> "не требуется"; null -> "не указано" }
            extra += "WBS: $status" + (item.wbs.text?.takeIf { it.isNotEmpty() }?.let { " (${text(it)})" } ?: "")
        }
        listOf("Балкон" to item.features.balcony, "Лифт" to item.features.elevator, "Встроенная кухня" to item.features.builtInKitchen)
            .forEach { (label, value) -> if (value != null) extra += "$label: ${if (value) "есть" else "нет"}" }
        if (extra.isNotEmpty()) lines += listOf("") + extra
        lines += ""
        item.provider?.takeIf { it.isNotEmpty() && !it.equals(source, ignoreCase = true) }?.let { lines += "Компания: ${text(it)}" }
        var body = lines.joinToString("\n")
        val budget = 4096 - item.url.length - 1
        if (budget < 1) throw DeliveryException("Listing URL exceeds Telegram limit")
        if (body.length > budget) {
            var end = budget - 1
            if (end > 0 && body[end - 1].isHighSurrogate()) end--
            body = body.take(end) + "…"
        }
        return "$body\n${item.url}"
    }
}
