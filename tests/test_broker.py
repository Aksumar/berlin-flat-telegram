import json
import os
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, patch

@unittest.skipUnless(os.environ.get("RUN_KAFKA_INTEGRATION"), "requires Kafka")
@patch.dict(os.environ, {"TELEGRAM_CHAT_IDS": "123"})
class BrokerTest(unittest.TestCase):
    def test_delivery(self):
        from confluent_kafka import Producer, Consumer
        servers = os.environ["KAFKA_BOOTSTRAP_SERVERS"]
        topic = os.environ["KAFKA_TOPIC"]
        id = str(uuid.uuid4())
        key = json.dumps(["allod", id], separators=(",", ":"))
        event = json.loads((Path(__file__).parent / "fixtures/listing-v2.json").read_text())
        event.update(source="allod", id=id)
        producer = Producer({"bootstrap.servers":servers})
        producer.produce(topic, key=key.encode(), value=json.dumps(event).encode())
        self.assertEqual(producer.flush(30), 0)
        group = "test-" + id
        config = {"bootstrap.servers":servers, "group.id":group, "auto.offset.reset":"earliest", "enable.auto.commit":False}
        client = Consumer(config)
        client.subscribe([topic])
        deadline = time.monotonic()+45
        found = None
        try:
            while time.monotonic()<deadline:
                msg=client.poll(1)
                if msg is not None and not msg.error() and msg.key()==key.encode():
                    found=msg
                    break
            self.assertIsNotNone(found)
            from notifier.main import process, load_state
            from confluent_kafka import TopicPartition
            with tempfile.TemporaryDirectory() as d:
                path=Path(d)/"sent.json"; send=Mock(side_effect=RuntimeError())
                with self.assertRaises(RuntimeError):
                    process(found, client, path, load_state(path), send)
                partition=TopicPartition(found.topic(), found.partition())
                self.assertLess(client.committed([partition], timeout=10)[0].offset, 0)
                send.side_effect=None
                process(found, client, path, load_state(path), send)
                self.assertEqual(client.committed([partition], timeout=10)[0].offset, found.offset()+1)
        finally:
            client.close()
