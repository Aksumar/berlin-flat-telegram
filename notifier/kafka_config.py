"""Shared environment contract; keep identical in producer and consumer repositories."""
import os


def config():
    servers = os.environ.get("KAFKA_BOOTSTRAP_SERVERS")
    if not servers:
        raise ValueError("KAFKA_BOOTSTRAP_SERVERS is required")
    protocol = os.environ.get("KAFKA_SECURITY_PROTOCOL", "SASL_SSL")
    if protocol not in {"SASL_SSL", "SSL", "PLAINTEXT"}:
        raise ValueError("Unsupported Kafka security protocol")
    result = {"bootstrap.servers": servers, "security.protocol": protocol}
    if protocol == "SASL_SSL":
        for env, key in (("KAFKA_SASL_USERNAME", "sasl.username"), ("KAFKA_SASL_PASSWORD", "sasl.password")):
            value = os.environ.get(env)
            if not value:
                raise ValueError(f"{env} is required")
            result[key] = value
        result["sasl.mechanism"] = os.environ.get("KAFKA_SASL_MECHANISM", "PLAIN")
    if os.environ.get("KAFKA_SSL_CA_LOCATION"):
        result["ssl.ca.location"] = os.environ["KAFKA_SSL_CA_LOCATION"]
    return result


def topic():
    return os.environ.get("KAFKA_TOPIC", "berlin-flat-listings-v1")
