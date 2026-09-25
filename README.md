# Berlin Flat Telegram

Kotlin/JVM 21 and Spring Boot consumer for apartment notifications. Accepts **only
Kafka listing contract v2** and renders one Russian Telegram template with one house
emoji. See [the event contract](docs/listing-v2.md) for fields and source semantics.

## Build and run

Requires JDK 21; Gradle is provided by the checked-in wrapper.

```sh
./gradlew test bootJar
java -jar build/libs/app.jar --state state/sent.json
```

`--state` defaults to `state/sent.json`. Both `--state value` and `--state=value` forms work.
`--help` prints usage without requiring Kafka or Telegram credentials. The application
runs continuously until it is stopped. The environment equivalent is `STATE_PATH`.

Copy `.env.example` for reference and export the variables through your shell,
container or deployment system. The application does not automatically load `.env`.

| Variable | Default / meaning |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Required broker addresses |
| `KAFKA_SECURITY_PROTOCOL` | `SASL_SSL`; also `SSL` or `PLAINTEXT` |
| `KAFKA_SASL_MECHANISM` | `PLAIN`; also `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | Required for SASL_SSL |
| `KAFKA_SSL_CA_LOCATION` | Optional PEM CA file; translated to the Java client's PEM truststore |
| `KAFKA_TOPIC` | `berlin-flat-listings-v1` (name is independent of payload version) |
| `KAFKA_GROUP_ID` | `berlin-flat-telegram-v1` |
| `TELEGRAM_BOT_TOKEN` | Required |
| `TELEGRAM_CHAT_IDS` | Comma-separated chat IDs; blanks removed and duplicates collapsed |
| `TELEGRAM_CHAT_ID` | Fallback when CHAT_IDS is empty |
| `STATE_PATH` | `state/sent.json` |

## Delivery and recovery

Spring Kafka uses one record listener, one consumer thread, auto-commit disabled and
`MANUAL_IMMEDIATE` synchronous acknowledgments. The service verifies broker/topic
availability before starting the listener. Its error handler stops consumption on
failure; there is no automatic message skipping or dead-letter recovery. Malformed
JSON, v1, wrong keys, failed Telegram requests and persistence failures leave the
record unacknowledged and result in a nonzero exit code. Restart after fixing the cause.

After each successful chat delivery, progress is atomically saved and flushed to disk.
Only after all configured chats are complete does the service mark the listing sent
and acknowledge Kafka. On restart, it sends only to chats missing from `pending`.
A commit failure after state persistence does not resend the listing.

The JSON file remains compatible with the Python service:

```json
{"version":1,"sent":["[\"allod\",\"old-id\"]"],"pending":{"[\"gewobag\",\"123\"]":["123"]}}
```

State version 1 is unrelated to Kafka payload version 2. Missing `pending` is accepted.
Corrupt state is fatal and is never reset. Kafka keys retain Python's compact ASCII
JSON encoding, including Unicode escaping. A file lock prevents two processes from
writing the same state path. Run a **single service instance** with a durable local
volume; different state files do not coordinate deduplication.

Delivery remains at-least-once: a crash between Telegram accepting a message and
saving the result can cause a duplicate. Telegram does not offer an idempotency key.
SIGTERM stops the listener through Spring's shutdown lifecycle; allow time for an
in-flight HTTP request (30-second timeout) and state persistence before force-killing.

## Docker

```sh
docker build -t berlin-flat-telegram .
docker run --rm berlin-flat-telegram --help
docker run --rm --env-file .env -v "$PWD/state:/app/state" \
  berlin-flat-telegram
```

The image builds with JDK 21 and runs on JRE 21. Its entrypoint is `java -jar /app/app.jar`.
**Remove old Compose commands such as `python -m notifier.main`.** Use:

```yaml
command: ["--state", "state/sent.json"]
stop_grace_period: 90s
```

## Tests and CI

```sh
./gradlew test                  # Unit tests; fake local HTTP server, no real Telegram
./gradlew integrationTest       # Docker required: isolated Kafka via Testcontainers
./gradlew check bootJar         # Both suites plus executable JAR
```

Tests cover the exact Python message template, strict v2 validation, UTF-16 limits,
HTTP failures without token disclosure, state compatibility, partial delivery across
process restarts, failed persistence and failed commits. Integration tests start the
real Spring listener against Kafka, verify committed offsets and ensure v1 stops
consumption before a subsequent v2 event. Telegram delivery is replaced in broker tests.

The build workflow runs both suites, builds/verifies the image, uploads reports and
image/source archives, then publishes branch/SHA images to GHCR on pushes (`latest`
only from the default branch). Production delivery is a long-running service; there is
no scheduled GitHub Actions consumer.

## Migration

This implementation replaces Python after the v2-format PR. Stop the Python consumer,
then start Kotlin with the **same consumer group, topic and state volume**; do not
reset offsets or delete state. Drain any older v1 backlog with an old v1-capable
consumer before the v2-only rollout, as described in the contract document. Do not run
Python and Kotlin simultaneously during handover. A rollback to the v2-only Python
consumer can use the same state file after Kotlin is stopped.
