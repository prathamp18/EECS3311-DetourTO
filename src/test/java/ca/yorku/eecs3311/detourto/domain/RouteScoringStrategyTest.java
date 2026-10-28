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

class RouteScoringStrategyTest {

    static TransitNetwork net;
    static JourneyPlanner planner;
    static final LocalDateTime THU_8AM = LocalDateTime.of(2026, 10, 22, 8, 0);

    // hand-built legs for scoring tests
    static final Stop A = new Stop("A", "", "A", 43.60, -79.40, true, null, false);
    static final Stop B = new Stop("B", "", "B", 43.61, -79.40, true, null, false);
    static final Stop C = new Stop("C", "", "C", 43.62, -79.40, true, null, false);
    static final Route R = new Route("R", "R", "Test", TransitMode.BUS);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        planner = new JourneyPlanner(net);
    }

    static Stop stop(String id) {
        return net.stopById(id);
    }

    static RideLeg ride(Stop from, Stop to, int startMin, int endMin) {
        Trip t = new Trip("T" + startMin, R, "S", "", new int[] {0, 1}, new int[] {0, 60}, new int[] {0, 60});
        return new RideLeg(t, 0, 1, from, to, THU_8AM.plusMinutes(startMin), THU_8AM.plusMinutes(endMin));
    }

    static WalkLeg walk(Stop from, Stop to, int startMin, int endMin, int meters) {
        return new WalkLeg(from, to, THU_8AM.plusMinutes(startMin), THU_8AM.plusMinutes(endMin), meters);
    }

    @Test
    void fastestCountsWaitingFromTheRequestedTime() {
        TripQuery q = TripQuery.departAt(A, C, THU_8AM);
        Itinerary leavesLate = new Itinerary("x", List.of(ride(A, C, 20, 30)));
        assertEquals(30.0, new FastestStrategy().score(leavesLate, q), 1e-9);
    }

    @Test
    void fewestTransfersPrefersADirectRideEvenIfSlower() {
        TripQuery q = TripQuery.departAt(A, C, THU_8AM);
        Itinerary withTransfer = new Itinerary("t", List.of(ride(A, B, 0, 10), walk(B, B, 10, 11, 0), ride(B, C, 12, 20)));
        Itinerary direct = new Itinerary("d", List.of(ride(A, C, 0, 26)));
        RouteScoringStrategy fewest = new FewestTransfersStrategy();
        RouteScoringStrategy fastest = new FastestStrategy();
        assertTrue(fewest.score(direct, q) < fewest.score(withTransfer, q));
        assertTrue(fastest.score(withTransfer, q) < fastest.score(direct, q));
    }

    @Test
    void leastWalkingWeighsWalkingMinutes() {
        TripQuery q = TripQuery.departAt(A, C, THU_8AM);
        Itinerary walky = new Itinerary("w", List.of(walk(A, B, 0, 10, 700), ride(B, C, 10, 20)));
        Itinerary ridey = new Itinerary("r", List.of(ride(A, C, 5, 25)));
        RouteScoringStrategy least = new LeastWalkingStrategy();
        assertEquals(20 + 2 * 10, least.score(walky, q), 1e-9);
        assertTrue(least.score(ridey, q) < least.score(walky, q));
    }

    @Test
    void arriveByScoresByHowEarlyYouLeave() {
        TripQuery q = TripQuery.arriveBy(A, C, THU_8AM.plusMinutes(60));
        Itinerary early = new Itinerary("e", List.of(ride(A, C, 0, 20)));
        Itinerary late = new Itinerary("l", List.of(ride(A, C, 30, 50)));
        assertTrue(new FastestStrategy().score(late, q) < new FastestStrategy().score(early, q));
    }

    @Test
    void factoryReturnsMatchingKinds() {
        for (StrategyKind k : StrategyKind.values()) {
            assertEquals(k, RouteScoringStrategy.of(k).kind());
        }
    }

    @Test
    void accessibleAvoidsStationsWithoutStepFreeAccess() {
        // St Patrick (SPT) has no step-free access in the sample data
        TripQuery q = TripQuery.departAt(stop("STN_YU"), stop("STN_SPT"), THU_8AM);
        assertFalse(planner.plan(q).isEmpty(), "fastest can use St Patrick");
        q.setStrategyKind(StrategyKind.ACCESSIBLE);
        assertTrue(planner.plan(q).isEmpty(), "accessible must not alight at St Patrick");
    }

    @Test
    void accessibleRoutesAroundAnElevatorOutage() {
        TripQuery q = TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM);
        q.setStrategyKind(StrategyKind.ACCESSIBLE);
        NetworkView spadinaOutage = net.withClosures(Set.of(), Set.of(), Set.of("STN_SP"));
        List<Itinerary> options = planner.plan(q, spadinaOutage);
        assertFalse(options.isEmpty());
        for (Itinerary it : options) {
            for (RideLeg leg : it.rideLegs()) {
                assertTrue(spadinaOutage.isStepFree(leg.from()) && spadinaOutage.isStepFree(leg.to()), it.toString());
            }
            assertTrue(it.legs().stream().noneMatch(l -> l.from().id().startsWith("SP_")
                    && l instanceof RideLeg), "no boarding at Spadina: " + it);
        }
    }

    @Test
    void avoidedModeIsNotUsed() {
        TripQuery q = TripQuery.departAt(stop("STN_KE"), stop("STN_UN"), THU_8AM);
        NetworkView line1Closed = net.withClosures(Set.of(), Set.of("1"), Set.of());
        assertTrue(planner.plan(q, line1Closed).get(0).routeIds().contains("510"));
        q.avoidMode(TransitMode.STREETCAR);
        for (Itinerary it : planner.plan(q, line1Closed)) {
            assertTrue(it.rideLegs().stream().noneMatch(l -> l.mode() == TransitMode.STREETCAR), it.toString());
        }
    }

    @Test
    void rankingFollowsTheChosenStrategy() {
        TripQuery q = TripQuery.departAt(stop("DFLA_29"), stop("QQSP_510"), THU_8AM);
        for (StrategyKind k : StrategyKind.values()) {
            q.setStrategyKind(k);
            RouteScoringStrategy s = RouteScoringStrategy.of(k);
            List<Itinerary> options = planner.plan(q);
            for (int i = 1; i < options.size(); i++) {
                assertTrue(s.score(options.get(i - 1), q) <= s.score(options.get(i), q), k + " order");
            }
        }
    }
}
