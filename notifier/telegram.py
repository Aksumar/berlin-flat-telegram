import json
import os
from urllib.request import Request, urlopen


def chat_ids():
    configured = os.environ.get("TELEGRAM_CHAT_IDS", "").strip() or os.environ.get("TELEGRAM_CHAT_ID", "")
    chats = list(dict.fromkeys(chat.strip() for chat in configured.split(",") if chat.strip()))
    if not chats:
        raise RuntimeError("Set TELEGRAM_CHAT_IDS or TELEGRAM_CHAT_ID")
    return chats


def send(source, listing, chat):
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if not token or not chat:
        raise RuntimeError("Set TELEGRAM_BOT_TOKEN and a destination chat")
    text = f"🏠 {source.upper()}: новое объявление\n{listing.title}\n{listing.details}"[:3400]
    text += f"\n{listing.url}"
    request = Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=json.dumps({"chat_id": chat, "text": text, "link_preview_options": {"is_disabled": True}}).encode(),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
        if result.get("ok") is not True:
            raise RuntimeError("Telegram rejected message")
    except Exception:
        # urllib errors can contain the URL, hence the bot token. Never log them.
        raise RuntimeError("Telegram delivery failed; listing remains pending") from None
