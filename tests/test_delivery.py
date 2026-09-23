import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from notifier.main import process, load_state


def message(id="a"):
    m = Mock()
    m.error.return_value = None
    m.key.return_value = json.dumps(["allod",id], separators=(",", ":")).encode()
    m.value.return_value = json.dumps(dict(version=1, source="allod", id=id, title=id, url="https://www.allod.de/angebote", details="")).encode()
    return m


class DeliveryTests(unittest.TestCase):
    def test_failure_never_commits_and_retry_deduplicates(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d)/"sent.json"; state = load_state(path)
            consumer = Mock(); consumer.commit.return_value = []
            send = Mock(side_effect=RuntimeError())
            with self.assertRaises(RuntimeError):
                process(message(), consumer, path, state, send)
            consumer.commit.assert_not_called()
            self.assertFalse(path.exists())
            send.side_effect = None
            process(message(), consumer, path, state, send)
            self.assertTrue(path.exists())
            stamp = path.stat().st_mtime_ns
            process(message(), consumer, path, load_state(path), send)
            self.assertEqual(send.call_count, 2)
            self.assertEqual(path.stat().st_mtime_ns, stamp)
            self.assertEqual(consumer.commit.call_count, 2)

    def test_invalid_event_and_broker_error_not_acknowledged(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"sent.json"; consumer=Mock(); send=Mock(); m=message()
            m.key.return_value=b"wrong"
            with self.assertRaises(ValueError):
                process(m, consumer, path, load_state(path), send)
            m.error.return_value=RuntimeError()
            with self.assertRaises(RuntimeError):
                process(m, consumer, path, load_state(path), send)
            consumer.commit.assert_not_called(); send.assert_not_called()

    def test_commit_failure_keeps_dedup_state(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"sent.json"; consumer=Mock(); send=Mock()
            consumer.commit.side_effect=RuntimeError()
            with self.assertRaises(RuntimeError):
                process(message(), consumer, path, load_state(path), send)
            self.assertEqual(len(load_state(path)["sent"]), 1)
