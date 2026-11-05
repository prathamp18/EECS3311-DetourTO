package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/** Travelling as planned: watch for delays and disruptions on the rest of the trip. */
public final class OnTrackState implements TripState {

    @Override
    public void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now) {
        TripRisk risk = trip.assess(snap, ds, now);
        if (trip.isFinished(now)) {
            trip.setState(new CompletedState(), "arrived");
        } else if (risk.atRisk()) {
            trip.setState(new AtRiskState(), risk.reason());
        }
    }

    @Override
    public String name() {
        return "ON_TRACK";
    }
}
