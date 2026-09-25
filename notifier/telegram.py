import json
import os
from urllib.request import Request, urlopen


def chat_ids():
    configured = os.environ.get("TELEGRAM_CHAT_IDS", "").strip() or os.environ.get("TELEGRAM_CHAT_ID", "")
    chats = list(dict.fromkeys(chat.strip() for chat in configured.split(",") if chat.strip()))
    if not chats:
        raise RuntimeError("Set TELEGRAM_CHAT_IDS or TELEGRAM_CHAT_ID")
    return chats


def format_message(source, listing):
    def text(value):
        return ' '.join(str(value).split()) if value is not None else 'не указано'
    def number(value):
        return f'{value:g}'.replace('.', ',') if value is not None else 'не указано'
    def price(value, starting=False):
        return (('от ' if starting else '') + f'{value:,.2f}'.replace(',', ' ').replace('.', ',') + ' €') if value is not None else 'не указано'
    address = listing.address
    location = address['full'] or ', '.join(filter(None, [
        ' '.join(filter(None, [address['street'], address['house_number']])),
        ' '.join(filter(None, [address['postal_code'], address['city']]))])) or None
    heading = '🏠 Новая квартира'
    if address['district']:
        heading += ' · ' + text(address['district'])
    lines = [heading, '', 'Адрес: ' + text(location),
             'Площадь: ' + number(listing.area_m2) + (' м²' if listing.area_m2 is not None else ''),
             'Комнат: ' + number(listing.rooms)]
    if listing.floor is not None:
        lines.append('Этаж: ' + text(listing.floor))
    lines += ['', 'Warmmiete: ' + price(listing.rent['warm'], listing.rent['warm_from']) + ('/мес.' if listing.rent['warm'] is not None else ''),
              'Kaltmiete: ' + price(listing.rent['cold']) + ('/мес.' if listing.rent['cold'] is not None else '')]
    for key, label in [('operating_costs', 'Коммунальные'), ('heating_costs', 'Отопление'), ('deposit', 'Залог')]:
        if listing.rent[key] is not None:
            lines.append(label + ': ' + price(listing.rent[key]))
    extra = []
    available = listing.availability['text'] or listing.availability['date']
    if available:
        extra.append('Доступна: ' + text(available))
    wbs = listing.wbs
    if wbs['required'] is not None or wbs['text']:
        status = {True: 'требуется', False: 'не требуется', None: 'не указано'}[wbs['required']]
        extra.append('WBS: ' + status + (' (' + text(wbs['text']) + ')' if wbs['text'] else ''))
    for key, label in [('balcony', 'Балкон'), ('elevator', 'Лифт'), ('built_in_kitchen', 'Встроенная кухня')]:
        if listing.features[key] is not None:
            extra.append(label + ': ' + ('есть' if listing.features[key] else 'нет'))
    if extra:
        lines += ['', *extra]
    # Legacy queue remains readable; v2 never passes raw details to the renderer.
    if listing.details:
        lines += ['', text(listing.title), listing.details]
    lines.append('')
    if listing.provider:
        lines.append('Компания: ' + text(listing.provider))
    source_name = {'allod': 'Allod', 'rbb': 'RBB', 'berlinhaus': 'Berlinhaus',
                   'berlinovo': 'Berlinovo', 'gewobag': 'Gewobag', 'inberlinwohnen': 'InBerlinWohnen'}.get(source, source)
    lines.append('Источник: ' + text(source_name))
    body = '\n'.join(lines)
    # Telegram counts UTF-16 units. Keep the complete link and bound the body.
    url = listing.url
    units = lambda value: len(value.encode('utf-16-le')) // 2
    budget = 4096 - units(url) - 1
    if budget < 1:
        raise ValueError('Listing URL exceeds Telegram limit')
    if units(body) > budget:
        body = body.encode('utf-16-le')[:max(0, budget - 1) * 2].decode('utf-16-le', errors='ignore') + '…'
    return body + '\n' + url


def send(source, listing, chat):
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if not token or not chat:
        raise RuntimeError("Set TELEGRAM_BOT_TOKEN and a destination chat")
    text = format_message(source, listing)
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
