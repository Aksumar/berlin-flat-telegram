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


def run(queue_path, state_path, deliver=send):
    queue = json.loads(queue_path.read_text())
    if queue.get("version") != 1 or not isinstance(queue.get("outbox"), dict):
        raise ValueError("Invalid producer outbox; run the updated watcher first")
    state = json.loads(state_path.read_text()) if state_path.exists() else {"version": 1, "sent": []}
    if state.get("version") != 1 or not isinstance(state.get("sent"), list) or not all(isinstance(x, str) for x in state["sent"]):
        raise ValueError("Invalid delivery state")
    events = []
    for key, event in queue["outbox"].items():
        if not isinstance(event, dict) or not all(isinstance(event.get(k), str) for k in ("source", "id", "title", "url", "details")):
            raise ValueError("Invalid event")
        if event["source"] not in ("allod", "rbb") or key != json.dumps([event["source"], event["id"]], separators=(",", ":")):
            raise ValueError("Invalid event identity")
        events.append((key, event))
    sent = set(state["sent"])
    for key, event in events:
        if key in sent:
            continue
        listing = Listing(**{k: event[k] for k in ("id", "title", "url", "details")})
        deliver(event["source"], listing)
        sent.add(key)
        state["sent"] = sorted(sent)
        save(state_path, state)
    save(state_path, state)
    logging.info("Queue: %d; delivered total: %d", len(events), len(sent))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--state", type=Path, default=Path("state/sent.json"))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    try:
        run(args.queue, args.state)
    except Exception as exc:
        logging.error("Delivery failed (%s); retained progress. Check secrets, queue and connectivity.", type(exc).__name__)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
