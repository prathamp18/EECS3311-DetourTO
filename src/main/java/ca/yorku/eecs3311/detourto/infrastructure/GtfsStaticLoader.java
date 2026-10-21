package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.Route;
import ca.yorku.eecs3311.detourto.domain.ServiceCalendar;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.Transfer;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.Trip;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a GTFS static feed (a .zip or an unzipped folder) into a {@link TransitNetwork}.
 *
 * <p>Problems in individual rows are collected as warnings instead of stopping the load, so a
 * slightly broken feed still gives a usable network. {@link #lastReport()} returns what was found.
 */
public final class GtfsStaticLoader {

    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String[] REQUIRED = {"stops.txt", "routes.txt", "trips.txt", "stop_times.txt"};
    private static final int MAX_WARNINGS = 500;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private FeedValidationReport lastReport;

    /** Source of the feed's files, so the loader works the same on a zip and on a folder. */
    private interface FeedSource extends AutoCloseable {
        boolean has(String name);

        InputStream open(String name) throws IOException;

        @Override
        void close() throws IOException;
    }

    public TransitNetwork load(Path path) throws IOException {
        warnings.clear();
        errors.clear();
        if (!Files.exists(path)) {
            throw new IOException("GTFS path not found: " + path.toAbsolutePath()
                    + " (set GTFS_PATH in .env, or use the sample in data/sample/mini-gtfs)");
        }
        try (FeedSource src = Files.isDirectory(path) ? folder(path) : zip(path)) {
            for (String name : REQUIRED) {
                if (!src.has(name)) {
                    throw new IOException("GTFS feed " + path + " is missing " + name);
                }
            }
            List<Stop> stops = readStops(src);
            Map<String, Integer> stopIdx = new HashMap<>();
            for (int i = 0; i < stops.size(); i++) {
                stopIdx.put(stops.get(i).id(), i);
            }
            Map<String, Route> routes = readRoutes(src);
            Map<String, String[]> tripInfo = readTrips(src, routes);
            StopTimeResult st = readStopTimes(src, stopIdx);
            List<Trip> trips = buildTrips(tripInfo, routes, st);
            ServiceCalendar calendar = readCalendar(src);
            List<Transfer> transfers = readTransfers(src, stops, stopIdx);

            Set<String> services = new HashSet<>();
            trips.forEach(t -> services.add(t.serviceId()));
            if (stops.isEmpty()) {
                errors.add("no stops loaded");
            }
            if (trips.isEmpty()) {
                errors.add("no trips with stop times loaded");
            }
            if (calendar.ruleCount() == 0 && !src.has("calendar_dates.txt")) {
                errors.add("no calendar.txt or calendar_dates.txt: no service would ever run");
            }
            lastReport = new FeedValidationReport(stops.size(), routes.size(), trips.size(),
                    st.rows, services.size(), warnings, errors);
            return new TransitNetwork(stops, routes.values(), trips, transfers, calendar);
        }
    }

    /** Report from the most recent {@link #load(Path)} call. */
    public FeedValidationReport lastReport() {
        if (lastReport == null) {
            throw new IllegalStateException("load() has not been called");
        }
        return lastReport;
    }

    /** Loads the feed only to check it; used by {@code detour validate-feed}. */
    public FeedValidationReport validate(Path path) throws IOException {
        load(path);
        return lastReport;
    }

    // ------------------------------------------------------------------ files

    private List<Stop> readStops(FeedSource src) throws IOException {
        List<Stop> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (CsvReader csv = reader(src, "stops.txt")) {
            while (csv.next()) {
                String id = csv.get("stop_id");
                if (id.isEmpty() || !seen.add(id)) {
                    warn("stops.txt line " + csv.lineNumber() + ": missing or duplicate stop_id");
                    continue;
                }
                double lat;
                double lon;
                try {
                    lat = Double.parseDouble(csv.get("stop_lat"));
                    lon = Double.parseDouble(csv.get("stop_lon"));
                } catch (NumberFormatException e) {
                    warn("stops.txt: stop " + id + " has no valid coordinates; skipped");
                    continue;
                }
                String locType = csv.get("location_type");
                if (!locType.isEmpty() && !locType.equals("0") && !locType.equals("1")) {
                    continue; // entrances, generic nodes and boarding areas are not used yet
                }
                String parent = csv.get("parent_station");
                out.add(new Stop(id, csv.get("stop_code"), csv.get("stop_name"), lat, lon,
                        "1".equals(csv.get("wheelchair_boarding")),
                        parent.isEmpty() ? null : parent, "1".equals(locType)));
            }
        }
        for (Stop s : out) {
            if (s.parentStation() != null && !seen.contains(s.parentStation())) {
                warn("stop " + s.id() + " refers to unknown parent_station " + s.parentStation());
            }
        }
        return out;
    }

    private Map<String, Route> readRoutes(FeedSource src) throws IOException {
        Map<String, Route> out = new LinkedHashMap<>();
        try (CsvReader csv = reader(src, "routes.txt")) {
            while (csv.next()) {
                String id = csv.get("route_id");
                int type;
                try {
                    type = Integer.parseInt(csv.get("route_type"));
                } catch (NumberFormatException e) {
                    warn("routes.txt: route " + id + " has an invalid route_type; treated as bus");
                    type = 3;
                }
                out.put(id, new Route(id, csv.get("route_short_name"), csv.get("route_long_name"),
                        TransitMode.fromGtfsRouteType(type)));
            }
        }
        return out;
    }

    /** trip_id -> {route_id, service_id, headsign}. */
    private Map<String, String[]> readTrips(FeedSource src, Map<String, Route> routes)
            throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        try (CsvReader csv = reader(src, "trips.txt")) {
            while (csv.next()) {
                String routeId = csv.get("route_id");
                if (!routes.containsKey(routeId)) {
                    warn("trips.txt: trip " + csv.get("trip_id") + " has unknown route " + routeId);
                    continue;
                }
                out.put(csv.get("trip_id"),
                        new String[] {routeId, csv.get("service_id"), csv.get("trip_headsign")});
            }
        }
        return out;
    }

    /** Stop times grouped by trip, kept as growable int arrays to save memory. */
    private static final class StopTimeResult {
        final Map<String, int[][]> byTrip = new HashMap<>(); // [seq, stop, arr, dep] columns
        final Map<String, Integer> sizes = new HashMap<>();
        int rows;
    }

    private StopTimeResult readStopTimes(FeedSource src, Map<String, Integer> stopIdx)
            throws IOException {
        StopTimeResult r = new StopTimeResult();
        try (CsvReader csv = reader(src, "stop_times.txt")) {
            while (csv.next()) {
                String tripId = csv.get("trip_id");
                Integer stop = stopIdx.get(csv.get("stop_id"));
                if (stop == null) {
                    warn("stop_times.txt line " + csv.lineNumber() + ": unknown stop_id "
                            + csv.get("stop_id"));
                    continue;
                }
                int arr = parseTime(csv.get("arrival_time"));
                int dep = parseTime(csv.get("departure_time"));
                if (arr < 0 && dep < 0) {
                    // untimed stop: GTFS allows this between timepoints; not interpolated yet
                    warn("stop_times.txt line " + csv.lineNumber() + ": untimed stop skipped");
                    continue;
                }
                if (arr < 0) {
                    arr = dep;
                }
                if (dep < 0) {
                    dep = arr;
                }
                int seq;
                try {
                    seq = Integer.parseInt(csv.get("stop_sequence"));
                } catch (NumberFormatException e) {
                    warn("stop_times.txt line " + csv.lineNumber() + ": bad stop_sequence");
                    continue;
                }
                int n = r.sizes.getOrDefault(tripId, 0);
                int[][] cols = r.byTrip.get(tripId);
                if (cols == null) {
                    cols = new int[4][16];
                    r.byTrip.put(tripId, cols);
                } else if (n == cols[0].length) {
                    for (int c = 0; c < 4; c++) {
                        cols[c] = Arrays.copyOf(cols[c], n * 2);
                    }
                }
                cols[0][n] = seq;
                cols[1][n] = stop;
                cols[2][n] = arr;
                cols[3][n] = dep;
                r.sizes.put(tripId, n + 1);
                r.rows++;
            }
        }
        return r;
    }

    private List<Trip> buildTrips(Map<String, String[]> tripInfo, Map<String, Route> routes,
                                  StopTimeResult st) {
        List<Trip> out = new ArrayList<>(tripInfo.size());
        for (Map.Entry<String, String[]> e : tripInfo.entrySet()) {
            String id = e.getKey();
            int[][] cols = st.byTrip.get(id);
            int n = st.sizes.getOrDefault(id, 0);
            if (cols == null || n < 2) {
                warn("trip " + id + " has fewer than two stop times; skipped");
                continue;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            final int[] seqCol = cols[0];
            Arrays.sort(order, (a, b) -> Integer.compare(seqCol[a], seqCol[b]));
            int[] stopIndex = new int[n];
            int[] arr = new int[n];
            int[] dep = new int[n];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                stopIndex[i] = cols[1][order[i]];
                arr[i] = cols[2][order[i]];
                dep[i] = cols[3][order[i]];
                if (i > 0 && arr[i] < dep[i - 1]) {
                    ok = false;
                }
            }
            if (!ok) {
                warn("trip " + id + " goes back in time between stops; skipped");
                continue;
            }
            String[] info = e.getValue();
            out.add(new Trip(id, routes.get(info[0]), info[1], info[2], stopIndex, arr, dep));
        }
        for (String id : st.byTrip.keySet()) {
            if (!tripInfo.containsKey(id)) {
                warn("stop_times.txt refers to unknown trip " + id);
            }
        }
        return out;
    }

    private ServiceCalendar readCalendar(FeedSource src) throws IOException {
        ServiceCalendar cal = new ServiceCalendar();
        if (src.has("calendar.txt")) {
            String[] cols = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
                "sunday"};
            try (CsvReader csv = reader(src, "calendar.txt")) {
                while (csv.next()) {
                    Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                    for (int i = 0; i < 7; i++) {
                        if ("1".equals(csv.get(cols[i]))) {
                            days.add(DayOfWeek.of(i + 1));
                        }
                    }
                    try {
                        cal.addRule(csv.get("service_id"), days,
                                LocalDate.parse(csv.get("start_date"), GTFS_DATE),
                                LocalDate.parse(csv.get("end_date"), GTFS_DATE));
                    } catch (DateTimeParseException e) {
                        warn("calendar.txt: service " + csv.get("service_id") + " has bad dates");
                    }
                }
            }
        }
        if (src.has("calendar_dates.txt")) {
            try (CsvReader csv = reader(src, "calendar_dates.txt")) {
                while (csv.next()) {
                    try {
                        cal.addException(csv.get("service_id"),
                                LocalDate.parse(csv.get("date"), GTFS_DATE),
                                Integer.parseInt(csv.get("exception_type")));
                    } catch (DateTimeParseException | NumberFormatException e) {
                        warn("calendar_dates.txt line " + csv.lineNumber() + ": bad row");
                    }
                }
            }
        }
        return cal;
    }

    private List<Transfer> readTransfers(FeedSource src, List<Stop> stops,
                                         Map<String, Integer> stopIdx) throws IOException {
        List<Transfer> out = new ArrayList<>();
        if (!src.has("transfers.txt")) {
            return out;
        }
        try (CsvReader csv = reader(src, "transfers.txt")) {
            while (csv.next()) {
                Integer from = stopIdx.get(csv.get("from_stop_id"));
                Integer to = stopIdx.get(csv.get("to_stop_id"));
                if (from == null || to == null || "3".equals(csv.get("transfer_type"))) {
                    continue; // unknown stops, or "transfer not possible"
                }
                int seconds = TransitNetwork.MIN_TRANSFER_SECONDS;
                String min = csv.get("min_transfer_time");
                if (!min.isEmpty()) {
                    try {
                        seconds = Math.max(seconds, Integer.parseInt(min));
                    } catch (NumberFormatException e) {
                        warn("transfers.txt line " + csv.lineNumber() + ": bad min_transfer_time");
                    }
                }
                out.add(new Transfer(stops.get(from), stops.get(to), seconds));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** "HH:MM:SS" to seconds after midnight; GTFS allows hours past 24 for after-midnight trips. */
    static int parseTime(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        String[] p = s.split(":");
        if (p.length != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(p[0].strip()) * 3600 + Integer.parseInt(p[1]) * 60
                    + Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void warn(String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("(further warnings not recorded)");
        }
    }

    private static CsvReader reader(FeedSource src, String name) throws IOException {
        return new CsvReader(new InputStreamReader(src.open(name), StandardCharsets.UTF_8));
    }

    private static FeedSource folder(Path dir) {
        return new FeedSource() {
            @Override
            public boolean has(String name) {
                return Files.isRegularFile(dir.resolve(name));
            }

            @Override
            public InputStream open(String name) throws IOException {
                return Files.newInputStream(dir.resolve(name));
            }

            @Override
            public void close() {
            }
        };
    }

    private static FeedSource zip(Path file) throws IOException {
        ZipFile zf = new ZipFile(file.toFile());
        Map<String, ZipEntry> entries = new HashMap<>();
        zf.stream().forEach(e -> {
            // some feeds put files in a sub-folder inside the zip
            String name = e.getName();
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (!e.isDirectory() && base.endsWith(".txt")) {
                entries.putIfAbsent(base, e);
            }
        });
        return new FeedSource() {
            @Override
            public boolean has(String name) {
                return entries.containsKey(name);
            }

            @Override
            public InputStream open(String name) throws IOException {
                return zf.getInputStream(entries.get(name));
            }

            @Override
            public void close() throws IOException {
                zf.close();
            }
        };
    }
}
