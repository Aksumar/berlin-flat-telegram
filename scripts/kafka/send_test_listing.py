#!/usr/bin/env python3
"""Publish one listing using the Kafka CLI in an existing Docker container."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, default=directory / "test-listing.json")
    parser.add_argument("--topic", default=os.getenv("KAFKA_TOPIC", "berlin-flat-listings-v1"))
    parser.add_argument(
        "--bootstrap-server",
        default=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092"),
        help="Broker address reachable FROM the Kafka container (default: kafka:19092)",
    )
    parser.add_argument("--container", help="Existing Kafka container name; overrides Compose")
    parser.add_argument("--compose-file", type=Path, default=directory.parents[1] / "compose.test.yaml")
    parser.add_argument("--service", default="kafka", help="Compose service (default: kafka)")
    parser.add_argument("--project-name", help="Optional Compose project name")
    parser.add_argument("--dry-run", action="store_true", help="Print key and payload without sending")
    args = parser.parse_args()

    try:
        payload = json.loads(args.file.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 2:
            raise ValueError("Expected a listing object with version: 2")
        if any(not isinstance(payload.get(field), str) or not payload[field].strip() for field in ("source", "id")):
            raise ValueError("source and id must be nonempty strings")
        key = json.dumps([payload["source"], payload["id"]], ensure_ascii=False, separators=(",", ":"))
        value = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    # One physical line; tabs/newlines within JSON strings remain escaped.
    record = f"{key}\t{value}\n"
    if args.dry_run:
        print(record, end="")
        return 0

    if args.container:
        command = ["docker", "exec", "-i", args.container]
    else:
        command = ["docker", "compose", "-f", str(args.compose_file)]
        if args.project_name:
            command += ["--project-name", args.project_name]
        command += ["exec", "-T", args.service]
    command += [
        "/opt/kafka/bin/kafka-console-producer.sh",
        "--bootstrap-server", args.bootstrap_server,
        "--topic", args.topic,
        "--property", "parse.key=true",
        "--property", "key.separator=\t",
        "--producer-property", "acks=all",
        "--producer-property", "max.block.ms=10000",
        "--producer-property", "delivery.timeout.ms=15000",
        "--producer-property", "request.timeout.ms=10000",
        "--sync",
    ]
    try:
        subprocess.run(command, input=record, encoding="utf-8", check=True, timeout=45)
    except (OSError, subprocess.SubprocessError) as error:
        print(f"Send failed: {error}", file=sys.stderr)
        return 1
    print(f"Sent one listing to {args.topic}; key={key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
