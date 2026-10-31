package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.time.Instant;
import java.util.List;

/** A feed that returns a scenario's recorded alerts (and no live delays). */
public final class ReplayRealtimeFeed implements RealtimeFeed {

    private final List<ServiceAlert> alerts;

    public ReplayRealtimeFeed(List<ServiceAlert> alerts) {
        this.alerts = List.copyOf(alerts);
    }

    @Override
    public List<ServiceAlert> alerts() {
        return alerts;
    }

    @Override
    public RealtimeSnapshot snapshot() {
        return RealtimeSnapshot.empty(Instant.now(), false);
    }
}
