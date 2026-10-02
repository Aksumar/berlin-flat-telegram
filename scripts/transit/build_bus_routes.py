#!/usr/bin/env python3
"""Build a small, dated Berlin bus or tram catalog from the official VBB GTFS ZIP."""
import argparse
import csv
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import zipfile

SOURCE = 'https://unternehmen.vbb.de/digitale-services/datensaetze/'
WEEKDAYS = ('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')


def rows(archive, name):
    if name not in archive.namelist():
        return
    with archive.open(name) as stream:
        yield from csv.DictReader(io.TextIOWrapper(stream, encoding='utf-8-sig'))


def active_services(archive, start, end):
    days = [start + timedelta(days=i) for i in range((end - start).days + 1)]
    active = defaultdict(set)
    for row in rows(archive, 'calendar.txt'):
        for day in days:
            if row['start_date'] <= day.strftime('%Y%m%d') <= row['end_date'] and row[WEEKDAYS[day.weekday()]] == '1':
                active[row['service_id']].add(day.strftime('%Y%m%d'))
    for row in rows(archive, 'calendar_dates.txt'):
        if start.strftime('%Y%m%d') <= row['date'] <= end.strftime('%Y%m%d'):
            if row['exception_type'] == '1':
                active[row['service_id']].add(row['date'])
            elif row['exception_type'] == '2':
                active[row['service_id']].discard(row['date'])
    return {service for service, dates in active.items() if dates}


def build_catalog(path, start, days=7, mode="bus"):
    route_types = {"bus": {3, *range(700, 717)}, "tram": {0, *range(900, 907)}}[mode]
    end = start + timedelta(days=days - 1)
    with zipfile.ZipFile(path) as archive:
        services = active_services(archive, start, end)
        routes = {r['route_id']: r['route_short_name'].strip() for r in rows(archive, 'routes.txt')
                  if int(r['route_type']) in route_types
                  and r['route_short_name'].strip()}
        trips = {t['trip_id']: routes[t['route_id']] for t in rows(archive, 'trips.txt')
                 if t['route_id'] in routes and t['service_id'] in services}
        # Berlin municipality in VBB's stop identifiers, including its platforms.
        stops = {s['stop_id']: s for s in rows(archive, 'stops.txt') if s['stop_id'].startswith('de:11000:')}
        lines = defaultdict(set)
        for row in rows(archive, 'stop_times.txt'):
            if row['stop_id'] in stops and row['trip_id'] in trips:
                if row.get('pickup_type') == '1' and row.get('drop_off_type') == '1':
                    continue
                lines[row['stop_id']].add(trips[row['trip_id']])
        entries = []
        for stop_id, names in sorted(lines.items()):
            stop = stops[stop_id]
            entries.append(dict(id=stop_id, parent=stop.get('parent_station', ''), name=stop['stop_name'],
                                lon=round(float(stop['stop_lon']), 6), lat=round(float(stop['stop_lat']), 6),
                                lines=sorted(names)))
    if not entries:
        raise ValueError(f'No active Berlin {mode} stops in the requested period; output was not replaced')
    return dict(source=SOURCE, license='CC BY 4.0',
                generatedAt=datetime.now(timezone.utc).isoformat(),
                feedSha256=hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                validFrom=start.isoformat(), validUntil=end.isoformat(), stops=entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gtfs', type=Path)
    parser.add_argument('--date', type=date.fromisoformat, default=date.today())
    parser.add_argument('--mode', choices=('bus', 'tram'), default='bus')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.output is None:
        args.output = Path(__file__).resolve().parents[2] / f'src/main/resources/berlin_{args.mode}_routes.json'
    catalog = build_catalog(args.gtfs, args.date, mode=args.mode)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.tmp')
    stops = catalog.pop('stops')
    metadata = json.dumps(catalog, ensure_ascii=False, indent=2)
    entries = ',\n'.join('    ' + json.dumps(stop, ensure_ascii=False, separators=(',', ':')) for stop in stops)
    temporary.write_text(metadata[:-2] + ',\n  "stops": [\n' + entries + '\n  ]\n}\n', encoding='utf-8')
    catalog['stops'] = stops
    temporary.replace(args.output)
    print(f"{len(catalog['stops'])} platforms; {catalog['validFrom']} — {catalog['validUntil']}; {args.output}")


if __name__ == '__main__':
    main()
