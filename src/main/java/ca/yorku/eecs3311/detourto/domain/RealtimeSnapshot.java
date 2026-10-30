package ca.yorku.eecs3311.detourto.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real-time trip predictions at one moment: per trip, the delay reported at specific stops.
 * {@code stale} is true when this is an old copy returned because the live feed failed.
 */
public final class RealtimeSnapshot {

    /** One GTFS-RT stop_time_update: delay at a stop (matched by id or by sequence). */
    public record StopDelay(int stopSequence, String stopId, int delaySeconds) {
    }

    private final Instant fetchedAt;
    private final Map<String, List<StopDelay>> delaysByTrip;
    private final boolean stale;

    public RealtimeSnapshot(Instant fetchedAt, Map<String, List<StopDelay>> delaysByTrip, boolean stale) {
        this.fetchedAt = fetchedAt;
        this.delaysByTrip = Map.copyOf(delaysByTrip);
        this.stale = stale;
    }

    public static RealtimeSnapshot empty(Instant at, boolean stale) {
        return new RealtimeSnapshot(at, Map.of(), stale);
    }

    public RealtimeSnapshot asStale() {
        return new RealtimeSnapshot(fetchedAt, delaysByTrip, true);
    }

    public Instant fetchedAt() {
        return fetchedAt;
    }

    public boolean stale() {
        return stale;
    }

    public boolean hasTrip(String tripId) {
        return delaysByTrip.containsKey(tripId);
    }

    public int tripCount() {
        return delaysByTrip.size();
    }

    /**
     * Delay of a trip at a stop: the update for that stop if there is one, otherwise the latest
     * update before it (GTFS-RT says delays carry forward along the trip).
     */
    public Optional<Integer> delayAt(String tripId, String stopId, int stopSequence) {
        List<StopDelay> updates = delaysByTrip.get(tripId);
        if (updates == null || updates.isEmpty()) {
            return Optional.empty();
        }
        StopDelay best = null;
        for (StopDelay u : updates) {
            if (stopId.equals(u.stopId()) || (u.stopSequence() > 0 && u.stopSequence() == stopSequence)) {
                return Optional.of(u.delaySeconds());
            }
            if (u.stopSequence() > 0 && u.stopSequence() < stopSequence
                    && (best == null || u.stopSequence() > best.stopSequence())) {
                best = u;
            }
        }
        if (best != null) {
            return Optional.of(best.delaySeconds());
        }
        // updates without sequence numbers cannot be ordered: use the trip's first reported delay
        boolean sequenced = updates.stream().anyMatch(u -> u.stopSequence() > 0);
        return sequenced ? Optional.empty() : Optional.of(updates.get(0).delaySeconds());
    }

    /** Latest known delay per trip (the Stage 1 tripDelaySeconds view). */
    public Map<String, Integer> tripDelaySeconds() {
        Map<String, Integer> out = new HashMap<>();
        delaysByTrip.forEach((trip, list) -> {
            if (!list.isEmpty()) {
                out.put(trip, list.get(list.size() - 1).delaySeconds());
            }
        });
        return out;
    }
}
