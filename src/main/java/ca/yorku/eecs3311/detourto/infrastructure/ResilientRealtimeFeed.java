package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Decorator: never throws. When the inner feed fails (or the circuit breaker is open), it returns
 * the last good result marked stale, or an empty stale result, so planning falls back to the
 * schedule instead of crashing.
 */
public final class ResilientRealtimeFeed extends RealtimeFeedDecorator {

    private final CircuitBreaker breaker;
    private final Clock clock;
    private List<ServiceAlert> lastAlerts = List.of();
    private RealtimeSnapshot lastSnapshot;
    private String lastError;

    public ResilientRealtimeFeed(RealtimeFeed inner) {
        this(inner, new CircuitBreaker(), Clock.systemUTC());
    }

    public ResilientRealtimeFeed(RealtimeFeed inner, CircuitBreaker breaker, Clock clock) {
        super(inner);
        this.breaker = breaker;
        this.clock = clock;
    }

    @Override
    public synchronized List<ServiceAlert> alerts() {
        List<ServiceAlert> fresh = attempt(inner::alerts);
        if (fresh != null) {
            lastAlerts = fresh;
        }
        return lastAlerts;
    }

    @Override
    public synchronized RealtimeSnapshot snapshot() {
        RealtimeSnapshot fresh = attempt(inner::snapshot);
        if (fresh != null) {
            lastSnapshot = fresh;
            return fresh;
        }
        return lastSnapshot != null ? lastSnapshot.asStale() : RealtimeSnapshot.empty(clock.instant(), true);
    }

    private <T> T attempt(Supplier<T> call) {
        if (!breaker.allowRequest()) {
            return null;
        }
        try {
            T value = call.get();
            breaker.recordSuccess();
            lastError = null;
            return value;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            lastError = e.getMessage();
            return null;
        }
    }

    /** Why the last call failed, or null when it worked. */
    public synchronized String lastError() {
        return lastError;
    }

    public CircuitBreaker.Status breakerStatus() {
        return breaker.status();
    }
}
