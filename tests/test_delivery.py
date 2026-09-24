import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from notifier.main import process, load_state


def message(id="a"):
    m = Mock()
    m.error.return_value = None
    m.key.return_value = json.dumps(["allod",id], separators=(",", ":")).encode()
    m.value.return_value = json.dumps(dict(version=1, source="allod", id=id, title=id, url="https://www.allod.de/angebote", details="")).encode()
    return m


@patch.dict(os.environ, {"TELEGRAM_CHAT_IDS": "123"})
class DeliveryTests(unittest.TestCase):
    def test_two_chats_resume_after_partial_failure(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"TELEGRAM_CHAT_IDS": "123,456"}):
            path = Path(directory) / "sent.json"
            consumer = Mock()
            consumer.commit.return_value = []
            deliver = Mock(side_effect=[None, RuntimeError("unavailable")])
            with self.assertRaises(RuntimeError):
                process(message(), consumer, path, load_state(path), deliver)
            consumer.commit.assert_not_called()
            state = load_state(path)
            self.assertEqual(state["sent"], [])
            self.assertEqual(state["pending"], {'["allod","a"]': ["123"]})
            self.assertEqual([call.args[2] for call in deliver.call_args_list], ["123", "456"])

            retry = Mock()
            process(message(), consumer, path, state, retry)
            retry.assert_called_once()
            self.assertEqual(retry.call_args.args[2], "456")
            consumer.commit.assert_called_once()
            self.assertEqual(load_state(path)["pending"], {})
            process(message(), consumer, path, load_state(path), retry)
            retry.assert_called_once()

    def test_legacy_sent_state_is_not_resent_to_new_chat(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"TELEGRAM_CHAT_IDS": "123,456"}):
            path = Path(directory) / "sent.json"
            path.write_text(json.dumps({"version": 1, "sent": ['["allod","a"]']}))
            consumer = Mock()
            consumer.commit.return_value = []
            deliver = Mock()
            process(message(), consumer, path, load_state(path), deliver)
            deliver.assert_not_called()
            consumer.commit.assert_called_once()

    def test_invalid_pending_state_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sent.json"
            for pending in ([], {"key": "123"}, {"key": [123]}):
                with self.subTest(pending=pending):
                    path.write_text(json.dumps({"version": 1, "sent": [], "pending": pending}))
                    with self.assertRaises(ValueError):
                        load_state(path)

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

    def test_berlinhaus_delivery_and_deduplication(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            consumer = Mock(); consumer.commit.return_value = []
            send = Mock(); m = message('8172')
            event = json.loads(m.value())
            event.update(source='berlinhaus', url='https://www.berlinhaus.com/immobilie/flat/')
            m.value.return_value = json.dumps(event).encode()
            m.key.return_value = b'["berlinhaus","8172"]'
            process(m, consumer, path, load_state(path), send)
            process(m, consumer, path, load_state(path), send)
            send.assert_called_once()
            self.assertEqual(send.call_args.args[0], 'berlinhaus')
            self.assertEqual(consumer.commit.call_count, 2)

    def test_inberlinwohnen_delivery_and_deduplication(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            consumer = Mock(); consumer.commit.return_value = []
            deliver = Mock(); m = message('21896')
            event = json.loads(m.value())
            event.update(source='inberlinwohnen', details='WBS: erforderlich')
            m.value.return_value = json.dumps(event).encode()
            m.key.return_value = b'["inberlinwohnen","21896"]'
            process(m, consumer, path, load_state(path), deliver)
            process(m, consumer, path, load_state(path), deliver)
            deliver.assert_called_once()
            self.assertEqual(deliver.call_args.args[0], 'inberlinwohnen')

    def test_berlinovo_delivery_and_deduplication(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            consumer = Mock(); consumer.commit.return_value = []
            deliver = Mock(); m = message('21896')
            event = json.loads(m.value())
            event.update(source='berlinovo', details='WBS: erforderlich')
            m.value.return_value = json.dumps(event).encode()
            m.key.return_value = b'["berlinovo","21896"]'
            process(m, consumer, path, load_state(path), deliver)
            process(m, consumer, path, load_state(path), deliver)
            deliver.assert_called_once()
            self.assertEqual(deliver.call_args.args[0], 'berlinovo')

    def test_gewobag_delivery_and_deduplication(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            consumer = Mock(); consumer.commit.return_value = []
            deliver = Mock(); m = message('21896')
            event = json.loads(m.value())
            event.update(source='gewobag', details='WBS: erforderlich', url='https://www.gewobag.de/fuer-mietinteressentinnen/mietangebote/0100-01921-0101-0036/')
            m.value.return_value = json.dumps(event).encode()
            m.key.return_value = b'["gewobag","21896"]'
            process(m, consumer, path, load_state(path), deliver)
            process(m, consumer, path, load_state(path), deliver)
            deliver.assert_called_once()
            self.assertEqual(deliver.call_args.args[0], 'gewobag')
