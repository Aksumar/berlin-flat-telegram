# Berlin Flat Telegram

Kotlin/JVM 21 and Spring Boot consumer for apartment notifications. Accepts **only
Kafka listing contract v2** and renders one Russian Telegram template with one house
emoji. See [the event contract](docs/listing-v2.md) for fields and source semantics.

## Отправить тестовое сообщение — запуск кнопкой Play

Команды готовы для текущего проекта на этом Mac: ничего подставлять не нужно.
Открой Docker Desktop, затем запускай блоки по порядку кнопкой Play.
Запускай команды из корня репозитория `telegram` (папки с `gradlew`).

**1. Запустить существующие Kafka и Telegram-consumer.** Если они уже работают,
команда оставит их работающими. После запуска подожди около 10 секунд.

```bash
docker start berlin-flat-watcher-kafka-1 berlin-flat-telegram-main
```

**2. Отправить одно тестовое объявление в Telegram через Kafka.**
Каждое нажатие Play отправляет новую запись во все настроенные чаты.

```bash
python3 scripts/kafka/send_test_listing.py --container berlin-flat-watcher-kafka-1 --bootstrap-server localhost:9092 --topic berlin-flat-listings-v1
```

Объявление можно изменить в [test-listing.json](scripts/kafka/test-listing.json).
Генерация карты и доставка могут занять около 30 секунд.
`Sent one listing` означает, что Kafka приняла запись.

**3. Если сообщение не пришло — посмотреть логи Telegram-consumer.**

```bash
docker logs --tail 80 berlin-flat-telegram-main
```

Дополнительные готовые команды — в [инструкции к скрипту](scripts/kafka/README.md).
Контейнер использует настройки `.env`, сохранённые при его создании:
после изменения `.env` контейнер нужно пересоздать, обычный `docker start`
новые значения не загрузит.

## Build and run

Requires JDK 21; Gradle is provided by the checked-in wrapper.

```sh
./gradlew test bootJar
java -jar build/libs/app.jar
```

The application runs continuously until it is stopped.

Copy `.env.example` for reference and export the variables through your shell,
container or deployment system. The application does not automatically load `.env`.

| Variable | Default / meaning |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Required broker addresses |
| `KAFKA_TOPIC` | `berlin-flat-listings-v1` (name is independent of payload version) |
| `KAFKA_GROUP_ID` | `berlin-flat-telegram-v1` |
| `TELEGRAM_BOT_TOKEN` | Required |
| `TELEGRAM_CHAT_IDS` | Required comma-separated chat IDs; Spring binds them to a list |
| `GEOAPIFY_API_KEY` | Optional; enables apartment map images |

## Apartment maps

Set `GEOAPIFY_API_KEY` to enable Geoapify Geocoding and Static Maps (Routing API is
not used). Each listing gets a 960 × 600 map with the provider's street labels and labelled stations, a red location marker and a Berlin overview
inset with a small location dot. The heading shows the source website and district, e.g. `🏠 InBerlinWohnen · Mitte`.
The housing company appears separately only when it differs from the source.
Buttons open the location in Google Maps and the original listing. Images are uploaded to Telegram;
the Geoapify key is never included in message URLs.

Matches that do not meet the exact-building criteria below are labelled
**Примерное расположение**. City-only, low-confidence and out-of-bounds matches
produce text only. Missing keys, provider
errors and invalid main-map images also fall back to text. Individual requests have a
twenty-second timeout within a thirty-second map budget; one image is reused for all chats.
A permanent Telegram photo rejection falls back to text; exhausted transient delivery
failures retain the Kafka record for recovery.

Captions up to 1024 UTF-16 units stay with the photo. Longer formatted listings follow
as a quiet text message, so only the photo triggers a notification. The existing 4096-unit
text limit still applies. Map-provider attribution remains visible on the image.
See [Geoapify Forward Geocoding](https://apidocs.geoapify.com/docs/geocoding/forward-geocoding/),
[Geoapify Static Maps](https://apidocs.geoapify.com/docs/maps/static/) and
[Telegram sendPhoto](https://core.telegram.org/bots/api#sendphoto).

### How the Geoapify client works

The client is implemented in
[`ListingMaps.kt`](src/main/kotlin/com/aksumar/telegram/maps/ListingMaps.kt).
For each listing it performs these steps:

1. Use the nonblank `address.full`, or assemble a query from street, house number,
   postcode, district and city. Without an API key or a nonblank address, return no map.
2. Call `GET https://api.geoapify.com/v1/geocode/search` with `text`, `format=json`,
   `lang=de`, `limit=1` and `filter=rect:13.08,52.33,13.77,52.68`.
   Only the first result is examined; if it fails validation, the client does not
   search for another candidate.
3. Require `rank.confidence >= 0.8`, longitude within `13.08–13.77`, latitude within
   `52.33–52.68`, and `result_type` equal to `building`, `street`, `suburb`, `district`
   or `postcode`. A `city` result identifies only a city, not a street or building,
   so it is rejected even with high confidence. The rectangle is a fixed search
   area around Berlin, **not its administrative boundary**; the client does not
   check whether a point belongs to Berlin. The same rectangle filters the API
   search and validates returned coordinates.
4. Treat a match as exact only when `result_type=building`,
   `rank.confidence_building_level >= 0.95` and `rank.match_type=full_match`.
   All other accepted matches are approximate. These thresholds and geographic
   bounds are hardcoded application choices, not Geoapify requirements.
5. Fetch two PNGs from `GET https://maps.geoapify.com/v1/staticmap`, using `osm-bright`
   style for the main map and `positron` for the overview, with German labels and
   provider attribution. The main map is `960 × 600`,
   centred on the coordinates, with a red house marker for exact buildings or a circle
   for approximate matches, and zoom `15.5` for buildings
   or streets, otherwise `12.5`. Street names use the native `osm-bright` labels, with
   their default size, color and placement; some streets may be unlabelled at this zoom.
   Built-in POIs are hidden. Only transport stations and stops are overlaid;
   shops, pharmacies, parks and other amenities are neither requested nor marked.
   The overview is `288 × 240`, centred on the address at zoom `9`,
   and marks the same coordinates with a small red dot. If the overview fails,
   the main map is still sent.
6. Fetch nearby subway, train and light-rail stations from [Geoapify Places](https://apidocs.geoapify.com/docs/places/).
   Overlay all returned U-Bahn and confirmed S-Bahn stations in the visible map,
   deduplicating station names within each transport type. At application startup,
   [`berlin_u_s_stations_colors.csv`](src/main/resources/berlin_u_s_stations_colors.csv)
   is loaded into an in-memory lookup from normalized station name to every served
   S/U line and its supplied HEX color. Matching line numbers are drawn as colored
   chips beside station labels; unmatched names keep the generic transport badge.
   If no visible S/U station is found, query tram and bus stops separately and prefer
   a tram. Skip the part of the main map covered by the overview. Transport stops
   keep name labels; S/U stations also show their line numbers. Transport requests
   use map-bounds filtering and at most two pages of 500 results.

7. Compose a `960 × 600` PNG locally with Java `Graphics2D`: place the overview in
   a bordered inset at the lower right with the heading `БЕРЛИН`, preserving the
   main map's attribution. Add `Примерное расположение` at the upper left for an
   approximate match. Return the PNG bytes, a Google Maps link and the
   approximate-location flag. The same generated image is reused for all chats.
   The link for an approximate match opens a map view without a pin.

The requests run sequentially, without Geoapify retries: one geocoding request
and, if accepted, two static-map requests and Places requests for transport.
The HTTP client has a 10-second connect timeout;
each request is limited by its 20-second timeout and the remaining 30-second map
budget. Each response must have HTTP status
200 and a body no larger than 5,000,000 bytes; map images must decode successfully
and have the expected dimensions. Geocoding and main-map failures return no map,
so the listing can still be sent as text. Error logs omit request URLs and response bodies to
avoid exposing the API key.

### Enable maps in Docker

To enable in an existing Docker deployment, add `GEOAPIFY_API_KEY=...` to the `.env`
passed via `docker run --env-file .env`, rebuild/pull the updated image and recreate the
container. For Docker Compose, explicitly pass the variable to your existing service:

```yaml
services:
  telegram:
    environment:
      GEOAPIFY_API_KEY: ${GEOAPIFY_API_KEY:-}
```

A Compose `.env` supplies interpolation values; it does not automatically pass every
variable into the container. Recreate the service after updating the image/environment
(`docker compose up -d --build --force-recreate telegram` for a locally built image).
Keep the key out of Git. Without it the bot continues sending text notifications.

## Delivery and recovery

Kafka connections currently use `PLAINTEXT`; see `TODO.md` for the planned SASL_SSL hardening.
Spring Kafka uses a single-record listener with one consumer thread and auto-commit disabled.
The listener waits for delivery to all chats before returning, so the Kafka offset is
acknowledged only after Telegram delivery handling completes.

Malformed Kafka records and permanent Telegram rejections are logged and skipped so they do
not block later listings. Transient Telegram failures are retried inside `TelegramClient`:
network failures and `5xx` responses use bounded exponential backoff, while Telegram `429`
responses respect `retry_after` up to the configured bound. Permanent `4xx` responses are
not retried.

If all transient delivery attempts are exhausted, the listener completes exceptionally and
the Kafka record remains uncommitted. The container stops through the fatal error handler, so
a service restart can retry that record instead of silently losing the listing.

Kafka offsets are the only delivery state. A crash after Telegram accepts a message but before
the Kafka offset is committed can still cause a duplicate because Telegram does not provide an
idempotency key.

Spring manages Kafka listener startup and shutdown. Missing topics prevent listener startup.
Kafka/broker-level consumer failures send a Telegram alert to all configured chats and stop
the listener; restart the service to resume consumption.

SIGTERM stops the listener through Spring's shutdown lifecycle; allow time for an in-flight
HTTP request (30-second timeout) before force-killing.

## Docker

```sh
docker build -t berlin-flat-telegram .
docker run --rm --env-file .env berlin-flat-telegram
```

The image builds with JDK 21 and runs on JRE 21. Its entrypoint is `java -jar /app/app.jar`.
**Remove old Compose commands such as `python -m notifier.main`.** The container runs
continuously by default.

## Tests and CI

For manual Kafka publishing, see [the script and sample listing](scripts/kafka/README.md).
Run `python3 scripts/kafka/send_test_listing.py --dry-run` to preview a record.

```sh
./gradlew test                  # Unit tests; fake local HTTP server, no real Telegram
./gradlew integrationTest       # Docker required: isolated Kafka via Testcontainers
./gradlew check bootJar         # Both suites plus executable JAR
```

Tests cover message formatting, strict v2 validation, UTF-16 limits, Telegram HTTP success,
transient retries, permanent rejection handling, invalid-record skipping, and Kafka offset
preservation after exhausted transient delivery failures. Telegram itself is replaced with a
test sender in Kafka integration tests.

The build workflow runs on pull requests, pushes to `main`, `v*` tags, and manual
runs. Feature-branch pushes do not start a second pipeline alongside the PR check.
Both test suites run on the runner. PRs, version tags, and manual runs only run
the tests; Docker images are built only by the publication job on pushes to `main`.
The same `Dockerfile` is used locally and in GitHub Actions.

Only a push to `main` (normally after merging a PR) publishes images to GHCR after
successful tests. Direct pushes to main follow the same path. The separate
publication job is the only job with `packages: write`. Images receive
`sha-<commit>`, `branch-main`, and `latest` tags. The publication job checks out
the same commit and builds directly from source; no artifact transfer is needed.
The image build runs on `main` after the PR tests have passed.

On pushes to main, image/source archives, image metadata, commit ID, and SHA256
checksums are exported and retained for 14 days. Test/publication report uploads
and Docker build-record uploads remain temporarily disabled because the artifact
storage quota is exhausted. Logs remain available in the Actions run. Archive
uploads still require artifact storage. When report uploads are
restored, they will run only on pushes to main, with 14-day retention.

Production delivery is a long-running service; there is no scheduled GitHub Actions
consumer.
