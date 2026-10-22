package ca.yorku.eecs3311.detourto.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TripQueryTest {

    static final Stop A = new Stop("A", "", "A", 43.6, -79.4, true, null, false);
    static final Stop B = new Stop("B", "", "B", 43.7, -79.4, true, null, false);
    static final LocalDateTime T = LocalDateTime.of(2026, 10, 22, 9, 0);

    @Test
    void departAndArriveAreExclusive() {
        TripQuery q = TripQuery.departAt(A, B, T);
        assertFalse(q.isArriveBy());
        q.setArriveBy(T.plusHours(1));
        assertTrue(q.isArriveBy());
        assertNull(q.departAt());
        assertEquals(T.plusHours(1), q.time());
    }

    @Test
    void copyIsIndependent() {
        TripQuery q = TripQuery.departAt(A, B, T);
        TripQuery c = q.copy();
        q.avoidRoute("1");
        q.setStrategyKind(StrategyKind.ACCESSIBLE);
        assertTrue(c.avoidRouteIds().isEmpty());
        assertEquals(StrategyKind.FASTEST, c.strategyKind());
    }

    @Test
    void strategyNamesParseFromCliSpelling() {
        assertEquals(StrategyKind.FEWEST_TRANSFERS, StrategyKind.parse("fewest-transfers"));
        assertEquals("least-walking", StrategyKind.LEAST_WALKING.cliName());
        assertThrows(IllegalArgumentException.class, () -> StrategyKind.parse("cheapest"));
        assertThrows(IllegalArgumentException.class, () -> TripQuery.departAt(A, B, T).setMaxWalkMeters(-1));
    }
}
