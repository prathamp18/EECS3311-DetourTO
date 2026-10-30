package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RealtimeFeedDecoratorTest {

    /** Clock the test can move forward. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-02T13:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** Feed that counts calls and can be switched to failing. */
    static final class FakeFeed implements RealtimeFeed {
        int calls;
        boolean failing;

        @Override
        public List<ServiceAlert> alerts() {
            calls++;
            if (failing) {
                throw new FeedException("network down");
            }
            return List.of(new ServiceAlert("a" + calls, "h", "d", List.of(), List.of(), null, null, "", ""));
        }

        @Override
        public RealtimeSnapshot snapshot() {
            calls++;
            if (failing) {
                throw new FeedException("network down");
            }
            return new RealtimeSnapshot(Instant.EPOCH, Map.of("T" + calls, List.of()), false);
        }
    }

    @Test
    void cachingReusesResultsUntilTheTtlExpires() {
        TestClock clock = new TestClock();
        FakeFeed inner = new FakeFeed();
        CachingRealtimeFeed feed = new CachingRealtimeFeed(inner, Duration.ofSeconds(30), clock);
        List<ServiceAlert> first = feed.alerts();
        clock.advance(Duration.ofSeconds(20));
        assertSame(first, feed.alerts());
        assertEquals(1, inner.calls);
        clock.advance(Duration.ofSeconds(11));
        assertEquals("a2", feed.alerts().get(0).id());
        feed.snapshot();
        feed.snapshot();
        assertEquals(3, inner.calls, "alerts and snapshot are cached separately");
    }

    @Test
    void resilientFeedReturnsLastGoodDataMarkedStale() {
        TestClock clock = new TestClock();
        FakeFeed inner = new FakeFeed();
        ResilientRealtimeFeed feed = new ResilientRealtimeFeed(inner, new CircuitBreaker(3, Duration.ofSeconds(60), clock), clock);
        RealtimeSnapshot good = feed.snapshot();
        assertFalse(good.stale());

        inner.failing = true;
        RealtimeSnapshot fallback = feed.snapshot();
        assertTrue(fallback.stale());
        assertTrue(fallback.hasTrip("T1"), "last good data is kept");
        assertEquals("network down", feed.lastError());
        assertTrue(feed.alerts().isEmpty(), "alerts never loaded, so an empty list instead of an exception");
    }

    @Test
    void breakerOpensAfterThreeFailuresAndRetriesAfterCooldown() {
        TestClock clock = new TestClock();
        FakeFeed inner = new FakeFeed();
        inner.failing = true;
        ResilientRealtimeFeed feed = new ResilientRealtimeFeed(inner, new CircuitBreaker(3, Duration.ofSeconds(60), clock), clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(feed.snapshot().stale());
        }
        assertEquals(CircuitBreaker.Status.OPEN, feed.breakerStatus());
        feed.snapshot();
        feed.alerts();
        assertEquals(3, inner.calls, "no calls while the breaker is open");

        clock.advance(Duration.ofSeconds(61));
        inner.failing = false;
        assertFalse(feed.snapshot().stale(), "half-open trial call succeeds");
        assertEquals(CircuitBreaker.Status.CLOSED, feed.breakerStatus());
    }

    @Test
    void failedTrialCallReopensTheBreaker() {
        TestClock clock = new TestClock();
        CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(60), clock);
        b.recordFailure();
        b.recordFailure();
        b.recordFailure();
        assertFalse(b.allowRequest());
        clock.advance(Duration.ofSeconds(60));
        assertTrue(b.allowRequest());
        b.recordFailure();
        assertEquals(CircuitBreaker.Status.OPEN, b.status());
    }

    @Test
    void emptyStaleSnapshotWhenTheFeedNeverWorked() {
        TestClock clock = new TestClock();
        FakeFeed inner = new FakeFeed();
        inner.failing = true;
        ResilientRealtimeFeed feed = new ResilientRealtimeFeed(inner, new CircuitBreaker(), clock);
        RealtimeSnapshot s = feed.snapshot();
        assertTrue(s.stale());
        assertEquals(0, s.tripCount());
        assertTrue(feed.alerts().isEmpty());
    }
}
