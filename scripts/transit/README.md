# Bus routes on listing maps

`berlin_bus_routes.json` is derived from the [official VBB GTFS feed](https://unternehmen.vbb.de/digitale-services/datensaetze/).
Source: Verkehrsverbund Berlin-Brandenburg GmbH (VBB), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
The transformation selects Berlin bus platforms and their scheduled routes; maps credit VBB separately from the base map providers.

Update from the repository root (Python 3, standard library only):

```sh
curl -fL --retry 2 https://unternehmen.vbb.de/gtfs -o /tmp/vbb-bus-gtfs.zip
python3 scripts/transit/build_bus_routes.py /tmp/vbb-bus-gtfs.zip
python3 -m unittest discover -s scripts/transit -p 'test_*.py'
./gradlew test
```

Commit the generated resource and rebuild/redeploy the application to use it. Refresh weekly or when route changes are known; this command is explicit and is not run during application startup or map generation.

The generator includes routes with at least one scheduled trip in the next seven calendar days, including weekend and night services. It honors both weekly calendars and added/removed service dates, excludes expired services and platforms with neither pickup nor drop-off, and supports GTFS bus types 3 and 700–716. It streams stop times without extracting the archive. An empty result fails without replacing the previous resource. `--date YYYY-MM-DD` selects a reproducible schedule window; `--output PATH` changes the destination. Metadata includes generation time, the schedule window and source archive SHA-256.

These are scheduled routes for a week, not departures or a guarantee of service today. Routes remain visible after the recorded window; the app logs a warning and uses the latest bundled snapshot until it is updated. A missing/broken catalog leaves the existing B/name label intact.

Matching requires a normalized name (including `Str.`/`Straße`) and a platform within 180 m. Platforms with the same GTFS parent are one stop. Without a parent, same-name platforms are grouped only when each is within 180 m of every other group member, avoiding chains of distant stops. A match to multiple groups is rejected. All routes of the matched group are shown once, across both directions. Unmatched map points are merged only when their names match and they are within 180 m; distant namesakes remain separate.

Numbers wrap to fit the label. Numeric routes use natural order, then M/X routes, then N routes. S/U row layout and the existing rail → tram → bus selection priority remain unchanged.

Visual example (requires `GEOAPIFY_API_KEY`):

```sh
./gradlew mapExamples --tests '*BusMapExample'
```

The example is centered in Gartenheimsiedlung Grenzland near the six stops from the reference image; its center is illustrative. Output: `build/map-diagnostics/bus-routes-mariendorf.png`.
