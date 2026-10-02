package com.aksumar.telegram.maps

import java.awt.Color
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

data class TransitLine(val name: String, val color: Color)

private val stationPrefix = Regex("^(?:[SU](?:\\s*[+/]\\s*[SU])?\\s+)", RegexOption.IGNORE_CASE)
private val stationType = Regex("\\b(?:bahnhof|bhf)\\b\\.?", RegexOption.IGNORE_CASE)
private val berlinSuffix = Regex("\\s+\\(Berlin\\)$", RegexOption.IGNORE_CASE)
private val repeatedSpace = Regex("\\s+")

internal fun stationDisplayName(value: String): String = value.trim().replace(stationPrefix, "")

internal fun stationKey(value: String): String =
    stationDisplayName(value)
        .replace(stationType, "")
        .replace(berlinSuffix, "")
        .replace(repeatedSpace, " ")
        .trim()
        .lowercase()

/** Rail lines/colors and bus/tram routes loaded once from bundled VBB-derived resources at startup. */
@Component
class VbbTransitCache internal constructor(
    private val busCatalog: StopRouteCatalog,
    private val tramCatalog: StopRouteCatalog = StopRouteCatalog.load(ClassPathResource("berlin_tram_routes.json")),
) {
    constructor() : this(StopRouteCatalog.load(ClassPathResource("berlin_bus_routes.json")))

    internal fun stopFor(station: Landmark): RouteStop? =
        when (station.kind) {
            LandmarkKind.BUS -> busCatalog
            LandmarkKind.TRAM -> tramCatalog
            else -> null
        }?.find(station.name, station.longitude, station.latitude)

    private val logger = LoggerFactory.getLogger(VbbTransitCache::class.java)
    private val linesByStation: Map<String, List<TransitLine>> = load()

    fun linesFor(stationName: String): List<TransitLine> =
        linesByStation[stationKey(stationName)].orEmpty()

    private fun load(): Map<String, List<TransitLine>> =
        try {
            val resource = ClassPathResource("berlin_u_s_stations_colors.csv")
            resource.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val header = parseCsv(reader.readLine().removePrefix("\uFEFF"))
                val indices = header.withIndex().associate { it.value to it.index }
                val stationIndex = indices.getValue("station")
                val lineIndex = indices.getValue("line")
                val colorIndex = indices.getValue("official_color_hex")
                val collected = mutableMapOf<String, MutableMap<String, TransitLine>>()
                reader.lineSequence().forEach { row ->
                    val values = parseCsv(row)
                    if (values.size <= maxOf(stationIndex, lineIndex, colorIndex)) return@forEach
                    val station = stationKey(values[stationIndex])
                    val name = values[lineIndex].trim()
                    val color = runCatching { Color.decode(values[colorIndex]) }.getOrNull()
                    if (station.isNotBlank() && name.isNotBlank() && color != null) {
                        collected.getOrPut(station) { linkedMapOf() }[name] = TransitLine(name, color)
                    }
                }
                val result = collected.mapValues { (_, lines) -> lines.values.sortedBy { it.name } }
                logger.info("Loaded VBB line colors for {} stations into transit cache", result.size)
                result
            }
        } catch (_: Exception) {
            logger.warn("VBB station color data unavailable; using generic transport markers")
            emptyMap()
        }

    private fun parseCsv(line: String?): List<String> {
        if (line == null) return emptyList()
        val values = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    value.append('"')
                    i++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += value.toString()
                    value.setLength(0)
                }
                else -> value.append(char)
            }
            i++
        }
        values += value.toString()
        return values
    }
}
