"""Writes the small sample GTFS network in data/sample/mini-gtfs.

The sample is a simplified slice of the TTC so the app, the tests and the demo
videos run without downloading the full TTC feed. Station names and positions
follow the real network; timetables, run times and the wheelchair_boarding
values are ILLUSTRATIVE, not official TTC data.

Usage:  python3 tools/make_sample_gtfs.py
"""
import csv
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "sample", "mini-gtfs")

# station key -> (name, lat, lon)
STATIONS = {
    "YU": ("York University Station", 43.7742, -79.4999),
    "FW": ("Finch West Station", 43.7652, -79.4911),
    "SW": ("Sheppard West Station", 43.7495, -79.4620),
    "WI": ("Wilson Station", 43.7348, -79.4499),
    "SC": ("St Clair West Station", 43.6840, -79.4155),
    "SP": ("Spadina Station", 43.6672, -79.4037),
    "SG": ("St George Station", 43.6683, -79.3999),
    "MU": ("Museum Station", 43.6671, -79.3936),
    "QP": ("Queen's Park Station", 43.6600, -79.3904),
    "SPT": ("St Patrick Station", 43.6548, -79.3883),
    "OS": ("Osgoode Station", 43.6508, -79.3867),
    "SA": ("St Andrew Station", 43.6476, -79.3848),
    "UN": ("Union Station", 43.6453, -79.3806),
    "KI": ("King Station", 43.6491, -79.3779),
    "QU": ("Queen Station", 43.6524, -79.3793),
    "DU": ("Dundas Station", 43.6561, -79.3803),
    "CO": ("College Station", 43.6613, -79.3830),
    "WE": ("Wellesley Station", 43.6653, -79.3839),
    "BY": ("Bloor-Yonge Station", 43.6709, -79.3857),
    "EG": ("Eglinton Station", 43.7057, -79.3983),
    "FI": ("Finch Station", 43.7804, -79.4153),
    "KE": ("Keele Station", 43.6556, -79.4597),
    "DF": ("Dufferin Station", 43.6602, -79.4355),
    "BA": ("Bathurst Station", 43.6662, -79.4111),
    "BAY": ("Bay Station", 43.6702, -79.3901),
    "SH": ("Sherbourne Station", 43.6722, -79.3763),
    "BR": ("Broadview Station", 43.6769, -79.3588),
}
# street stops (not stations)
STREET = {
    "SPCO": ("Spadina Ave at College St", 43.6578, -79.4005),
    "SPQU": ("Spadina Ave at Queen St West", 43.6488, -79.3963),
    "SPKI": ("Spadina Ave at King St West", 43.6453, -79.3949),
    "QQSP": ("Queens Quay West at Spadina Ave", 43.6385, -79.3925),
    "DFEG": ("Dufferin St at Eglinton Ave West", 43.6967, -79.4508),
    "DFLA": ("Dufferin St at Lawrence Ave West", 43.7196, -79.4560),
    "FWKE": ("Finch Ave West at Keele St", 43.7700, -79.4880),
    "FWBA": ("Finch Ave West at Bathurst St", 43.7743, -79.4400),
    "FWYO": ("Finch Ave West at Yonge St", 43.7800, -79.4160),
}
NOT_STEP_FREE = {"SPT", "MU"}   # illustrative only

LINES = {
    # route_id: (short, long, gtfs route_type, stop keys, seconds between stops, headway weekday, headway weekend, platform tag)
    "1": ("1", "Line 1 Yonge-University", 1,
          ["YU", "FW", "SW", "WI", "SC", "SP", "SG", "MU", "QP", "SPT", "OS", "SA", "UN",
           "KI", "QU", "DU", "CO", "WE", "BY", "EG", "FI"], 120, 300, 360, "L1"),
    "2": ("2", "Line 2 Bloor-Danforth", 1,
          ["KE", "DF", "BA", "SP", "SG", "BAY", "BY", "SH", "BR"], 120, 300, 360, "L2"),
    "510": ("510", "Spadina", 0, ["SP", "SPCO", "SPQU", "SPKI", "QQSP", "UN"], 240, 480, 600, "510"),
    "29": ("29", "Dufferin", 3, ["DF", "DFEG", "DFLA", "WI"], 420, 720, 900, "29"),
    "36": ("36", "Finch West", 3, ["FW", "FWKE", "FWBA", "FWYO", "FI"], 360, 600, 900, "36"),
}


def hhmmss(sec):
    return f"{sec // 3600:02d}:{sec % 3600 // 60:02d}:{sec % 60:02d}"


def main():
    os.makedirs(OUT, exist_ok=True)
    stops, stop_ids = [], {}
    for key, (name, lat, lon) in STATIONS.items():
        pid = f"STN_{key}"
        stops.append([pid, "", name, lat, lon, 1, "", 0 if key in NOT_STEP_FREE else 1])

    def platform(line_tag, key):
        sid = f"{key}_{line_tag}"
        if sid not in stop_ids:
            if key in STATIONS:
                name, lat, lon = STATIONS[key]
                stops.append([sid, "", f"{name} - {LINES_TAG_NAMES[line_tag]}", lat, lon, 0, f"STN_{key}",
                              2 if key in NOT_STEP_FREE else 1])
            else:
                name, lat, lon = STREET[key]
                stops.append([sid, "", name, lat, lon, 0, "", 1])
            stop_ids[sid] = True
        return sid

    trips, stop_times = [], []
    for rid, (short, long_name, rtype, keys, run, hw_wd, hw_we, tag) in LINES.items():
        for service, headway in (("WEEKDAY", hw_wd), ("WEEKEND", hw_we)):
            for direction, seq in ((0, keys), (1, list(reversed(keys)))):
                start, n = 6 * 3600, 0
                while start <= 23 * 3600 + 30 * 60:
                    tid = f"{rid}_{service}_{direction}_{n}"
                    trips.append([rid, service, tid, STATIONS.get(seq[-1], STREET.get(seq[-1]))[0], direction])
                    t = start
                    for i, k in enumerate(seq):
                        dwell = 20 if rtype == 1 else 0
                        stop_times.append([tid, hhmmss(t), hhmmss(t + dwell), platform(tag, k), i + 1])
                        t += run + dwell
                    start += headway
                    n += 1

    def write(name, header, rows):
        with open(os.path.join(OUT, name), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(header)
            w.writerows(rows)

    write("agency.txt", ["agency_id", "agency_name", "agency_url", "agency_timezone"],
          [["TTC", "Toronto Transit Commission (sample)", "https://www.ttc.ca", "America/Toronto"]])
    write("stops.txt", ["stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon", "location_type",
                        "parent_station", "wheelchair_boarding"], stops)
    write("routes.txt", ["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"],
          [[rid, "TTC", v[0], v[1], v[2]] for rid, v in LINES.items()])
    write("trips.txt", ["route_id", "service_id", "trip_id", "trip_headsign", "direction_id"], trips)
    write("stop_times.txt", ["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"], stop_times)
    write("calendar.txt", ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
                           "sunday", "start_date", "end_date"],
          [["WEEKDAY", 1, 1, 1, 1, 1, 0, 0, "20260101", "20271231"],
           ["WEEKEND", 0, 0, 0, 0, 0, 1, 1, "20260101", "20271231"]])
    write("calendar_dates.txt", ["service_id", "date", "exception_type"],
          [["WEEKDAY", "20261225", 2], ["WEEKEND", "20261225", 1]])   # Christmas runs a weekend schedule
    print(f"wrote {len(stops)} stops, {len(trips)} trips, {len(stop_times)} stop times to {os.path.normpath(OUT)}")


LINES_TAG_NAMES = {"L1": "Line 1 platform", "L2": "Line 2 platform", "510": "510 platform",
                   "29": "29 Dufferin bus bay", "36": "36 Finch West bus bay"}

if __name__ == "__main__":
    main()
