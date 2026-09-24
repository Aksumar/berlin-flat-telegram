import argparse
import json
import logging
import os
from pathlib import Path
from .model import Listing
from .telegram import send

def save(path, data):
    content = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if path.exists() and path.read_text() == content:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with tmp.open("w") as f:
        f.write(content)
        f.flush()
        os.fsync(f.fileno())
    tmp.replace(path)


def load_state(path):
    state = json.loads(path.read_text()) if path.exists() else {"version": 1, "sent": []}
    if state.get("version") != 1 or not isinstance(state.get("sent"), list) or not all(isinstance(x, str) for x in state["sent"]):
        raise ValueError("Invalid delivery state")
    return state


def process(message, consumer, state_path, state, deliver=send):
    if message.error():
        raise RuntimeError("Kafka consumer error")
    event = json.loads(message.value())
    if event.get("version") != 1 or not all(isinstance(event.get(k), str) for k in ("source", "id", "title", "url", "details")):
        raise ValueError("Invalid Kafka event")
    key = json.dumps([event["source"], event["id"]], separators=(",", ":"))
    if event["source"] not in ("allod", "rbb", "berlinhaus", "inberlinwohnen") or message.key() != key.encode():
        raise ValueError("Invalid Kafka event identity")
    if key not in state["sent"]:
        deliver(event["source"], Listing(**{k: event[k] for k in ("id", "title", "url", "details")}))
        state["sent"].append(key)
        save(state_path, state)
    committed = consumer.commit(message=message, asynchronous=False)
    if any(getattr(partition, "error", None) for partition in (committed or [])):
        raise RuntimeError("Kafka offset commit failed")


def run(state_path, duration=120, consumer=None, deliver=send):
    import time
    from .kafka_config import config, topic
    state = load_state(state_path)
    if consumer is None:
        from confluent_kafka import Consumer
        consumer = Consumer({**config(), "group.id": os.environ.get("KAFKA_GROUP_ID", "berlin-flat-telegram-v1"),
                             "enable.auto.commit": False, "enable.auto.offset.store": False,
                             "auto.offset.reset": "earliest"})
    deadline = time.monotonic() + duration if duration else None
    try:
        consumer.subscribe([topic()])
        # Fail on unreachable cluster instead of reporting an empty successful run.
        metadata = consumer.list_topics(topic=topic(), timeout=30)
        if topic() not in metadata.topics or metadata.topics[topic()].error:
            raise RuntimeError("Kafka topic unavailable")
        while deadline is None or time.monotonic() < deadline:
            message = consumer.poll(1.0)
            if message is not None:
                process(message, consumer, state_path, state, deliver)
    finally:
        consumer.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=120, help="Seconds to consume; 0 runs continuously")
    parser.add_argument("--state", type=Path, default=Path("state/sent.json"))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    try:
        if args.duration < 0:
            raise ValueError("Duration must be nonnegative")
        run(args.state, args.duration)
    except Exception as exc:
        logging.error("Delivery failed (%s); retained progress. Check secrets, queue and connectivity.", type(exc).__name__)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
