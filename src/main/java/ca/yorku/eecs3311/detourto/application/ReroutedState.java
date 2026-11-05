package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/** Following a new itinerary after a reroute; checked against the new plan. */
public final class ReroutedState implements TripState {

    @Override
    public void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now) {
        TripRisk risk = trip.assess(snap, ds, now);
        if (trip.isFinished(now)) {
            trip.setState(new CompletedState(), "arrived on the new route");
        } else if (risk.atRisk()) {
            trip.setState(new AtRiskState(), risk.reason());
        }
    }

    @Override
    public String name() {
        return "REROUTED";
    }
}
