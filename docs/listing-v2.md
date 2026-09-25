# Listing Kafka contract v2

New events use `version: 2`. The Kafka topic remains `berlin-flat-listings-v1`:
the topic name does not specify the payload version. The key remains compact JSON
`[source,id]`; IDs, baselines and Telegram delivery state must not be reset.

The Telegram consumer accepts **only v2**. A v1 event is rejected before delivery
or offset commit and stops that consumer run; it is not silently skipped.

For rollout, stop the old watcher and drain any v1 backlog/outbox with the old
consumer before switching both services to v2. Keep the same consumer group and
state volumes. Do not resume a v1 producer against the v2-only consumer. Payload
versions are independent of the delivery-state version and topic/group names.

## Fields

Every field shown in the example is present. Unknown values are `null`, never zero,
false or an empty string. `source`, `id`, `title`, `url` are strings; source identifies
the scraper, while `provider` is the housing company. Text from sources is preserved
in its original language. Address components are best-effort; `address.full` preserves
the available location even when a street or house number is not published.

`area_m2`, `rooms` and monetary amounts are finite nonnegative JSON numbers.
Rooms may be fractional. Prices are EUR per month, except `deposit` (one-time).
`cold` is Kaltmiete; `warm` is the explicitly displayed Warmmiete/Gesamtmiete.
Heating inclusion and price components depend on the source; unknown components
are not inferred by subtraction. `warm_from: true` preserves a displayed “ab” price;
false means an exact displayed total, null means unavailable. Allod's displayed total
is BasePrice + OperatingCost, exactly as in its frontend. InBerlinWohnen uses the
labelled Gesamtmiete, **not rentGross**, which can exclude heating.

`floor` is text (e.g. `0`, `EG`, `DG`, `1 von (insg. 4)`). Availability contains an
ISO date when parseable, and source text (e.g. `sofort`) otherwise. WBS and features
use nullable booleans: absent mention does not imply false. `wbs.text` preserves
published eligibility restrictions. Only explicit positive feature labels are extracted;
free prose is not treated as proof that a feature exists.

V2 excludes the old unstructured `details` field. Consumers may ignore additional
future fields but must reject invalid known field types before acknowledging Kafka.

## Example

```json
{
  "version": 2,
  "source": "gewobag",
  "id": "123",
  "title": "Wohnung",
  "url": "https://example.com/123",
  "address": {
    "full": "Musterstraße 12, 10115 Berlin",
    "street": "Musterstraße",
    "house_number": "12",
    "postal_code": "10115",
    "city": "Berlin",
    "district": "Mitte"
  },
  "area_m2": 64.5,
  "rooms": 2,
  "floor": "3",
  "rent": {
    "currency": "EUR",
    "cold": 650,
    "warm": 890,
    "operating_costs": 160,
    "heating_costs": 80,
    "deposit": 1950,
    "warm_from": false
  },
  "availability": {
    "date": "2026-11-01",
    "text": "01.11.2026"
  },
  "wbs": {
    "required": false,
    "text": null
  },
  "features": {
    "balcony": true,
    "elevator": null,
    "built_in_kitchen": null
  },
  "provider": "Gewobag"
}
```

## Telegram layout

One house emoji in the heading. Address, area, rooms, Warmmiete and Kaltmiete always
appear (`не указано` for unknown values). Other unknown fields are omitted. Company,
source and the full listing URL end the message. Long messages are truncated within
Telegram's 4096 UTF-16-unit limit while retaining the URL. There is no legacy
v1 rendering path.

## Source coverage

- Berlinhaus: location, area, rooms and labelled rent from result cards.
- Berlinovo: location, area if provided, rooms, floor, total rent, availability and WBS.
- Gewobag: address, district, area, rooms, “ab” total, availability, WBS and explicit features.
- InBerlinWohnen: structured address, provider, area, rooms and labelled details.
- Allod: rendered card plus the publication response used by the page itself.
- RBB: new listings are enriched from their detail table before publishing. Existing
  listings are not fetched again. A detail-page failure leaves the listing unseen for retry.

Missing optional information does not suppress a listing. No live Telegram messages
are needed for tests; delivery is mocked, including Kafka integration tests.
