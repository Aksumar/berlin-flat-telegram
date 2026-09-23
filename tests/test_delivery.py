import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from notifier.main import run


class DeliveryTests(unittest.TestCase):
    def test_retry_partial_progress_and_no_duplicates(self):
        with tempfile.TemporaryDirectory() as d:
            queue, state = Path(d)/'queue.json', Path(d)/'sent.json'
            events = {json.dumps(['allod', i], separators=(',', ':')): dict(source='allod', id=i, title=i, url='https://www.allod.de/angebote', details='') for i in ['a', 'b']}
            queue.write_text(json.dumps(dict(version=1, outbox=events)))
            send = Mock(side_effect=[None, RuntimeError('failed')])
            with self.assertRaises(RuntimeError):
                run(queue, state, send)
            self.assertEqual(len(json.loads(state.read_text())['sent']), 1)
            send = Mock()
            run(queue, state, send)
            self.assertEqual(send.call_count, 1)
            stamp = state.stat().st_mtime_ns
            run(queue, state, send)
            self.assertEqual(send.call_count, 1)
            self.assertEqual(state.stat().st_mtime_ns, stamp)

    def test_invalid_queue_and_corrupt_state_do_not_send(self):
        with tempfile.TemporaryDirectory() as d:
            queue, state = Path(d)/'queue.json', Path(d)/'sent.json'
            send = Mock()
            queue.write_text('{"version":1}')
            with self.assertRaises(ValueError):
                run(queue, state, send)
            queue.write_text('{"version":1,"outbox":{}}')
            state.write_text('{"version":2,"sent":[]}')
            with self.assertRaises(ValueError):
                run(queue, state, send)
            send.assert_not_called()

    def test_empty_queue_needs_no_telegram(self):
        with tempfile.TemporaryDirectory() as d:
            queue, state = Path(d)/'queue.json', Path(d)/'sent.json'
            queue.write_text('{"version":1,"outbox":{}}')
            send = Mock()
            run(queue, state, send)
            send.assert_not_called()
            self.assertEqual(json.loads(state.read_text())['sent'], [])
