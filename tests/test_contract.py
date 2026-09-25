import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from notifier.contract import decode
from notifier.main import process, load_state
from notifier.telegram import format_message

FIXTURE = Path(__file__).parent / 'fixtures/listing-v2.json'


class ContractTests(unittest.TestCase):
    def event(self):
        return json.loads(FIXTURE.read_text())

    def test_render_contract(self):
        text = format_message('gewobag', decode(self.event()))
        self.assertIn('Адрес: Musterstraße 12, 10115 Berlin', text)
        self.assertIn('Площадь: 64,5 м²\nКомнат: 2\nЭтаж: 3', text)
        self.assertIn('Warmmiete: 890,00 €/мес.', text)
        self.assertIn('WBS: не требуется', text)
        self.assertIn('Балкон: есть', text)
        self.assertNotIn('Лифт:', text)
        self.assertEqual(text.count('🏠'), 1)
        self.assertTrue(text.endswith('https://example.com/123'))

    def test_nulls_from_price_and_raw_details_not_rendered(self):
        event = self.event()
        event['details'] = 'THIS MUST NOT BE RENDERED'
        event['area_m2'] = event['rooms'] = None
        event['rent']['cold'] = None
        event['rent']['warm_from'] = True
        event['wbs'] = dict(required=None, text=None)
        text = format_message('gewobag', decode(event))
        self.assertIn('Площадь: не указано', text)
        self.assertIn('Комнат: не указано', text)
        self.assertIn('Kaltmiete: не указано', text)
        self.assertIn('Warmmiete: от 890,00', text)
        self.assertNotIn('WBS:', text)
        self.assertNotIn(event['details'], text)

    def test_reject_invalid_payloads(self):
        bad = [None, [], dict(version=True)]
        for key, value in [('rooms', True), ('area_m2', -1), ('area_m2', float('nan')),
                           ('rent', {}), ('address', 'bad'), ('floor', 3)]:
            event = self.event(); event[key] = value; bad.append(event)
        event = self.event(); event['availability']['date'] = '2026-99-01'; bad.append(event)
        event = self.event(); event['wbs']['required'] = 'false'; bad.append(event)
        for event in bad:
            with self.subTest(event=event), self.assertRaises(ValueError):
                decode(event)

    def test_utf16_limit_preserves_url(self):
        event = self.event()
        event['address']['full'] = '🏠' * 5000
        text = format_message('gewobag', decode(event))
        self.assertLessEqual(len(text.encode('utf-16-le')) // 2, 4096)
        self.assertTrue(text.endswith(event['url']))

    @patch.dict(os.environ, {'TELEGRAM_CHAT_IDS': '123,456'})
    def test_v2_retry_and_dedup(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            event = self.event()
            message = Mock(); message.error.return_value = None
            message.key.return_value = b'["gewobag","123"]'
            message.value.return_value = json.dumps(event).encode()
            consumer = Mock(); consumer.commit.return_value = []
            deliver = Mock(side_effect=[None, RuntimeError('failed')])
            with self.assertRaises(RuntimeError):
                process(message, consumer, path, load_state(path), deliver)
            consumer.commit.assert_not_called()
            retry = Mock()
            process(message, consumer, path, load_state(path), retry)
            self.assertEqual(retry.call_args.args[2], '456')
            process(message, consumer, path, load_state(path), retry)
            retry.assert_called_once()

    @patch.dict(os.environ, {'TELEGRAM_CHAT_IDS': '123'})
    def test_invalid_v2_not_committed(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'sent.json'
            event = self.event(); event['rooms'] = '2'
            message = Mock(); message.error.return_value = None
            message.value.return_value = json.dumps(event).encode()
            consumer = Mock(); deliver = Mock()
            with self.assertRaises(ValueError):
                process(message, consumer, path, load_state(path), deliver)
            consumer.commit.assert_not_called(); deliver.assert_not_called()
            self.assertFalse(path.exists())

    @patch.dict(os.environ, {'TELEGRAM_CHAT_IDS': '123'})
    def test_v1_rejected_without_delivery_or_commit(self):
        legacy = dict(version=1, source='gewobag', id='123', title='Old',
                      url='https://example.com/123', details='Old format')
        mislabeled = self.event(); mislabeled['version'] = 1
        for event in (legacy, mislabeled):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as d:
                path = Path(d) / 'sent.json'
                message = Mock(); message.error.return_value = None
                message.key.return_value = b'["gewobag","123"]'
                message.value.return_value = json.dumps(event).encode()
                consumer = Mock(); deliver = Mock()
                with self.assertRaisesRegex(ValueError, 'Invalid Kafka version'):
                    process(message, consumer, path, load_state(path), deliver)
                consumer.commit.assert_not_called()
                deliver.assert_not_called()
                self.assertFalse(path.exists())
