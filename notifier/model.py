from dataclasses import dataclass, field


def address_fields():
    return dict.fromkeys(('full', 'street', 'house_number', 'postal_code', 'city', 'district'))


def rent_fields():
    return dict(currency='EUR', cold=None, warm=None, operating_costs=None,
                heating_costs=None, deposit=None, warm_from=None)


@dataclass(frozen=True)
class Listing:
    id: str
    title: str
    url: str
    details: str = ""  # Legacy/debug text; never rendered for v2 events.
    address: dict = field(default_factory=address_fields)
    area_m2: float | None = None
    rooms: float | None = None
    floor: str | None = None
    rent: dict = field(default_factory=rent_fields)
    availability: dict = field(default_factory=lambda: dict(date=None, text=None))
    wbs: dict = field(default_factory=lambda: dict(required=None, text=None))
    features: dict = field(default_factory=lambda: dict(balcony=None, elevator=None, built_in_kitchen=None))
    provider: str | None = None
