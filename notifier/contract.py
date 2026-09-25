"""Versioned Kafka decoding. Reject malformed events before delivery/commit."""
import math
from datetime import date
from .model import Listing

STRINGS = ('id', 'title', 'url')
GROUPS = {
    'address': dict.fromkeys(('full', 'street', 'house_number', 'postal_code', 'city', 'district'), 'string'),
    'rent': {**dict.fromkeys(('cold', 'warm', 'operating_costs', 'heating_costs', 'deposit'), 'number'),
             'currency': 'string', 'warm_from': 'bool'},
    'availability': {'date': 'string', 'text': 'string'},
    'wbs': {'required': 'bool', 'text': 'string'},
    'features': dict.fromkeys(('balcony', 'elevator', 'built_in_kitchen'), 'bool'),
}


def valid(value, kind):
    if value is None:
        return True
    if kind == 'string':
        return isinstance(value, str)
    if kind == 'bool':
        return type(value) is bool
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def decode(event):
    if not isinstance(event, dict) or type(event.get('version')) is not int or event['version'] not in (1, 2):
        raise ValueError('Invalid Kafka version')
    if not all(isinstance(event.get(k), str) for k in (*STRINGS, 'source')):
        raise ValueError('Invalid Kafka identity')
    if event['version'] == 1:
        if not isinstance(event.get('details'), str):
            raise ValueError('Invalid legacy details')
        return Listing(**{k: event[k] for k in (*STRINGS, 'details')})
    for group, fields in GROUPS.items():
        values = event.get(group)
        if not isinstance(values, dict) or any(k not in values or not valid(values[k], kind) for k, kind in fields.items()):
            raise ValueError('Invalid Kafka ' + group)
    for key, kind in [('area_m2', 'number'), ('rooms', 'number'), ('floor', 'string'), ('provider', 'string')]:
        if key not in event or not valid(event[key], kind):
            raise ValueError('Invalid Kafka ' + key)
    if event['rent']['currency'] != 'EUR':
        raise ValueError('Unsupported currency')
    if event['availability']['date'] is not None:
        try:
            date.fromisoformat(event['availability']['date'])
        except ValueError:
            raise ValueError('Invalid availability date') from None
    return Listing(**{k: event[k] for k in (*STRINGS, *GROUPS, 'area_m2', 'rooms', 'floor', 'provider')})
