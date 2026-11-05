package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/** Before the rider leaves: problems are reported, otherwise wait for the departure time. */
public final class PlannedState implements TripState {

    @Override
    public void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now) {
        TripRisk risk = trip.assess(snap, ds, now);
        if (risk.atRisk()) {
            trip.setState(new AtRiskState(), risk.reason());
        } else if (!now.isBefore(trip.itinerary().departure())) {
            trip.setState(new OnTrackState(), "trip started");
        }
    }

    @Override
    public String name() {
        return "PLANNED";
    }
}
