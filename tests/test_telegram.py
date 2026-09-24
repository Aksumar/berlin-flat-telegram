import io
import json
import os
import unittest
from unittest.mock import patch

from notifier.model import Listing
from notifier.telegram import chat_ids, send


class TelegramTests(unittest.TestCase):
    def test_chat_configuration(self):
        cases = [
            ({"TELEGRAM_CHAT_IDS": " 123, -100456,123,, ", "TELEGRAM_CHAT_ID": "789"}, ["123", "-100456"]),
            ({"TELEGRAM_CHAT_ID": "123"}, ["123"]),
            ({"TELEGRAM_CHAT_IDS": " ", "TELEGRAM_CHAT_ID": "123"}, ["123"]),
        ]
        for environment, expected in cases:
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                self.assertEqual(chat_ids(), expected)

    def test_missing_chats_are_rejected(self):
        for configured in ("", " , , "):
            with self.subTest(configured=configured), patch.dict(os.environ, {"TELEGRAM_CHAT_IDS": configured}, clear=True):
                with self.assertRaises(RuntimeError):
                    chat_ids()

    @patch.dict(os.environ, {"TELEGRAM_BOT_TOKEN": "test-token"}, clear=True)
    def test_send_uses_explicit_destination(self):
        with patch("notifier.telegram.urlopen", return_value=io.BytesIO(b'{"ok": true}')) as request:
            send("allod", Listing("a", "Flat", "https://example.com/flat"), "-100456")
        payload = json.loads(request.call_args.args[0].data)
        self.assertEqual(payload["chat_id"], "-100456")
        self.assertIn("https://example.com/flat", payload["text"])

    @patch.dict(os.environ, {"TELEGRAM_BOT_TOKEN": "test-token"}, clear=True)
    def test_failure_does_not_expose_token(self):
        with patch("notifier.telegram.urlopen", side_effect=OSError("URL includes test-token")):
            with self.assertRaises(RuntimeError) as error:
                send("allod", Listing("a", "Flat", "https://example.com/flat"), "123")
        self.assertNotIn("test-token", str(error.exception))
