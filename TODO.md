# TODO

- Replace Kafka `PLAINTEXT` transport with `SASL_SSL` for production.
  - Configure TLS certificates / CA trust.
  - Configure SASL authentication (prefer SCRAM).
  - Use separate credentials for producer and consumer.
  - Update Docker Compose, environment configuration and integration tests.
