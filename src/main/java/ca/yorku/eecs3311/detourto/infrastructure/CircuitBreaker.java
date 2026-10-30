package ca.yorku.eecs3311.detourto.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops calling a failing service for a while. After {@code threshold} failures in a row the
 * breaker opens; after {@code cooldown} one trial call is allowed (half-open); success closes it.
 */
public final class CircuitBreaker {

    public enum Status { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final Duration cooldown;
    private final Clock clock;
    private Status status = Status.CLOSED;
    private int failures;
    private Instant openedAt;

    public CircuitBreaker() {
        this(3, Duration.ofSeconds(60), Clock.systemUTC());
    }

    public CircuitBreaker(int threshold, Duration cooldown, Clock clock) {
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (status == Status.OPEN && !clock.instant().isBefore(openedAt.plus(cooldown))) {
            status = Status.HALF_OPEN;
        }
        return status != Status.OPEN;
    }

    public synchronized void recordSuccess() {
        failures = 0;
        status = Status.CLOSED;
    }

    public synchronized void recordFailure() {
        failures++;
        if (status == Status.HALF_OPEN || failures >= threshold) {
            status = Status.OPEN;
            openedAt = clock.instant();
        }
    }

    public synchronized Status status() {
        allowRequest();
        return status;
    }
}
