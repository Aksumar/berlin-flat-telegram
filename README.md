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
| `TELEGRAM_CHAT_IDS` | Comma-separated chat IDs; blanks removed and duplicates collapsed |
| `TELEGRAM_CHAT_ID` | Fallback when CHAT_IDS is empty |

## Delivery and recovery

Kafka connections currently use `PLAINTEXT`; see `TODO.md` for the planned SASL_SSL hardening.
Spring Kafka uses one record listener, one consumer thread, auto-commit disabled and
`MANUAL_IMMEDIATE` synchronous acknowledgments. The service verifies broker/topic
availability before starting the listener. Its error handler stops consumption on
failure; there is no automatic message skipping or dead-letter recovery. Malformed JSON, v1, wrong keys and failed Telegram requests leave the record
unacknowledged and result in a nonzero exit code. Restart after fixing the cause.

Kafka offsets are the only delivery state. The listener acknowledges an event only after
it has been sent successfully to every configured Telegram chat. If delivery fails,
the offset is not committed and Kafka will retry the event after restart.

This provides at-least-once delivery, not exactly-once delivery. A crash after Telegram
accepts a message but before the Kafka offset is committed can cause a duplicate. With
multiple chats, if one chat succeeds and a later chat fails, the successful chat can
receive the same event again on retry. Telegram does not provide an idempotency key.

SIGTERM stops the listener through Spring's shutdown lifecycle; allow time for an
in-flight HTTP request (30-second timeout) before force-killing.

## Docker

```sh
docker build -t berlin-flat-telegram .
docker run --rm --env-file .env berlin-flat-telegram
```

The image builds with JDK 21 and runs on JRE 21. Its entrypoint is `java -jar /app/app.jar`.
**Remove old Compose commands such as `python -m notifier.main`.** The container runs continuously by default.
Use `stop_grace_period: 90s` so an in-flight Telegram request can finish cleanly.

## Tests and CI

```sh
./gradlew test                  # Unit tests; fake local HTTP server, no real Telegram
./gradlew integrationTest       # Docker required: isolated Kafka via Testcontainers
./gradlew check bootJar         # Both suites plus executable JAR
```

Tests cover the exact Python message template, strict v2 validation, UTF-16 limits,
HTTP failures without token disclosure, failed delivery without Kafka acknowledgment,
and successful retry/commit behavior. Integration tests start the
real Spring listener against Kafka, verify committed offsets and ensure v1 stops
consumption before a subsequent v2 event. Telegram delivery is replaced in broker tests.

The build workflow runs both suites, builds/verifies the image, uploads reports and
image/source archives, then publishes branch/SHA images to GHCR on pushes (`latest`
only from the default branch). Production delivery is a long-running service; there is
no scheduled GitHub Actions consumer.

## Migration

This implementation replaces Python after the v2-format PR. Stop the Python consumer,
then start Kotlin with the **same consumer group and topic**; do not reset offsets. Drain any older v1 backlog with an old v1-capable
consumer before the v2-only rollout, as described in the contract document. Do not run
Python and Kotlin simultaneously during handover. A rollback should reuse the same Kafka consumer group after Kotlin is stopped.
