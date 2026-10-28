package ca.yorku.eecs3311.detourto.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The whole static transit network loaded from GTFS: stops, routes, trips, transfers and the
 * service calendar, plus the indexes the journey planner needs (route patterns per stop and
 * walking footpaths between nearby stops).
 *
 * <p>One instance is shared by the planner, the place resolver and the grounding validator.
 * {@link #replaceWith(TransitNetwork)} swaps in freshly downloaded data without changing that
 * shared reference (feature F13).
 */
public final class TransitNetwork {

    /** Walking speed used for footpaths, metres per second (slow, to leave margin). */
    public static final double WALK_SPEED_MPS = 1.2;
    /** Straight-line distance is multiplied by this to approximate street distance. */
    public static final double DETOUR_FACTOR = 1.3;
    /** Stops closer than this (straight line) get a generated walking transfer. */
    public static final double MAX_FOOTPATH_METERS = 300;
    /** Minimum time to change between platforms of the same station. */
    public static final int MIN_TRANSFER_SECONDS = 60;

    /** A walk from one stop to another, used by the planner. */
    public record Footpath(int toStop, int seconds) {
    }

    /** Where a pattern visits a stop: pattern index and position along the pattern. */
    public record PatternStop(int pattern, int position) {
    }

    private List<Stop> stops;
    private Map<String, Integer> stopIndexById;
    private Map<String, Route> routes;
    private Map<String, Trip> trips;
    private List<Transfer> transfers;
    private ServiceCalendar calendar;
    private List<RoutePattern> patterns;
    private List<List<PatternStop>> patternsByStop;
    private List<List<Footpath>> footpaths;
    private Map<String, List<Stop>> childrenByStation;

    public TransitNetwork(List<Stop> stops, Collection<Route> routes, Collection<Trip> trips,
                          List<Transfer> transfers, ServiceCalendar calendar) {
        init(stops, routes, trips, transfers, calendar);
    }

    private void init(List<Stop> stopList, Collection<Route> routeList, Collection<Trip> tripList,
                      List<Transfer> transferList, ServiceCalendar cal) {
        this.stops = List.copyOf(stopList);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            idx.put(stops.get(i).id(), i);
        }
        this.stopIndexById = idx;
        Map<String, Route> r = new LinkedHashMap<>();
        routeList.forEach(route -> r.put(route.id(), route));
        this.routes = r;
        Map<String, Trip> t = new LinkedHashMap<>();
        tripList.forEach(trip -> t.put(trip.id(), trip));
        this.trips = t;
        this.transfers = List.copyOf(transferList);
        this.calendar = cal;
        this.childrenByStation = buildChildren();
        this.patterns = buildPatterns();
        this.patternsByStop = buildPatternsByStop();
        this.footpaths = buildFootpaths();
    }

    // ------------------------------------------------------------------ lookups

    public int stopCount() {
        return stops.size();
    }

    public Stop stopAt(int index) {
        return stops.get(index);
    }

    public List<Stop> stops() {
        return stops;
    }

    public int indexOf(Stop stop) {
        Integer i = stopIndexById.get(stop.id());
        if (i == null) {
            throw new IllegalArgumentException("Unknown stop " + stop.id());
        }
        return i;
    }

    /** Stop by GTFS id; throws if unknown (use {@link #findStop} for optional lookups). */
    public Stop stopById(String id) {
        return findStop(id).orElseThrow(() -> new IllegalArgumentException("Unknown stop id: " + id));
    }

    public Optional<Stop> findStop(String id) {
        Integer i = stopIndexById.get(id);
        return i == null ? Optional.empty() : Optional.of(stops.get(i));
    }

    public Optional<Route> findRoute(String id) {
        return Optional.ofNullable(routes.get(id));
    }

    public Collection<Route> routes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    public Optional<Trip> findTrip(String id) {
        return Optional.ofNullable(trips.get(id));
    }

    public int tripCount() {
        return trips.size();
    }

    public List<Transfer> transfers() {
        return transfers;
    }

    public ServiceCalendar calendar() {
        return calendar;
    }

    public List<RoutePattern> patterns() {
        return patterns;
    }

    public List<PatternStop> patternsAt(int stopIndex) {
        return patternsByStop.get(stopIndex);
    }

    public List<Footpath> footpathsFrom(int stopIndex) {
        return footpaths.get(stopIndex);
    }

    /** Platforms of a station, or the stop itself when it is not a station. */
    public List<Stop> boardingStopsFor(Stop stop) {
        if (stop.station()) {
            return childrenByStation.getOrDefault(stop.id(), List.of());
        }
        return List.of(stop);
    }

    /** All stops within {@code radiusM} metres, nearest first. */
    public List<Stop> stopsNear(double lat, double lon, int radiusM) {
        List<Stop> out = new ArrayList<>();
        for (Stop s : stops) {
            if (s.distanceMetersTo(lat, lon) <= radiusM) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Double.compare(a.distanceMetersTo(lat, lon), b.distanceMetersTo(lat, lon)));
        return out;
    }

    /** A view of this network with stops and routes closed (used for disruptions). */
    public NetworkView withClosures(Set<String> closedStopIds, Set<String> closedRouteIds,
                                    Set<String> elevatorOutageStopIds) {
        return new NetworkView(this, closedStopIds, closedRouteIds, elevatorOutageStopIds);
    }

    /** A view with nothing closed. */
    public NetworkView fullView() {
        return withClosures(Set.of(), Set.of(), Set.of());
    }

    /**
     * Replaces all data with a freshly loaded network, keeping this object's identity so every
     * component holding a reference sees the new data (feature F13, refresh transit data).
     */
    public synchronized void replaceWith(TransitNetwork other) {
        init(other.stops, other.routes.values(), other.trips.values(), other.transfers, other.calendar);
    }

    // ------------------------------------------------------------------ index building

    private Map<String, List<Stop>> buildChildren() {
        Map<String, List<Stop>> out = new HashMap<>();
        for (Stop s : stops) {
            if (s.parentStation() != null && !s.parentStation().isBlank()) {
                out.computeIfAbsent(s.parentStation(), k -> new ArrayList<>()).add(s);
            }
        }
        return out;
    }

    private List<RoutePattern> buildPatterns() {
        Map<String, List<Trip>> groups = new LinkedHashMap<>();
        for (Trip trip : trips.values()) {
            String key = trip.route().id() + "|" + java.util.Arrays.toString(trip.stopSequence());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(trip);
        }
        List<RoutePattern> out = new ArrayList<>(groups.size());
        for (List<Trip> group : groups.values()) {
            Trip first = group.get(0);
            out.add(new RoutePattern(out.size(), first.route(), first.stopSequence(), group));
        }
        return List.copyOf(out);
    }

    private List<List<PatternStop>> buildPatternsByStop() {
        List<List<PatternStop>> out = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            out.add(new ArrayList<>());
        }
        for (RoutePattern p : patterns) {
            for (int pos = 0; pos < p.stopCount(); pos++) {
                out.get(p.stopAt(pos)).add(new PatternStop(p.index(), pos));
            }
        }
        return out;
    }

    /**
     * Walking links: explicit transfers.txt entries, plus every pair of served stops within
     * {@link #MAX_FOOTPATH_METERS}. A coarse lat/lon grid keeps this fast for ~10,000 TTC stops.
     */
    private List<List<Footpath>> buildFootpaths() {
        int n = stops.size();
        List<Map<Integer, Integer>> best = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            best.add(new HashMap<>());
        }
        double cell = 0.004; // roughly 300-450 m
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Stop s = stops.get(i);
            if (s.station()) {
                continue;
            }
            grid.computeIfAbsent(cellKey(s.lat(), s.lon(), cell), k -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < n; i++) {
            Stop a = stops.get(i);
            if (a.station()) {
                continue;
            }
            long cy = (long) Math.floor(a.lat() / cell);
            long cx = (long) Math.floor(a.lon() / cell);
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    for (int j : grid.getOrDefault(((cy + dy) << 32) ^ (cx + dx), List.of())) {
                        if (j == i) {
                            continue;
                        }
                        Stop b = stops.get(j);
                        double d = a.distanceMetersTo(b);
                        if (d <= MAX_FOOTPATH_METERS) {
                            int secs = (int) Math.ceil(d * DETOUR_FACTOR / WALK_SPEED_MPS);
                            if (sameStation(a, b)) {
                                secs = Math.max(secs, MIN_TRANSFER_SECONDS);
                            }
                            best.get(i).merge(j, Math.max(secs, 30), Math::min);
                        }
                    }
                }
            }
        }
        for (Transfer t : transfers) {
            Integer from = stopIndexById.get(t.from().id());
            Integer to = stopIndexById.get(t.to().id());
            if (from != null && to != null && !from.equals(to)) {
                best.get(from).put(to, Math.max(t.minSeconds(), 0));
            }
        }
        List<List<Footpath>> out = new ArrayList<>(n);
        for (Map<Integer, Integer> m : best) {
            List<Footpath> list = new ArrayList<>(m.size());
            m.forEach((to, secs) -> list.add(new Footpath(to, secs)));
            out.add(List.copyOf(list));
        }
        return out;
    }

    private static long cellKey(double lat, double lon, double cell) {
        long cy = (long) Math.floor(lat / cell);
        long cx = (long) Math.floor(lon / cell);
        return (cy << 32) ^ cx;
    }

    private static boolean sameStation(Stop a, Stop b) {
        return a.parentStation() != null && !a.parentStation().isBlank()
                && a.parentStation().equals(b.parentStation());
    }
}
