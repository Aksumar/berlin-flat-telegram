# Listing Kafka contract v2

The payload contract is `version: 2`. The Kafka topic is `berlin-flat-listings-v1`;
the topic name is independent of the payload version. The key is compact JSON
`[source,id]`.

The Telegram consumer accepts only contract version 2. Any other version is treated
as an invalid record.

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
are not inferred by subtraction. Allod's displayed total
is BasePrice + OperatingCost, exactly as in its frontend. InBerlinWohnen uses the
labelled Gesamtmiete, **not rentGross**, which can exclude heating.

`floor` is text (e.g. `0`, `EG`, `DG`, `1 von (insg. 4)`). Availability contains an
ISO date when parseable, and source text (e.g. `sofort`) otherwise. WBS and features
use nullable booleans: absent mention does not imply false. `wbs.text` preserves
published eligibility restrictions. Only explicit positive feature labels are extracted;
free prose is not treated as proof that a feature exists.

The contract does not include an unstructured `details` field. Consumers may ignore additional
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
    "deposit": 1950
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

One house emoji, source website and optional district in the heading; no “Новая квартира” label. Address, area, rooms, Warmmiete and Kaltmiete always
appear (`не указано` for unknown values). Other unknown fields are omitted. The company appears only when it differs from the source. The full listing URL ends the message. Long messages are truncated within
Telegram's 4096 UTF-16-unit limit while retaining the URL. With `GEOAPIFY_API_KEY`,
a map with a Berlin overview accompanies the listing; approximate matches are labelled.
Captions exceeding 1024 units use a heading on the photo and a separate quiet text message.
Missing or failed map enrichment does not suppress delivery or the listing button.
Both buttons share one row and their links are built from the listing independently of image generation.
The map button always opens an address search; it is omitted only when no address is available.
Photo delivery failure, including exhausted retries,
falls back to text with both buttons. Transient text delivery failures remain uncommitted.

## Source coverage

- Degewo: address, area, rooms, Warmmiete, availability, explicit WBS and features.

- WBM: address, district, area, rooms, Warmmiete, explicit WBS and features.

- Berlinhaus: location, area, rooms and labelled rent from result cards.
- Berlinovo: location, area if provided, rooms, floor, total rent, availability and WBS.
- Gewobag: address, district, area, rooms, “ab” total, availability, WBS and explicit features.
- InBerlinWohnen: structured address, provider, area, rooms and labelled details.
- Allod: rendered card plus the publication response used by the page itself.
- RBB: new listings are enriched from their detail table before publishing. Existing
  listings are not fetched again. A detail-page failure leaves the listing unseen for retry.

Missing optional information does not suppress a listing. No live Telegram messages
are needed for tests; delivery is mocked, including Kafka integration tests.
