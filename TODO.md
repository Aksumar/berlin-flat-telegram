rjvvb# TODO

- Automate transit route catalog updates from the VBB GTFS feed.
  - Replace the bundled bus/tram JSON files with an in-memory database for stops and routes.
  - Load and refresh route data automatically so updated bus and tram numbers do not require manual JSON generation or deployment.

- Replace Kafka `PLAINTEXT` transport with `SASL_SSL` for production.
  - Configure TLS certificates / CA trust.
  - Configure SASL authentication (prefer SCRAM).
  - Use separate credentials for producer and consumer.
  - Update Docker Compose, environment configuration and integration tests.
