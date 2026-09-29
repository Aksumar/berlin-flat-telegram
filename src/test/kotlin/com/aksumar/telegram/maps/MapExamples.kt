package com.aksumar.telegram.maps

import com.aksumar.telegram.support.testMapper
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Explicit preview task; cached provider data keeps layout examples reproducible and offline. */
@Tag("map-examples")
class MapExamples {
    private val output = Path.of("examples/maps")
    private val cache = Path.of("build/map-examples-cache")
    private val renderer = LandmarkRenderer(VbbTransitCache())
    private val composer = MapComposer()

    @Test
    fun generateExamples() {
        Files.createDirectories(output)
        Files.createDirectories(cache)
        val apiKey = System.getenv("GEOAPIFY_API_KEY")?.takeIf { it.isNotBlank() } ?: localApiKey()
        val client = GeoapifyClient(apiKey)
        val maps = GeoapifyStaticMaps(client, "https://maps.geoapify.com/v1/staticmap")
        val location = GeocodedLocation(13.388, 52.52, approximate = false, detailZoom = 15.5)
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        val detail = cachedImage("detail-exact.png") { maps.detail(location, deadline) }
        val approximate = cachedImage("detail-approximate.png") { maps.detail(location.copy(approximate = true), deadline) }
        val overview = cachedImage("overview-zoom10.png") { requireNotNull(maps.overviewOrNull(location, deadline)) }
        val stations = cachedStations(client, location, deadline)
        val scenarios = listOf(
            Example("01-normal", "Обычная карта", "Реальные станции из Geoapify возле условной точки в центре Берлина.", stations),
            Example("02-s-and-u", "Пересадка S/U", "Название станции целиком; все S-Bahn в одном ряду, все U-Bahn в другом, без переноса линий. Координаты подписей заданы для теста.", listOf(
                Landmark("S+U Friedrichstraße Bhf", LandmarkKind.SUBURBAN_RAIL, 260, 180),
                Landmark("U Friedrichstraße", LandmarkKind.SUBWAY, 263, 180),
                Landmark("S+U Alexanderplatz Bhf", LandmarkKind.SUBURBAN_RAIL, 570, 160),
            )),
            Example("03-long-names-and-edges", "Длинные названия и края", "Перенос строк, сохранение полного названия, размещение внутри границ. Искусственные позиции.", listOf(
                Landmark("U Very long station name near the upper edge of the map", LandmarkKind.SUBWAY, 952, 10),
                Landmark("U Kochstraße / Checkpoint Charlie", LandmarkKind.SUBWAY, 8, 170),
                Landmark("S+U Gesundbrunnen Bhf", LandmarkKind.SUBURBAN_RAIL, 8, 570),
                Landmark("U Station with a long name next to the Berlin overview", LandmarkKind.SUBWAY, 630, 570),
            )),
            Example("04-crowded-all-stations", "Переполнение: все станции сохранены", "32 станции на искусственно уплотнённой карте. Непоместившиеся подписи вынесены ниже; номера связывают их с картой. География станций в этом стресс-сценарии условная.", crowdedStations()),
            Example("05-approximate-location", "Примерное расположение", "Круг вместо точной метки дома и предупреждение сверху. Подписи не перекрывают предупреждение.", stations, approximate = true),
            Example("06-no-overview", "Обзор недоступен", "Основная карта и станции сохраняются при сбое загрузки обзорной карты.", stations, hasOverview = false),
            Example("07-tram-fallback", "Трамвай вместо S/U", "Пример отображения трамвайных остановок, когда видимых S/U нет. Тестовые названия и позиции.", listOf(
                Landmark("Трамвайная остановка A", LandmarkKind.TRAM, 190, 200),
                Landmark("Трамвайная остановка B", LandmarkKind.TRAM, 570, 160),
            )),
            Example("08-no-station-data", "Данные о станциях недоступны", "Только основная карта, метка квартиры и обзор: так выглядит сбой Places API.", emptyList()),
        )
        val dimensions = scenarios.associate { example ->
            val layout = MapLayout(example.hasOverview, example.approximate)
            val base = if (example.approximate) approximate else detail
            val annotated = renderer.draw(base, example.stations, layout)
            val png = composer.compose(annotated, overview.takeIf { example.hasOverview }, layout)
            Files.write(output.resolve("${example.id}.png"), png)
            example.id to "${annotated.width} × ${annotated.height}"
        }
        writeGallery(scenarios, dimensions)
    }

    private fun cachedImage(name: String, fetch: () -> BufferedImage): BufferedImage {
        val path = cache.resolve(name)
        if (Files.exists(path)) return requireNotNull(ImageIO.read(path.toFile()))
        return fetch().also { check(ImageIO.write(it, "png", path.toFile())) }
    }

    private fun cachedStations(client: GeoapifyClient, location: GeocodedLocation, deadline: Long): List<Landmark> {
        val path = cache.resolve("stations.json")
        if (Files.exists(path)) return testMapper.readValue(Files.readAllBytes(path), Array<Landmark>::class.java).toList()
        val places = GeoapifyPlaces(client, testMapper, "https://api.geoapify.com/v2/places")
        val stations = places.findVisible(
            MapViewport(location.longitude, location.latitude, location.detailZoom),
            MapLayout(hasOverview = true, approximate = false),
            deadline,
        )
        Files.write(path, testMapper.writeValueAsBytes(stations))
        return stations
    }

    private fun localApiKey(): String {
        val env = Path.of(".env")
        if (!Files.exists(env)) return ""
        return Files.readAllLines(env).firstOrNull { it.startsWith("GEOAPIFY_API_KEY=") }
            ?.substringAfter('=')?.trim()?.trim('"', '\'').orEmpty()
    }

    private fun crowdedStations(): List<Landmark> {
        val names = listOf(
            "Friedrichstraße", "Alexanderplatz", "Potsdamer Platz", "Gesundbrunnen",
            "Hauptbahnhof", "Brandenburger Tor", "Stadtmitte", "Unter den Linden",
            "Kochstraße/Checkpoint Charlie", "Zoologischer Garten", "Westhafen", "Warschauer Straße",
            "Frankfurter Allee", "Neukölln", "Hermannstraße", "Schönhauser Allee",
            "Bundesplatz", "Yorckstraße", "Jannowitzbrücke", "Jungfernheide", "Wedding",
            "Tempelhof", "Innsbrucker Platz", "Heidelberger Platz", "Südkreuz", "Spandau",
            "Osloer Straße", "Kottbusser Tor", "Nollendorfplatz", "Wittenbergplatz", "Mehringdamm", "Bismarckstraße",
        )
        return names.mapIndexed { index, name ->
            Landmark(name, LandmarkKind.SUBWAY, 40 + (index % 8) * 78, 85 + (index / 8) * 100)
        }
    }

    private fun writeGallery(examples: List<Example>, dimensions: Map<String, String>) {
        val rows = examples.joinToString("\n            ") {
            "| [${it.title}](${it.id}.png) | ${dimensions.getValue(it.id)} | ${it.description} |"
        }
        Files.writeString(output.resolve("README.md"), """
            # Примеры карт

            Сгенерированы рабочими `LandmarkRenderer` и `MapComposer` поверх настоящей подложки Geoapify.
            Точка квартиры условная: 13.388, 52.52. Это визуальные сценарии, а не реальные объявления.
            Обычный пример использует ответ Places API; искусственные сценарии проверяют компоновку и не отражают географию станций.

            [Открыть галерею](index.html)

            | Сценарий | Размер | Что проверяем |
            | --- | --- | --- |
            $rows

            ## Пересоздание

            Из корня проекта: `./gradlew mapExamples --no-daemon`.
            При первом запуске нужен `GEOAPIFY_API_KEY` в окружении или `.env` и доступ к Geoapify.
            Подложка и ответ Places сохраняются в `build/map-examples-cache`; повторный запуск использует кэш.
            Для обновления подложки переименуйте или удалите эту папку кэша и запустите задачу снова.
            Ключ не записывается в изображения, галерею или кэш.
            Обычная задача `test` не обращается к провайдеру и не пересоздаёт примеры.
        """.trimIndent() + "\n")
        val cards = examples.joinToString("\n            ") {
            """<section><h2>${it.title}</h2><p>${it.description}</p><small>${dimensions.getValue(it.id)}</small><a href="${it.id}.png"><img src="${it.id}.png" alt="${it.title}" loading="lazy"></a></section>"""
        }
        Files.writeString(output.resolve("index.html"), """
            <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Примеры карт объявлений</title>
            <style>body{font:16px/1.5 system-ui;background:#edf0f4;color:#1d2939;max-width:1100px;margin:32px auto;padding:0 20px}h1{font-size:30px}section{background:white;border-radius:16px;padding:24px;margin:24px 0}h2{margin:0}p{max-width:900px}small{display:block;color:#667085;margin-bottom:12px}img{display:block;max-width:100%;height:auto;border:1px solid #e4e7ec;border-radius:8px}</style>
            <h1>Примеры карт объявлений</h1><p>Настоящая подложка, рабочий рендерер. Условная точка квартиры. Искусственные сценарии проверяют расположение подписей, а не географию станций. Нажмите на изображение, чтобы открыть полный размер.</p>
            $cards
            </html>
        """.trimIndent() + "\n")
    }

    private data class Example(
        val id: String,
        val title: String,
        val description: String,
        val stations: List<Landmark>,
        val approximate: Boolean = false,
        val hasOverview: Boolean = true,
    )
}
