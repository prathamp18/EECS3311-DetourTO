package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes itineraries with RAPTOR (Delling, Pajor and Werneck, "Round-Based Public Transit
 * Routing", 2012).
 *
 * <p>Round k finds the earliest arrival at every stop using exactly k vehicles. Each round scans
 * only the route patterns that serve a stop improved in the previous round, then relaxes short
 * walks between nearby stops. Because rounds count vehicles, the destination's arrival per round
 * directly gives the trade-off "faster but one more transfer". The planner runs the search a few
 * times with later start times to collect several departure options.
 *
 * <p>Closures come from the {@link NetworkView}: a closed stop cannot be used to board, alight or
 * walk to (vehicles still pass through it), and a closed route is not scanned at all.
 */
public final class JourneyPlanner {

    public static final int DEFAULT_MAX_ROUNDS = 5;
    public static final int MAX_OPTIONS = 5;
    /** Arrive-by searches start this long before the deadline. */
    static final int ARRIVE_BY_WINDOW_SECONDS = 2 * 3600;
    private static final int MAX_SEARCHES = 40;
    private static final int DAY = 86_400;
    private static final int INF = Integer.MAX_VALUE / 2;

    private final TransitNetwork network;
    private final int maxRounds;

    public JourneyPlanner(TransitNetwork network) {
        this(network, DEFAULT_MAX_ROUNDS);
    }

    public JourneyPlanner(TransitNetwork network, int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be at least 1");
        }
        this.network = network;
        this.maxRounds = maxRounds;
    }

    public TransitNetwork network() {
        return network;
    }

    /** Plans on the network with nothing closed, using the query's own strategy. */
    public List<Itinerary> plan(TripQuery q) {
        return plan(q, network.fullView(), RouteScoringStrategy.of(q.strategyKind()));
    }

    /** Plans on a view (closures applied), using the query's own strategy. */
    public List<Itinerary> plan(TripQuery q, NetworkView view) {
        return plan(q, view, RouteScoringStrategy.of(q.strategyKind()));
    }

    /**
     * Up to {@link #MAX_OPTIONS} non-dominated itineraries that the strategy accepts, best score
     * first, with ids opt-1, opt-2, ... An empty list means no route exists under the given
     * closures and strategy.
     */
    public List<Itinerary> plan(TripQuery q, NetworkView view, RouteScoringStrategy strategy) {
        List<Itinerary> found = q.isArriveBy()
                ? planArriveBy(q, view, strategy) : planDepartAt(q, view, strategy);
        List<Itinerary> accepted = new ArrayList<>();
        for (Itinerary it : nonDominated(found)) {
            if (it.legs().stream().allMatch(leg -> strategy.admissible(leg, q, view))) {
                accepted.add(it);
            }
        }
        Comparator<Itinerary> tieBreak = q.isArriveBy()
                ? Comparator.comparing(Itinerary::departure, Comparator.reverseOrder())
                : Comparator.comparing(Itinerary::arrival);
        List<Itinerary> sorted = new ArrayList<>(accepted);
        sorted.sort(Comparator.<Itinerary>comparingDouble(it -> strategy.score(it, q))
                .thenComparing(tieBreak)
                .thenComparingInt(Itinerary::transfers));
        List<Itinerary> out = new ArrayList<>();
        for (Itinerary it : sorted) {
            if (out.size() == MAX_OPTIONS) {
                break;
            }
            out.add(it.withId("opt-" + (out.size() + 1)));
        }
        return out;
    }

    private List<Itinerary> planDepartAt(TripQuery q, NetworkView view, RouteScoringStrategy strategy) {
        LocalDate date = q.departAt().toLocalDate();
        int t = q.departAt().toLocalTime().toSecondOfDay();
        Map<String, Itinerary> options = new LinkedHashMap<>();
        for (int i = 0; i < MAX_SEARCHES && options.size() < MAX_OPTIONS; i++) {
            List<Itinerary> batch = new Search(q, view, strategy, date, t).run();
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(it -> options.putIfAbsent(it.signature(), it));
            int next = nextStart(batch, date);
            if (next <= t) {
                break; // walk-only trip: later searches would find the same thing
            }
            t = next;
        }
        return new ArrayList<>(options.values());
    }

    /**
     * Arrive-by: search forward from (deadline - window), keep moving the start time to just after
     * the latest-found first departure, and keep every option that still arrives in time. The
     * options that leave latest are returned.
     */
    private List<Itinerary> planArriveBy(TripQuery q, NetworkView view, RouteScoringStrategy strategy) {
        LocalDateTime deadline = q.arriveBy();
        LocalDate date = deadline.toLocalDate();
        int t = Math.max(0, deadline.toLocalTime().toSecondOfDay() - ARRIVE_BY_WINDOW_SECONDS);
        Map<String, Itinerary> inTime = new LinkedHashMap<>();
        for (int i = 0; i < MAX_SEARCHES; i++) {
            List<Itinerary> batch = new Search(q, view, strategy, date, t).run();
            boolean anyInTime = false;
            for (Itinerary it : batch) {
                if (!it.arrival().isAfter(deadline)) {
                    inTime.putIfAbsent(it.signature(), it);
                    anyInTime = true;
                }
            }
            if (!anyInTime) {
                break;
            }
            int next = nextStart(batch, date);
            if (next <= t) {
                break;
            }
            t = next;
        }
        return inTime.values().stream()
                .sorted(Comparator.comparing(Itinerary::departure).reversed())
                .limit(MAX_OPTIONS)
                .toList();
    }

    /**
     * Drops options that are no better in any way than another one: leaving no later, arriving no
     * earlier, with no fewer transfers and no less walking.
     */
    static List<Itinerary> nonDominated(List<Itinerary> options) {
        List<Itinerary> out = new ArrayList<>();
        for (Itinerary a : options) {
            boolean dominated = false;
            for (Itinerary b : options) {
                if (a != b && dominates(b, a)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                out.add(a);
            }
        }
        return out;
    }

    private static boolean dominates(Itinerary b, Itinerary a) {
        boolean noWorse = !b.departure().isBefore(a.departure()) && !b.arrival().isAfter(a.arrival())
                && b.transfers() <= a.transfers() && b.walkMeters() <= a.walkMeters();
        boolean better = b.departure().isAfter(a.departure()) || b.arrival().isBefore(a.arrival())
                || b.transfers() < a.transfers() || b.walkMeters() < a.walkMeters();
        return noWorse && better;
    }

    /** One second after the earliest first-vehicle departure in the batch. */
    private static int nextStart(List<Itinerary> batch, LocalDate date) {
        int min = INF;
        for (Itinerary it : batch) {
            List<RideLeg> rides = it.rideLegs();
            if (!rides.isEmpty()) {
                min = Math.min(min, secondsSince(date, rides.get(0).start()));
            }
        }
        return min == INF ? -1 : min + 1;
    }

    private static int secondsSince(LocalDate date, LocalDateTime time) {
        return (int) java.time.Duration.between(date.atStartOfDay(), time).getSeconds();
    }

    /**
     * The next {@code n} scheduled departures from a stop (or all platforms of a station) at or
     * after {@code from}, within the next 3 hours. Last stops of trips are skipped because nobody
     * can board there. Used by the arrivals board (F08).
     */
    public List<Arrival> scheduledDepartures(Stop stop, LocalDateTime from, int n) {
        LocalDate date = from.toLocalDate();
        int start = from.toLocalTime().toSecondOfDay();
        int end = start + 3 * 3600;
        List<Arrival> out = new ArrayList<>();
        for (Stop platform : network.boardingStopsFor(stop)) {
            int idx = network.indexOf(platform);
            for (TransitNetwork.PatternStop ps : network.patternsAt(idx)) {
                RoutePattern pattern = network.patterns().get(ps.pattern());
                if (ps.position() == pattern.stopCount() - 1) {
                    continue;
                }
                for (int offset : new int[] {-DAY, 0}) {
                    Set<String> services = network.calendar().activeServices(date.plusDays(offset / DAY));
                    for (Trip trip : pattern.trips()) {
                        int dep = trip.departureAt(ps.position()) + offset;
                        if (dep >= start && dep <= end && services.contains(trip.serviceId())) {
                            LocalDateTime t = date.atStartOfDay().plusSeconds(dep);
                            out.add(new Arrival(pattern.route(), platform, trip.id(), trip.headsign(),
                                    ps.position() + 1, t, t, 0, false));
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparing(Arrival::scheduled));
        return out.size() > n ? List.copyOf(out.subList(0, n)) : List.copyOf(out);
    }

    // ================================================================== one RAPTOR run

    /** State of a single RAPTOR search. Times are seconds after midnight of {@code date}. */
    private final class Search {

        private final TripQuery q;
        private final NetworkView view;
        private final RouteScoringStrategy strategy;
        private final LocalDate date;
        private final int departure;
        private final int n;
        private final int rounds;
        /** Service days to look at: yesterday's late trips, today, tomorrow's early trips. */
        private final int[] dayOffsets = {-DAY, 0, DAY};
        private final List<Set<String>> servicesByOffset = new ArrayList<>();

        private final int[][] tau;        // best arrival per round (ride or walk)
        private final int[][] rideArr;    // arrival by riding (or start, for origins in round 0)
        private final Trip[][] rideTrip;
        private final int[][] rideBoard;
        private final int[][] rideAlight;
        private final int[][] rideOffset;
        private final int[][] walkFrom;   // stop we walked from in this round, or -1
        private final int[] best;
        private final Set<Integer> origins = new HashSet<>();
        private final Set<Integer> targets = new HashSet<>();
        private int bestTarget = INF;

        Search(TripQuery q, NetworkView view, RouteScoringStrategy strategy, LocalDate date,
               int departure) {
            this.q = q;
            this.view = view;
            this.strategy = strategy;
            this.date = date;
            this.departure = departure;
            this.n = network.stopCount();
            this.rounds = maxRounds + 1;
            for (int off : dayOffsets) {
                servicesByOffset.add(network.calendar().activeServices(date.plusDays(off / DAY)));
            }
            tau = filled(rounds, n, INF);
            rideArr = filled(rounds, n, INF);
            rideTrip = new Trip[rounds][n];
            rideBoard = new int[rounds][n];
            rideAlight = new int[rounds][n];
            rideOffset = new int[rounds][n];
            walkFrom = filled(rounds, n, -1);
            best = new int[n];
            java.util.Arrays.fill(best, INF);
            for (Stop s : network.boardingStopsFor(q.origin())) {
                int i = network.indexOf(s);
                if (!view.isStopClosed(i)) {
                    origins.add(i);
                }
            }
            for (Stop s : network.boardingStopsFor(q.destination())) {
                int i = network.indexOf(s);
                if (!view.isStopClosed(i)) {
                    targets.add(i);
                }
            }
        }

        List<Itinerary> run() {
            if (origins.isEmpty() || targets.isEmpty()) {
                return List.of();
            }
            Set<Integer> marked = new HashSet<>();
            for (int o : origins) {
                tau[0][o] = departure;
                rideArr[0][o] = departure;
                best[o] = departure;
                updateTarget(o, departure);
                marked.add(o);
            }
            marked.addAll(relaxFootpaths(0, marked));

            for (int k = 1; k < rounds && !marked.isEmpty(); k++) {
                Set<Integer> improved = scanPatterns(k, marked);
                improved.addAll(relaxFootpaths(k, improved));
                marked = improved;
            }
            return collect();
        }

        /** Rides: scan every pattern serving a stop marked in round k-1. */
        private Set<Integer> scanPatterns(int k, Set<Integer> marked) {
            Map<Integer, Integer> queue = new LinkedHashMap<>(); // pattern -> earliest position
            for (int s : marked) {
                for (TransitNetwork.PatternStop ps : network.patternsAt(s)) {
                    queue.merge(ps.pattern(), ps.position(), Math::min);
                }
            }
            Set<Integer> improved = new HashSet<>();
            Set<String> avoidRoutes = q.avoidRouteIds();
            Set<TransitMode> avoidModes = q.avoidModes();
            for (Map.Entry<Integer, Integer> e : queue.entrySet()) {
                RoutePattern pattern = network.patterns().get(e.getKey());
                Route route = pattern.route();
                if (view.isRouteClosed(route) || avoidRoutes.contains(route.id())
                        || avoidModes.contains(route.mode())) {
                    continue;
                }
                Trip trip = null;
                int offset = 0;
                int boardPos = -1;
                for (int pos = e.getValue(); pos < pattern.stopCount(); pos++) {
                    int s = pattern.stopAt(pos);
                    boolean open = !view.isStopClosed(s);
                    Stop stop = network.stopAt(s);
                    if (trip != null && open && strategy.canAlight(stop, view)) {
                        int arr = trip.arrivalAt(pos) + offset;
                        if (arr < Math.min(best[s], bestTarget)) {
                            tau[k][s] = arr;
                            rideArr[k][s] = arr;
                            rideTrip[k][s] = trip;
                            rideBoard[k][s] = boardPos;
                            rideAlight[k][s] = pos;
                            rideOffset[k][s] = offset;
                            best[s] = arr;
                            updateTarget(s, arr);
                            improved.add(s);
                        }
                    }
                    int ready = tau[k - 1][s];
                    if (open && ready < INF && strategy.canBoard(stop, route, view)
                            && (trip == null || ready <= trip.departureAt(pos) + offset)) {
                        int[] caught = earliestTrip(pattern, pos, ready);
                        if (caught != null) {
                            Trip t = pattern.trips().get(caught[0]);
                            int dep = t.departureAt(pos) + caught[1];
                            if (trip == null || dep < trip.departureAt(pos) + offset) {
                                trip = t;
                                offset = caught[1];
                                boardPos = pos;
                            }
                        }
                    }
                }
            }
            return improved;
        }

        /**
         * {trip index, day offset} of the first trip leaving position {@code pos} at or after
         * {@code ready} on any of the three service days, or null.
         */
        private int[] earliestTrip(RoutePattern pattern, int pos, int ready) {
            List<Trip> trips = pattern.trips();
            int[] bestHit = null;
            int bestDep = INF;
            for (int d = 0; d < dayOffsets.length; d++) {
                int off = dayOffsets[d];
                Set<String> services = servicesByOffset.get(d);
                // trips are sorted by first departure; with no overtaking that is also the order
                // at every later stop, so binary-search the first candidate and scan forward
                int lo = 0;
                int hi = trips.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (trips.get(mid).departureAt(pos) + off < ready) {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
                for (int i = lo; i < trips.size(); i++) {
                    Trip t = trips.get(i);
                    int dep = t.departureAt(pos) + off;
                    if (dep >= bestDep) {
                        break;
                    }
                    if (dep >= ready && services.contains(t.serviceId())) {
                        bestHit = new int[] {i, off};
                        bestDep = dep;
                        break;
                    }
                }
            }
            return bestHit;
        }

        /** Walks from stops reached by riding in round k. Walks never chain. */
        private Set<Integer> relaxFootpaths(int k, Set<Integer> from) {
            Set<Integer> improved = new HashSet<>();
            for (int s : from) {
                int start = rideArr[k][s];
                if (start >= INF) {
                    continue;
                }
                for (TransitNetwork.Footpath fp : network.footpathsFrom(s)) {
                    int to = fp.toStop();
                    if (view.isStopClosed(to)) {
                        continue;
                    }
                    int arr = start + fp.seconds();
                    if (arr < Math.min(best[to], bestTarget)) {
                        tau[k][to] = arr;
                        walkFrom[k][to] = s;
                        best[to] = arr;
                        updateTarget(to, arr);
                        improved.add(to);
                    }
                }
            }
            return improved;
        }

        private void updateTarget(int stop, int arrival) {
            if (targets.contains(stop) && arrival < bestTarget) {
                bestTarget = arrival;
            }
        }

        /** One itinerary per round that improved the arrival at the destination (Pareto set). */
        private List<Itinerary> collect() {
            List<Itinerary> out = new ArrayList<>();
            int bestSoFar = INF;
            for (int k = 0; k < rounds; k++) {
                int arrival = INF;
                int stop = -1;
                for (int t : targets) {
                    if (tau[k][t] < arrival) {
                        arrival = tau[k][t];
                        stop = t;
                    }
                }
                if (stop < 0 || arrival >= bestSoFar) {
                    continue;
                }
                if (k == 0 && walkFrom[0][stop] < 0) {
                    continue; // origin and destination are the same stop: nothing to plan
                }
                Itinerary it = reconstruct(k, stop);
                if (it.walkMeters() <= q.maxWalkMeters()) {
                    out.add(it);
                    bestSoFar = arrival;
                }
            }
            return out;
        }

        private Itinerary reconstruct(int k, int stop) {
            List<Leg> legs = new ArrayList<>();
            int s = stop;
            int round = k;
            while (true) {
                int w = walkFrom[round][s];
                if (w >= 0 && tau[round][s] < rideArr[round][s]) {
                    legs.add(0, walk(w, s, rideArr[round][w], tau[round][s]));
                    s = w;
                }
                if (round == 0) {
                    break;
                }
                Trip trip = rideTrip[round][s];
                int off = rideOffset[round][s];
                int b = rideBoard[round][s];
                int a = rideAlight[round][s];
                int boardStop = trip.stopIndexAt(b);
                legs.add(0, new RideLeg(trip, b, a, network.stopAt(boardStop), network.stopAt(s),
                        at(trip.departureAt(b) + off), at(trip.arrivalAt(a) + off)));
                s = boardStop;
                round--;
            }
            // leave just in time for the first vehicle instead of walking and then waiting
            if (legs.size() >= 2 && legs.get(0) instanceof WalkLeg first
                    && legs.get(1) instanceof RideLeg ride) {
                LocalDateTime end = ride.start();
                legs.set(0, new WalkLeg(first.from(), first.to(),
                        end.minusSeconds(first.seconds()), end, first.meters()));
            }
            return new Itinerary("draft", legs);
        }

        private WalkLeg walk(int from, int to, int start, int end) {
            Stop a = network.stopAt(from);
            Stop b = network.stopAt(to);
            int meters = (int) Math.round(a.distanceMetersTo(b) * TransitNetwork.DETOUR_FACTOR);
            return new WalkLeg(a, b, at(start), at(end), meters);
        }

        private LocalDateTime at(int seconds) {
            return date.atStartOfDay().plusSeconds(seconds);
        }

        private int[][] filled(int rows, int cols, int value) {
            int[][] a = new int[rows][cols];
            for (int[] row : a) {
                java.util.Arrays.fill(row, value);
            }
            return a;
        }
    }
}
