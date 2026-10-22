package ca.yorku.eecs3311.detourto.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JourneyPlannerTest {

    static TransitNetwork net;
    static JourneyPlanner planner;
    /** Thursday, so the WEEKDAY service runs. */
    static final LocalDateTime THU_8AM = LocalDateTime.of(2026, 10, 22, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        planner = new JourneyPlanner(net);
    }

    static Stop stop(String id) {
        return net.stopById(id);
    }

    @Test
    void directSubwayRide() {
        List<Itinerary> options = planner.plan(TripQuery.departAt(stop("STN_YU"), stop("STN_UN"), THU_8AM));
        assertFalse(options.isEmpty());
        Itinerary best = options.get(0);
        assertEquals("opt-1", best.id());
        assertEquals(0, best.transfers());
        assertEquals(Set.of("1"), best.routeIds());
        assertFalse(best.departure().isBefore(THU_8AM));
        // 12 stops x (120 s run + 20 s dwell) = 28 min of riding
        assertEquals(28, best.rideLegs().get(0).minutes());
        assertTrue(best.departure().isBefore(THU_8AM.plusMinutes(6)), "headway is 5 min");
    }

    @Test
    void optionsAreSortedAndHaveUniqueIds() {
        List<Itinerary> options = planner.plan(TripQuery.departAt(stop("STN_YU"), stop("STN_UN"), THU_8AM));
        assertEquals(JourneyPlanner.MAX_OPTIONS, options.size());
        for (int i = 1; i < options.size(); i++) {
            assertFalse(options.get(i).arrival().isBefore(options.get(i - 1).arrival()));
            assertEquals("opt-" + (i + 1), options.get(i).id());
        }
    }

    @Test
    void lineChangeUsesTheWalkBetweenPlatforms() {
        // Keele (Line 2) to Union (Line 1), changing at Spadina
        Itinerary best = planner.plan(TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM)).get(0);
        assertEquals(1, best.transfers());
        assertEquals(List.of("2", "1"), List.copyOf(best.routeIds()));
        List<Leg> legs = best.legs();
        for (int i = 1; i < legs.size(); i++) {
            assertFalse(legs.get(i).start().isBefore(legs.get(i - 1).end()), "legs must not overlap");
        }
        WalkLeg transfer = (WalkLeg) legs.stream().filter(WalkLeg.class::isInstance).findFirst().orElseThrow();
        assertTrue(transfer.seconds() >= TransitNetwork.MIN_TRANSFER_SECONDS);
    }

    @Test
    void closedLineIsAvoided() {
        NetworkView line1Closed = net.withClosures(Set.of(), Set.of("1"), Set.of());
        List<Itinerary> options = planner.plan(
                TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM), line1Closed);
        assertFalse(options.isEmpty());
        for (Itinerary it : options) {
            assertFalse(it.routeIds().contains("1"), it.toString());
        }
        assertTrue(options.get(0).routeIds().contains("510"), "Line 2 then 510 Spadina");
    }

    @Test
    void closedStationCannotBeUsedToTransfer() {
        NetworkView sgClosed = net.withClosures(Set.of("STN_SG"), Set.of(), Set.of());
        List<Itinerary> options = planner.plan(
                TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM), sgClosed);
        assertFalse(options.isEmpty());
        for (Itinerary it : options) {
            for (Leg leg : it.legs()) {
                assertFalse(leg.from().id().startsWith("SG_") || leg.to().id().startsWith("SG_"), it.toString());
            }
        }
    }

    @Test
    void avoidedRouteIsNotUsed() {
        TripQuery q = TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM);
        q.avoidRoute("1");
        assertTrue(planner.plan(q).stream().noneMatch(it -> it.routeIds().contains("1")));
    }

    @Test
    void arriveByReturnsLatestOptionsThatMakeIt() {
        LocalDateTime deadline = THU_8AM.plusHours(1);
        List<Itinerary> options = planner.plan(TripQuery.arriveBy(stop("STN_YU"), stop("STN_UN"), deadline));
        assertFalse(options.isEmpty());
        for (Itinerary it : options) {
            assertFalse(it.arrival().isAfter(deadline), it.toString());
        }
        Itinerary first = options.get(0);
        for (Itinerary it : options) {
            assertFalse(it.departure().isAfter(first.departure()), "latest departure is listed first");
        }
        assertTrue(first.arrival().isAfter(deadline.minusMinutes(6)), "should not leave earlier than needed");
    }

    @Test
    void earlyMorningWaitsForFirstTrain() {
        List<Itinerary> options = planner.plan(TripQuery.departAt(stop("STN_YU"), stop("STN_SG"),
                LocalDateTime.of(2026, 10, 22, 2, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 22, 6, 0, 20), options.get(0).departure());
    }

    @Test
    void christmasUsesTheWeekendSchedule() {
        // weekend headway on Line 1 is 6 minutes, weekday is 5
        List<Itinerary> options = planner.plan(TripQuery.departAt(stop("STN_YU"), stop("STN_UN"),
                LocalDateTime.of(2026, 12, 25, 6, 0)));
        long gap = java.time.Duration.between(options.get(0).departure(), options.get(1).departure()).toMinutes();
        assertEquals(6, gap);
    }

    @Test
    void noRouteWhenDestinationIsClosed() {
        NetworkView unionClosed = net.withClosures(Set.of("STN_UN"), Set.of(), Set.of());
        assertTrue(planner.plan(TripQuery.departAt(stop("STN_YU"), stop("STN_UN"), THU_8AM), unionClosed).isEmpty());
    }

    @Test
    void dominatedOptionsAreDropped() {
        NetworkView line1Closed = net.withClosures(Set.of(), Set.of("1"), Set.of());
        List<Itinerary> options = planner.plan(
                TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM), line1Closed);
        for (Itinerary a : options) {
            for (Itinerary b : options) {
                boolean sameArrivalLeavesEarlier = a != b && a.arrival().equals(b.arrival())
                        && a.departure().isBefore(b.departure()) && a.transfers() >= b.transfers();
                assertFalse(sameArrivalLeavesEarlier, a + " is dominated by " + b);
            }
        }
    }

    @Test
    void walkLimitFiltersOptions() {
        TripQuery q = TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM);
        q.setMaxWalkMeters(0);
        for (Itinerary it : planner.plan(q)) {
            assertEquals(0, it.walkMeters());
        }
    }
}
