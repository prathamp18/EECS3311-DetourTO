package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A disruption or delay threatens the trip. The rider is told once (a new risk notifies again,
 * the same one does not). Returns to on-track if the risk clears; a reroute moves to Rerouted.
 */
public final class AtRiskState implements TripState {

    @Override
    public void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now) {
        TripRisk risk = trip.assess(snap, ds, now);
        if (trip.isFinished(now)) {
            trip.setState(new CompletedState(), "arrived");
        } else if (!risk.atRisk()) {
            trip.setState(now.isBefore(trip.itinerary().departure()) ? new PlannedState() : new OnTrackState(),
                    "risk cleared");
        } else {
            trip.notifyRiskIfNew(risk);
        }
    }

    @Override
    public String name() {
        return "AT_RISK";
    }
}
