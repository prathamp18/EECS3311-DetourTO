package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Decorator: reuses results for {@code ttl} so the GUI, agent and monitor don't hammer the TTC. */
public final class CachingRealtimeFeed extends RealtimeFeedDecorator {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Duration ttl;
    private final Clock clock;
    private List<ServiceAlert> cachedAlerts;
    private Instant alertsAt;
    private RealtimeSnapshot cachedSnapshot;
    private Instant snapshotAt;

    public CachingRealtimeFeed(RealtimeFeed inner) {
        this(inner, DEFAULT_TTL, Clock.systemUTC());
    }

    public CachingRealtimeFeed(RealtimeFeed inner, Duration ttl, Clock clock) {
        super(inner);
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public synchronized List<ServiceAlert> alerts() {
        Instant now = clock.instant();
        if (cachedAlerts == null || now.isAfter(alertsAt.plus(ttl))) {
            cachedAlerts = inner.alerts();
            alertsAt = now;
        }
        return cachedAlerts;
    }

    @Override
    public synchronized RealtimeSnapshot snapshot() {
        Instant now = clock.instant();
        if (cachedSnapshot == null || now.isAfter(snapshotAt.plus(ttl))) {
            cachedSnapshot = inner.snapshot();
            snapshotAt = now;
        }
        return cachedSnapshot;
    }
}
