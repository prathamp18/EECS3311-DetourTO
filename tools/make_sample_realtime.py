#!/usr/bin/env python3
"""Writes a small recorded GTFS-Realtime feed for the sample network (data/sample/realtime).

trips.pb  - delays for 510 Spadina and 29 Dufferin trips (subway has no TTC real-time)
alerts.pb - four alerts: a Line 1 closure, an elevator outage, a free-text 510 diversion
            (only the text says which stop is skipped) and 29 Dufferin delays

The protobuf wire format is written by hand so this script needs only the standard library.
Field numbers follow gtfs-realtime.proto. Run from the repository root:
    python3 tools/make_sample_realtime.py
"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "sample", "realtime")
FEED_TIME = 1793610000            # 2026-11-02T09:00:00Z, fixed so the files are reproducible
ALERT_START = 1790000000          # 2026-09-21, alerts have no end time


def varint(n):
    n &= (1 << 64) - 1            # negative int32 values become 10-byte two's complement
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field_varint(num, value):
    return varint(num << 3 | 0) + varint(value)


def field_bytes(num, data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return varint(num << 3 | 2) + varint(len(data)) + data


def header():
    return field_bytes(1, field_bytes(1, "2.0") + field_varint(2, 0) + field_varint(3, FEED_TIME))


def translated(text):
    return field_bytes(1, field_bytes(1, text) + field_bytes(2, "en"))


def trip_entity(trip_id, route_id, first_stop, delay):
    descriptor = field_bytes(1, trip_id) + field_bytes(5, route_id)
    stu = field_varint(1, 1) + field_bytes(2, field_varint(1, delay)) + field_bytes(4, first_stop)
    update = field_bytes(1, descriptor) + field_bytes(2, stu)
    return field_bytes(2, field_bytes(1, "tu-" + trip_id) + field_bytes(3, update))


def alert_entity(alert_id, header_text, description, routes, stops, cause, effect):
    body = field_bytes(1, field_varint(1, ALERT_START))
    for r in routes:
        body += field_bytes(5, field_bytes(2, r))
    for s in stops:
        body += field_bytes(5, field_bytes(5, s))
    body += field_varint(6, cause) + field_varint(7, effect)
    body += field_bytes(10, translated(header_text)) + field_bytes(11, translated(description))
    return field_bytes(2, field_bytes(1, alert_id) + field_bytes(5, body))


def trips():
    out = header()
    first_stops = {("510", 0): "SP_510", ("510", 1): "UN_510", ("29", 0): "DF_29", ("29", 1): "WI_29"}
    headways = {("510", "WEEKDAY"): 480, ("510", "WEEKEND"): 600, ("29", "WEEKDAY"): 720, ("29", "WEEKEND"): 900}
    for route in ("510", "29"):
        for service in ("WEEKDAY", "WEEKEND"):
            hw = headways[(route, service)]
            count = (23 * 3600 + 30 * 60 - 6 * 3600) // hw + 1
            for direction in (0, 1):
                for n in range(count):
                    delay = (n * 97 + direction * 41) % 480 - 60     # -1 to +7 minutes
                    if route == "29":
                        delay += 300                                  # the 29 runs late (see alert)
                    out += trip_entity(f"{route}_{service}_{direction}_{n}", route,
                                       first_stops[(route, direction)], delay)
    return out


def alerts():
    out = header()
    out += alert_entity("A-L1-CLOSURE",
                        "Line 1: No subway service between St George and St Andrew stations",
                        "Due to track maintenance, there is no subway service between St George and "
                        "St Andrew stations. Shuttle buses are running.",
                        ["1"], ["STN_MU", "STN_QP", "STN_SPT", "STN_OS"], 9, 1)
    out += alert_entity("A-BAY-ELEVATOR",
                        "Elevator out of service at Bay Station",
                        "The elevator between the concourse and the Line 2 platform at Bay Station is "
                        "out of service.", [], ["STN_BAY"], 3, 11)
    out += alert_entity("A-510-DIVERSION",
                        "510 Spadina: Streetcars not stopping at Queen",
                        "Due to construction, 510 Spadina streetcars are not stopping at Spadina Ave "
                        "at Queen St West. Customers can board at Spadina Ave at King St West instead.",
                        ["510"], [], 10, 8)
    out += alert_entity("A-29-DELAY",
                        "29 Dufferin: Delays of up to 15 minutes",
                        "29 Dufferin buses are delayed by up to 15 minutes in both directions due to a "
                        "collision near Lawrence Ave West.", ["29"], [], 6, 3)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, data in (("trips.pb", trips()), ("alerts.pb", alerts())):
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print(f"wrote {name}: {len(data)} bytes")


if __name__ == "__main__":
    main()
