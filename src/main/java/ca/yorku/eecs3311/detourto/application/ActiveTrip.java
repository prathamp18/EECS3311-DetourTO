package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State pattern (context): a trip the rider is taking (feature F07). {@link #update} hands the
 * new data to the current {@link TripState}, which decides what happens next.
 */
public final class ActiveTrip {

    /** Delays below this are not worth a warning when there is no arrive-by time. */
    static final int LATE_WARNING_MINUTES = 10;
    static final int MIN_CONNECTION_SECONDS = 60;

    /** One state change, for the trip's history. */
    public record Change(LocalDateTime at, String from, String to, String reason) {
    }

    private final String id;
    private final TransitNetwork network;
    private final LocalDateTime deadline;
    private final StrategyKind strategy;
    private final EventBus eventBus;
    private Itinerary itinerary;
    private TripState state = new PlannedState();
    private LocalDateTime projectedArrival;
    private LocalDateTime now;
    private TripRisk lastRisk;
    private final Set<String> notifiedKeys = new HashSet<>();
    private final List<Change> changes = new ArrayList<>();
    private List<Itinerary> rerouteOptions = List.of();
    /** Missed-connection warnings stay until arrival (we cannot see whether the rider made it). */
    private final Map<String, String> stickyRisks = new java.util.LinkedHashMap<>();
    private long stickyDelaySeconds;

    public ActiveTrip(String id, Itinerary itinerary, LocalDateTime deadline, StrategyKind strategy,
                      TransitNetwork network, EventBus eventBus) {
        this.id = id;
        this.itinerary = itinerary;
        this.deadline = deadline;
        this.strategy = strategy;
        this.network = network;
        this.eventBus = eventBus;
        this.projectedArrival = itinerary.arrival();
    }

    /** New real-time data: the current state decides what to do. */
    public synchronized void update(RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime at) {
        this.now = at;
        state.handle(this, snap, ds, at);
    }

    public synchronized void setState(TripState next, String reason) {
        String from = state.name();
        state = next;
        changes.add(new Change(now, from, next.name(), reason));
        eventBus.publish(TransitEvent.of(EventType.TRIP_STATE_CHANGED, Map.of(
                "tripId", id, "from", from, "to", next.name(), "reason", reason)));
        if (next instanceof AtRiskState && lastRisk != null) {
            notifyRiskIfNew(lastRisk);
        }
    }

    /** Publishes TRIP_AT_RISK unless this exact risk was already reported. */
    void notifyRiskIfNew(TripRisk risk) {
        if (notifiedKeys.addAll(risk.key())) {
            eventBus.publish(TransitEvent.of(EventType.TRIP_AT_RISK, Map.of(
                    "tripId", id, "reason", risk.reason(), "projectedArrival", risk.projectedArrival().toString())));
        }
    }

    /** Follow a new itinerary from now on (the rider accepted a reroute). */
    public synchronized void replaceItinerary(Itinerary next) {
        this.itinerary = next;
        this.projectedArrival = next.arrival();
        this.rerouteOptions = List.of();
        notifiedKeys.clear();
        stickyRisks.clear();
        stickyDelaySeconds = 0;
        setState(new ReroutedState(), "rerouted: " + String.join(", ", next.routeIds().stream()
                .map(r -> network.findRoute(r).map(x -> x.label()).orElse(r)).toList()));
    }

    // ------------------------------------------------------------------ assessment

    boolean isFinished(LocalDateTime at) {
        return !at.isBefore(projectedArrival);
    }

    /** Checks the rest of the trip against disruptions and delays; remembers the projection. */
    TripRisk assess(RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime at) {
        List<String> reasons = new ArrayList<>();
        Set<String> key = new LinkedHashSet<>();
        List<RideLeg> rides = new ArrayList<>();
        for (Leg leg : itinerary.legs()) {
            if (leg instanceof RideLeg r && leg.end().isAfter(at)) {
                rides.add(r);
            }
        }
        for (Disruption d : ds) {
            if (!d.appliesByDefault()) {
                continue;
            }
            for (RideLeg r : rides) {
                String why = blocks(d, r);
                if (why != null) {
                    reasons.add(why);
                    key.add(d.id());
                    break;
                }
            }
        }
        // delays and connections
        long delay = 0;
        for (int i = 0; i < rides.size(); i++) {
            RideLeg r = rides.get(i);
            int alightDelay = snap.delayAt(r.trip().id(), r.to().id(), r.alightPos() + 1).orElse(0);
            delay = alightDelay;
            if (i + 1 < rides.size()) {
                RideLeg next = rides.get(i + 1);
                int boardDelay = snap.delayAt(next.trip().id(), next.from().id(), next.boardPos() + 1).orElse(0);
                long walk = java.time.Duration.between(r.end(), next.start()).getSeconds()
                        - waitBetween(r, next);
                LocalDateTime arriveAtTransfer = r.end().plusSeconds(alightDelay + Math.max(walk, 0));
                if (arriveAtTransfer.plusSeconds(MIN_CONNECTION_SECONDS).isAfter(next.start().plusSeconds(boardDelay))
                        && alightDelay > boardDelay) {
                    stickyRisks.put("missed:" + next.trip().id(), "may miss the " + next.route().label() + " at "
                            + shortName(next.from()) + " (" + r.route().label() + " running "
                            + Math.round(alightDelay / 60.0) + " min late)");
                    stickyDelaySeconds = Math.max(stickyDelaySeconds, alightDelay);
                }
            }
        }
        stickyRisks.forEach((k, why) -> {
            key.add(k);
            reasons.add(why);
        });
        projectedArrival = itinerary.arrival().plusSeconds(Math.max(delay, stickyDelaySeconds));
        if (deadline != null && projectedArrival.isAfter(deadline)) {
            reasons.add("projected arrival " + projectedArrival.toLocalTime() + " is after your arrive-by time "
                    + deadline.toLocalTime());
            key.add("late:" + deadline);
        } else if (deadline == null && delay >= LATE_WARNING_MINUTES * 60L) {
            reasons.add("running " + Math.round(delay / 60.0) + " min late");
            key.add("late");
        }
        TripRisk risk = reasons.isEmpty() ? TripRisk.none(projectedArrival)
                : new TripRisk(true, String.join("; ", reasons), projectedArrival, key);
        lastRisk = risk;
        return risk;
    }

    /** Seconds the rider waits between two rides (walk legs are the rest of the gap). */
    private long waitBetween(RideLeg a, RideLeg b) {
        long walk = 0;
        boolean between = false;
        for (Leg leg : itinerary.legs()) {
            if (leg == a) {
                between = true;
            } else if (leg == b) {
                break;
            } else if (between) {
                walk += leg.seconds();
            }
        }
        return java.time.Duration.between(a.end(), b.start()).getSeconds() - walk;
    }

    /** Why the disruption stops this ride, or null if it doesn't. */
    private String blocks(Disruption d, RideLeg r) {
        String route = r.route().id();
        if (d.kind() == DisruptionKind.CLOSURE && d.closedStopIds().isEmpty() && d.affectedRouteIds().contains(route)) {
            return r.route().label() + " is not running";
        }
        if (d.kind() == DisruptionKind.ELEVATOR_OUTAGE) {
            if (strategy == StrategyKind.ACCESSIBLE && (in(d, r.from()) || in(d, r.to()))) {
                return "no step-free access at " + shortName(in(d, r.from()) ? r.from() : r.to());
            }
            return null;
        }
        if (in(d, r.from()) || in(d, r.to())) {
            return shortName(in(d, r.from()) ? r.from() : r.to()) + " is closed (" + d.summary() + ")";
        }
        if (d.kind() == DisruptionKind.CLOSURE && d.affectedRouteIds().contains(route)) {
            for (int pos = r.boardPos() + 1; pos < r.alightPos(); pos++) {
                if (in(d, network.stopAt(r.trip().stopIndexAt(pos)))) {
                    return r.route().label() + " is not running through " + shortName(network.stopAt(r.trip().stopIndexAt(pos)));
                }
            }
        }
        return null;
    }

    private static boolean in(Disruption d, Stop s) {
        return d.closedStopIds().contains(s.id()) || (s.parentStation() != null && d.closedStopIds().contains(s.parentStation()));
    }

    private static String shortName(Stop s) {
        int dash = s.name().indexOf(" - ");
        return dash > 0 ? s.name().substring(0, dash) : s.name();
    }

    // ------------------------------------------------------------------ getters

    public String id() {
        return id;
    }

    public synchronized Itinerary itinerary() {
        return itinerary;
    }

    public synchronized TripState getState() {
        return state;
    }

    public synchronized LocalDateTime projectedArrival() {
        return projectedArrival;
    }

    public LocalDateTime deadline() {
        return deadline;
    }

    public StrategyKind strategy() {
        return strategy;
    }

    public synchronized List<Change> changes() {
        return List.copyOf(changes);
    }

    public synchronized TripRisk lastRisk() {
        return lastRisk;
    }

    public synchronized List<Itinerary> rerouteOptions() {
        return rerouteOptions;
    }

    synchronized void setRerouteOptions(List<Itinerary> options) {
        this.rerouteOptions = List.copyOf(options);
    }

    /** Where the rider can start a new route from at {@code at}. */
    synchronized Stop rerouteOrigin(LocalDateTime at) {
        for (Leg leg : itinerary.legs()) {
            if (leg.end().isAfter(at)) {
                return leg.start().isAfter(at) ? leg.from() : leg.to();
            }
        }
        return itinerary.destination();
    }
}
