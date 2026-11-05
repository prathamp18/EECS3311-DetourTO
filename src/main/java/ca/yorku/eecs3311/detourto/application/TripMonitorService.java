package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Feature F07: keeps the rider's active trips up to date. Each {@link #poll} reads the live feed
 * and alerts once and passes them to every trip. When a trip becomes at risk, reroute options
 * are computed so the rider can accept one.
 */
public final class TripMonitorService {

    private final RealtimeFeed feed;
    private final AlertService alerts;
    private final EventBus eventBus;
    private final TransitNetwork network;
    private final BiFunction<ActiveTrip, LocalDateTime, List<Itinerary>> rerouter;
    private final Map<String, ActiveTrip> trips = new LinkedHashMap<>();
    private int nextId = 1;

    public TripMonitorService(RealtimeFeed feed, AlertService alerts, EventBus eventBus, TransitNetwork network,
                              BiFunction<ActiveTrip, LocalDateTime, List<Itinerary>> rerouter) {
        this.feed = feed;
        this.alerts = alerts;
        this.eventBus = eventBus;
        this.network = network;
        this.rerouter = rerouter;
    }

    public synchronized ActiveTrip start(Itinerary itinerary, LocalDateTime deadline, StrategyKind strategy) {
        ActiveTrip t = new ActiveTrip("trip-" + nextId++, itinerary, deadline, strategy, network, eventBus);
        trips.put(t.id(), t);
        return t;
    }

    /** Updates every active trip with the current feed and alerts. */
    public synchronized void poll(LocalDateTime now) {
        RealtimeSnapshot snap = feed.snapshot();
        List<Disruption> ds = alerts.currentDisruptions(now);
        for (ActiveTrip t : List.copyOf(trips.values())) {
            boolean wasAtRisk = t.getState() instanceof AtRiskState;
            t.update(snap, ds, now);
            if (t.getState() instanceof AtRiskState && (!wasAtRisk || t.rerouteOptions().isEmpty())) {
                t.setRerouteOptions(rerouter.apply(t, now));
            }
        }
    }

    /** The rider picks a reroute option (by id, e.g. "opt-1"). */
    public synchronized Optional<Itinerary> applyReroute(String tripId, String optionId) {
        ActiveTrip t = trips.get(tripId);
        if (t == null) {
            return Optional.empty();
        }
        Optional<Itinerary> choice = t.rerouteOptions().stream().filter(i -> i.id().equals(optionId)).findFirst();
        choice.ifPresent(t::replaceItinerary);
        return choice;
    }

    public synchronized void stop(String tripId) {
        trips.remove(tripId);
    }

    public synchronized Optional<ActiveTrip> find(String tripId) {
        return Optional.ofNullable(trips.get(tripId));
    }

    public synchronized List<ActiveTrip> trips() {
        return new ArrayList<>(trips.values());
    }
}
