package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot.StopDelay;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes the parts of a GTFS-Realtime FeedMessage that DetourTO uses: trip updates (delays) and
 * alerts. Field numbers follow gtfs-realtime.proto; unknown fields are skipped.
 */
final class GtfsRealtimeDecoder {

    static final String[] CAUSES = {"", "UNKNOWN_CAUSE", "OTHER_CAUSE", "TECHNICAL_PROBLEM", "STRIKE",
        "DEMONSTRATION", "ACCIDENT", "HOLIDAY", "WEATHER", "MAINTENANCE", "CONSTRUCTION", "POLICE_ACTIVITY",
        "MEDICAL_EMERGENCY"};
    static final String[] EFFECTS = {"", "NO_SERVICE", "REDUCED_SERVICE", "SIGNIFICANT_DELAYS", "DETOUR",
        "ADDITIONAL_SERVICE", "MODIFIED_SERVICE", "OTHER_EFFECT", "UNKNOWN_EFFECT", "STOP_MOVED", "NO_EFFECT",
        "ACCESSIBILITY_ISSUE"};

    /** Decoded feed: header timestamp, delays by trip id, and alerts. */
    record Feed(Instant timestamp, Map<String, List<StopDelay>> delaysByTrip, List<ServiceAlert> alerts) {
    }

    Feed decode(byte[] bytes) {
        ProtoReader r = new ProtoReader(bytes);
        Instant timestamp = null;
        Map<String, List<StopDelay>> delays = new LinkedHashMap<>();
        List<ServiceAlert> alerts = new ArrayList<>();
        while (r.nextTag() != 0) {
            switch (r.fieldNumber()) {
                case 1 -> timestamp = header(r.readMessage());
                case 2 -> entity(r.readMessage(), delays, alerts);
                default -> r.skip();
            }
        }
        return new Feed(timestamp, delays, alerts);
    }

    private Instant header(ProtoReader h) {
        Instant t = null;
        while (h.nextTag() != 0) {
            if (h.fieldNumber() == 3) {
                t = Instant.ofEpochSecond(h.readVarint());
            } else {
                h.skip();
            }
        }
        return t;
    }

    private void entity(ProtoReader e, Map<String, List<StopDelay>> delays, List<ServiceAlert> alerts) {
        String id = "";
        boolean deleted = false;
        ProtoReader tripUpdate = null;
        ProtoReader alert = null;
        while (e.nextTag() != 0) {
            switch (e.fieldNumber()) {
                case 1 -> id = e.readString();
                case 2 -> deleted = e.readVarint() != 0;
                case 3 -> tripUpdate = e.readMessage();
                case 5 -> alert = e.readMessage();
                default -> e.skip();
            }
        }
        if (deleted) {
            return;
        }
        if (tripUpdate != null) {
            tripUpdate(tripUpdate, delays);
        }
        if (alert != null) {
            alerts.add(alert(id, alert));
        }
    }

    private void tripUpdate(ProtoReader t, Map<String, List<StopDelay>> delays) {
        String tripId = null;
        Integer tripDelay = null;
        List<StopDelay> updates = new ArrayList<>();
        while (t.nextTag() != 0) {
            switch (t.fieldNumber()) {
                case 1 -> tripId = tripDescriptorId(t.readMessage());
                case 2 -> {
                    StopDelay d = stopTimeUpdate(t.readMessage());
                    if (d != null) {
                        updates.add(d);
                    }
                }
                case 5 -> tripDelay = t.readInt32();
                default -> t.skip();
            }
        }
        if (tripId == null) {
            return;
        }
        if (updates.isEmpty() && tripDelay != null) {
            updates.add(new StopDelay(0, "", tripDelay));
        }
        if (!updates.isEmpty()) {
            delays.put(tripId, updates);
        }
    }

    private String tripDescriptorId(ProtoReader d) {
        String id = null;
        while (d.nextTag() != 0) {
            if (d.fieldNumber() == 1) {
                id = d.readString();
            } else {
                d.skip();
            }
        }
        return id;
    }

    /** Delay from arrival (preferred) or departure; updates with only absolute times are skipped. */
    private StopDelay stopTimeUpdate(ProtoReader u) {
        int sequence = 0;
        String stopId = "";
        Integer arrivalDelay = null;
        Integer departureDelay = null;
        boolean skipped = false;
        while (u.nextTag() != 0) {
            switch (u.fieldNumber()) {
                case 1 -> sequence = (int) u.readVarint();
                case 2 -> arrivalDelay = eventDelay(u.readMessage());
                case 3 -> departureDelay = eventDelay(u.readMessage());
                case 4 -> stopId = u.readString();
                case 5 -> skipped = u.readVarint() == 1; // SKIPPED
                default -> u.skip();
            }
        }
        Integer delay = arrivalDelay != null ? arrivalDelay : departureDelay;
        if (skipped || delay == null) {
            return null;
        }
        return new StopDelay(sequence, stopId, delay);
    }

    private Integer eventDelay(ProtoReader ev) {
        Integer delay = null;
        while (ev.nextTag() != 0) {
            if (ev.fieldNumber() == 1) {
                delay = ev.readInt32();
            } else {
                ev.skip();
            }
        }
        return delay;
    }

    private ServiceAlert alert(String id, ProtoReader a) {
        Instant from = null;
        Instant to = null;
        Set<String> routes = new LinkedHashSet<>();
        Set<String> stops = new LinkedHashSet<>();
        String cause = "UNKNOWN_CAUSE";
        String effect = "UNKNOWN_EFFECT";
        String header = "";
        String description = "";
        while (a.nextTag() != 0) {
            switch (a.fieldNumber()) {
                case 1 -> {
                    Instant[] range = timeRange(a.readMessage());
                    if (from == null || (range[0] != null && range[0].isBefore(from))) {
                        from = range[0];
                    }
                    to = range[1];
                }
                case 5 -> informedEntity(a.readMessage(), routes, stops);
                case 6 -> cause = enumName(CAUSES, (int) a.readVarint(), "UNKNOWN_CAUSE");
                case 7 -> effect = enumName(EFFECTS, (int) a.readVarint(), "UNKNOWN_EFFECT");
                case 10 -> header = translatedText(a.readMessage());
                case 11 -> description = translatedText(a.readMessage());
                default -> a.skip();
            }
        }
        return new ServiceAlert(id, header, description, List.copyOf(routes), List.copyOf(stops), from, to,
                cause, effect);
    }

    private Instant[] timeRange(ProtoReader t) {
        Instant[] out = new Instant[2];
        while (t.nextTag() != 0) {
            switch (t.fieldNumber()) {
                case 1 -> out[0] = Instant.ofEpochSecond(t.readVarint());
                case 2 -> out[1] = Instant.ofEpochSecond(t.readVarint());
                default -> t.skip();
            }
        }
        return out;
    }

    private void informedEntity(ProtoReader e, Set<String> routes, Set<String> stops) {
        while (e.nextTag() != 0) {
            switch (e.fieldNumber()) {
                case 2 -> routes.add(e.readString());
                case 5 -> stops.add(e.readString());
                default -> e.skip();
            }
        }
    }

    /** First translation, preferring English. */
    private String translatedText(ProtoReader ts) {
        String first = null;
        while (ts.nextTag() != 0) {
            if (ts.fieldNumber() != 1) {
                ts.skip();
                continue;
            }
            ProtoReader tr = ts.readMessage();
            String text = "";
            String lang = "";
            while (tr.nextTag() != 0) {
                switch (tr.fieldNumber()) {
                    case 1 -> text = tr.readString();
                    case 2 -> lang = tr.readString();
                    default -> tr.skip();
                }
            }
            if (lang.isEmpty() || lang.toLowerCase().startsWith("en")) {
                return text;
            }
            if (first == null) {
                first = text;
            }
        }
        return first == null ? "" : first;
    }

    private static String enumName(String[] names, int value, String fallback) {
        return value > 0 && value < names.length ? names[value] : fallback;
    }
}
