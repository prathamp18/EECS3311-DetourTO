package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReroutingTest {

    static TransitNetwork net;
    static final LocalDateTime MON_8AM = LocalDateTime.of(2026, 11, 2, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    static TripController controller() {
        return new TripController(new JourneyPlanner(net), new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
    }

    static boolean ridesThrough(Itinerary it, String routeId, String stationId) {
        for (RideLeg r : it.rideLegs()) {
            if (!r.route().id().equals(routeId)) {
                continue;
            }
            for (int pos = r.boardPos(); pos <= r.alightPos(); pos++) {
                var s = net.stopAt(r.trip().stopIndexAt(pos));
                if (stationId.equals(s.parentStation()) || stationId.equals(s.id())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void closedSectionOfALineCannotBeRiddenThrough() {
        Disruption closure = new Disruption("D1", "A1", DisruptionKind.CLOSURE, Set.of("1"),
                Set.of("STN_QP", "STN_SPT"), null, null, 1.0, false, "closed");
        TripQuery q = TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM);
        List<Itinerary> options = new JourneyPlanner(net).plan(q, net.withDisruptions(List.of(closure)));
        assertFalse(options.isEmpty());
        for (Itinerary it : options) {
            assertFalse(ridesThrough(it, "1", "STN_QP"), it.toString());
            assertFalse(ridesThrough(it, "1", "STN_SPT"), it.toString());
        }
    }

    @Test
    void stationClosureWithoutARouteLetsTrainsPassThrough() {
        Disruption stationOnly = new Disruption("D2", "A2", DisruptionKind.CLOSURE, Set.of(),
                Set.of("STN_QP"), null, null, 1.0, false, "station closed");
        TripQuery q = TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM);
        Itinerary best = new JourneyPlanner(net).plan(q, net.withDisruptions(List.of(stationOnly))).get(0);
        assertEquals(0, best.transfers(), "trains still run through Queen's Park without stopping");
    }

    @Test
    void sampleAlertsAreAppliedByDefaultAndCanBeIgnored() {
        TripController c = controller();
        TripQuery q = TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM);

        PlanResult avoided = c.planFromForm(q.copy());
        assertEquals(Set.of("D-A-L1-CLOSURE", "D-A-BAY-ELEVATOR"),
                Set.copyOf(avoided.disruptionsApplied().stream().map(Disruption::id).toList()));
        assertTrue(avoided.options().stream().noneMatch(it -> ridesThrough(it, "1", "STN_QP")));
        assertTrue(avoided.message().contains("Avoiding 2 disruptions"), avoided.message());

        PlanResult schedule = c.planWithDisruptions(q.copy(), Set.of(), true);
        assertTrue(schedule.disruptionsApplied().isEmpty());
        assertEquals(0, schedule.options().get(0).transfers(), "on the schedule Line 1 is direct");
        assertTrue(schedule.options().get(0).arrival().isBefore(avoided.options().get(0).arrival()));
    }

    @Test
    void elevatorOutageBlocksAccessibleRoutesThroughThatStation() {
        TripController c = controller();
        TripQuery q = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_BAY"), MON_8AM);
        q.setStrategyKind(StrategyKind.ACCESSIBLE);
        assertFalse(c.planWithDisruptions(q.copy(), Set.of(), true).options().isEmpty());
        PlanResult r = c.planFromForm(q.copy());
        assertTrue(r.options().isEmpty(), "Bay has no step-free access during the outage");
    }

    @Test
    void rerouteNeedsACurrentTrip() {
        TripController c = controller();
        assertTrue(c.rerouteAround("D-A-L1-CLOSURE").query() == null);
        c.planWithDisruptions(TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM),
                Set.of(), true);
        PlanResult r = c.rerouteAround("A-L1-CLOSURE");
        assertTrue(r.disruptionsApplied().stream().anyMatch(d -> d.id().equals("D-A-L1-CLOSURE")));
        for (Itinerary it : r.options()) {
            for (Leg leg : it.legs()) {
                assertFalse(leg.from().id().startsWith("QP_") || leg.to().id().startsWith("QP_"));
            }
        }
    }
}
