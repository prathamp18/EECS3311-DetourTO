package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;

/**
 * The next vehicle at a stop. {@code realtime} is false when the time is the published schedule
 * (always the case for the subway, which has no TTC real-time feed). {@code stopSequence} is the
 * 1-based position of the stop in the trip, used to match GTFS-RT updates.
 */
public record Arrival(Route route, Stop stop, String tripId, String headsign, int stopSequence,
                      LocalDateTime scheduled, LocalDateTime predicted, int delaySeconds, boolean realtime) {

    /** Same departure with a live delay applied. */
    public Arrival withDelay(int seconds) {
        return new Arrival(route, stop, tripId, headsign, stopSequence, scheduled,
                scheduled.plusSeconds(seconds), seconds, true);
    }
}
