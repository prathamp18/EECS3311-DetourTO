package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/**
 * State pattern: how an {@link ActiveTrip} reacts to new real-time data depends on where it is.
 * Each state decides the next state itself; {@link ActiveTrip} just delegates.
 */
public interface TripState {

    void handle(ActiveTrip trip, RealtimeSnapshot snap, List<Disruption> ds, LocalDateTime now);

    String name();
}
