import csv
from datetime import date
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from build_bus_routes import build_catalog


class CatalogTest(unittest.TestCase):
    def test_calendar_exceptions_bus_types_and_platforms(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'fixture.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                def table(name, header, rows):
                    stream = io.StringIO()
                    writer = csv.writer(stream)
                    writer.writerow(header.split(','))
                    writer.writerows(rows)
                    z.writestr(name, stream.getvalue())
                table('calendar.txt', 'service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date', [
                    ['regular', 1, 1, 1, 1, 1, 1, 1, '20260901', '20261031'],
                    ['expired', 1, 1, 1, 1, 1, 1, 1, '20260901', '20260930'],
                    ['removed', 0, 0, 0, 1, 0, 0, 0, '20261001', '20261001'],
                    ['future', 1, 1, 1, 1, 1, 1, 1, '20261101', '20261130'],
                ])
                table('calendar_dates.txt', 'service_id,date,exception_type', [
                    ['removed', '20261001', '2'], ['added', '20261003', '1'],
                    ['outside', '20261101', '1'],
                ])
                table('routes.txt', 'route_id,route_short_name,route_type', [
                    ['r1', '100', 3], ['r2', 'N1', 700], ['r3', 'M1', 0],
                    ['tram900', 'M10', 900], ['tram906', '21', 906],
                    ['tramExpired', '88', 900], ['tramRemoved', '89', 0],
                    ['tramAdded', '12', 900], ['subway', 'U2', 1],
                    ['expired', '999', 3], ['removed', '998', 3], ['future', '997', 3], ['outside', '996', 3],
                ])
                services = ['regular', 'expired', 'removed', 'future', 'added', 'outside']
                tram_trips = [['tram', 'r3', 'regular'], ['tram900', 'tram900', 'regular'],
                             ['tram906', 'tram906', 'regular'], ['tramExpired', 'tramExpired', 'expired'],
                             ['tramRemoved', 'tramRemoved', 'removed'], ['tramAdded', 'tramAdded', 'added']]
                table('trips.txt', 'trip_id,route_id,service_id', [[s, 'r2' if s == 'added' else 'r1' if s == 'regular' else s, s] for s in services] + tram_trips + [['subway', 'subway', 'regular']])
                table('stops.txt', 'stop_id,parent_station,stop_name,stop_lon,stop_lat', [
                    ['de:11000:1::1', 'de:11000:1', 'Test, Str. (Berlin)', 13.4, 52.5],
                    ['de:12000:2', '', 'Outside', 13.5, 52.6],
                    ['de:11000:3', '', 'No boarding', 13.4, 52.5],
                ])
                table('stop_times.txt', 'trip_id,stop_id,pickup_type,drop_off_type',
                      [[s, 'de:11000:1::1', 0, 0] for s in services + [t[0] for t in tram_trips] + ['subway']] +
                      [[trip, stop, pickup, dropoff] for trip in ['regular', 'tram']
                       for stop, pickup, dropoff in [('de:12000:2', 0, 0), ('de:11000:3', 1, 1)]])
            catalog = build_catalog(archive, date(2026, 10, 1))
            self.assertEqual('2026-10-07', catalog['validUntil'])
            self.assertEqual(1, len(catalog['stops']))
            self.assertEqual(['100', 'N1'], catalog['stops'][0]['lines'])
            self.assertEqual('de:11000:1', catalog['stops'][0]['parent'])
            tram_catalog = build_catalog(archive, date(2026, 10, 1), mode='tram')
            self.assertEqual(1, len(tram_catalog['stops']))
            self.assertEqual(['12', '21', 'M1', 'M10'], tram_catalog['stops'][0]['lines'])
            self.assertEqual('de:11000:1', tram_catalog['stops'][0]['parent'])
            with self.assertRaises(ValueError):
                build_catalog(archive, date(2027, 1, 1))
            with self.assertRaises(ValueError):
                build_catalog(archive, date(2027, 1, 1), mode='tram')


if __name__ == '__main__':
    unittest.main()
