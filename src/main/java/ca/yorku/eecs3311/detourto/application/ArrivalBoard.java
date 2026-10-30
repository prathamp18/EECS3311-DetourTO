package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Arrival;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Feature F08: next vehicles at a stop. Starts from the schedule and applies live GTFS-RT delays
 * for buses and streetcars. The subway has no TTC real-time feed, so its times stay "scheduled".
 */
public final class ArrivalBoard {

    /** Departures scheduled this long ago may still be coming if the vehicle is late. */
    static final int LOOKBACK_MINUTES = 20;
    /** Enough scheduled departures to cover the look-back window at a busy station. */
    static final int CANDIDATES = 300;

    /** The board for one stop, with a note when live data was unavailable. */
    public record Board(Stop stop, List<Arrival> arrivals, boolean liveDataAvailable, String note) {
    }

    private final JourneyPlanner planner;
    private final RealtimeFeed feed;

    public ArrivalBoard(JourneyPlanner planner, RealtimeFeed feed) {
        this.planner = planner;
        this.feed = feed;
    }

    public Board arrivals(Stop stop, LocalDateTime now, int n) {
        List<Arrival> scheduled = planner.scheduledDepartures(stop, now.minusMinutes(LOOKBACK_MINUTES), CANDIDATES + n);
        RealtimeSnapshot snap = feed.snapshot();
        List<Arrival> out = new ArrayList<>();
        for (Arrival a : scheduled) {
            Arrival withLive = a;
            if (a.route().mode() != TransitMode.SUBWAY) {
                withLive = snap.delayAt(a.tripId(), a.stop().id(), a.stopSequence()).map(a::withDelay).orElse(a);
            }
            if (!withLive.predicted().isBefore(now)) {
                out.add(withLive);
            }
        }
        out.sort(Comparator.comparing(Arrival::predicted));
        List<Arrival> top = out.size() > n ? out.subList(0, n) : out;
        String note;
        if (snap.stale()) {
            note = "Real-time data unavailable: showing scheduled times.";
        } else if (top.stream().anyMatch(Arrival::realtime)) {
            note = "Live predictions for buses and streetcars; subway times are scheduled.";
        } else {
            note = "Scheduled times (no live prediction for these trips right now).";
        }
        return new Board(stop, List.copyOf(top), !snap.stale(), note);
    }
}
