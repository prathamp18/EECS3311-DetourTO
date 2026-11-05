package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/** The trip is over: updates are ignored. */
public final class CompletedState implements TripState {

    @Override
    public void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now) {
        // nothing to do
    }

    @Override
    public String name() {
        return "COMPLETED";
    }
}
