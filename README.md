# Berlin Flat Telegram

Kotlin/JVM 21 and Spring Boot consumer for apartment notifications. Accepts **only
Kafka listing contract v2** and renders one Russian Telegram template with one house
emoji. See [the event contract](docs/listing-v2.md) for fields and source semantics.

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
not used). Each listing gets a 640 × 400 map with labelled streets and stations, a red house marker and a Berlin overview
inset with a small location dot. The heading shows the source website and district, e.g. `🏠 InBerlinWohnen · Mitte`.
The housing company appears separately only when it differs from the source.
Buttons open the map and the original listing. Images are uploaded to Telegram;
the Geoapify key is never included in message URLs.

Street/district/postcode matches are labelled **Примерное расположение**. City-only,
low-confidence and out-of-Berlin matches produce text only. Missing keys, provider
errors and invalid images also fall back to text. Map requests have twenty-second
individual timeouts (up to three requests per listing); one image is reused for all chats.
A permanent Telegram photo rejection falls back to text; exhausted transient delivery
failures retain the Kafka record for recovery.

Captions up to 1024 UTF-16 units stay with the photo. Longer formatted listings follow
as a quiet text message, so only the photo triggers a notification. The existing 4096-unit
text limit still applies. Map-provider attribution remains visible on the image.
See [Geoapify Static Maps](https://apidocs.geoapify.com/docs/maps/static/) and
[Telegram sendPhoto](https://core.telegram.org/bots/api#sendphoto).

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

The service verifies broker/topic availability before starting the listener. Kafka/broker-level
consumer failures send a Telegram alert to all configured chats and then stop the service.

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
Both test suites and `bootJar` run once. CI packages that JAR using `Dockerfile.ci`
and a minimal build context; the regular `Dockerfile` still builds from source locally.
PRs verify the image build without publishing. This is a build check, not a container
startup test.

After successful tests, a separate publication job publishes images to GHCR on
`main`, tags, and manual runs (including manually selected feature branches).
Only this job has `packages: write`. Images receive `sha-<commit>` and either
`branch-<branch>` or the Git tag; `latest` is updated only from the default branch.
The tested JAR and image recipe are transferred between jobs as a one-day artifact.

Test reports are retained for 14 days, including when tests fail. Image/source
archives, image metadata, commit ID, and SHA256 checksums are exported only for tag
runs or manual runs with **export_archives** enabled, and retained for 14 days.
Use Actions → Build, test and publish → Run workflow to select a branch and enable
that option. Manual runs also publish the image to GHCR. Ordinary runs use GHCR
without exporting an image archive.

Production delivery is a long-running service; there is no scheduled GitHub Actions
consumer.
