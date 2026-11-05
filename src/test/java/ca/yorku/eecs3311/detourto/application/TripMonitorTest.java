package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TripMonitorTest {

    static TransitNetwork net;
    static Itinerary direct;
    static final LocalDateTime MON_8AM = LocalDateTime.of(2026, 11, 2, 8, 0);
    static final RealtimeSnapshot NO_DELAYS = RealtimeSnapshot.empty(Instant.EPOCH, false);
    static final Disruption QP_CLOSED = new Disruption("D1", "A1", DisruptionKind.CLOSURE, Set.of("1"),
            Set.of("STN_QP"), null, null, 1.0, false, "Line 1 closed at Queen's Park");

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        direct = new JourneyPlanner(net).plan(TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM)).get(0);
    }

    static ActiveTrip trip(Itinerary it, LocalDateTime deadline, EventBus bus) {
        return new ActiveTrip("t1", it, deadline, StrategyKind.FASTEST, net, bus);
    }

    @Test
    void plannedThenOnTrackThenCompleted() {
        ActiveTrip t = trip(direct, null, new EventBus());
        t.update(NO_DELAYS, List.of(), direct.departure().minusMinutes(5));
        assertInstanceOf(PlannedState.class, t.getState());
        t.update(NO_DELAYS, List.of(), direct.departure().plusMinutes(1));
        assertInstanceOf(OnTrackState.class, t.getState());
        t.update(NO_DELAYS, List.of(), direct.arrival());
        assertInstanceOf(CompletedState.class, t.getState());
        t.update(NO_DELAYS, List.of(QP_CLOSED), direct.arrival().plusMinutes(1));
        assertInstanceOf(CompletedState.class, t.getState(), "completed trips ignore updates");
        assertEquals(List.of("PLANNED->ON_TRACK", "ON_TRACK->COMPLETED"),
                t.changes().stream().map(c -> c.from() + "->" + c.to()).toList());
    }

    @Test
    void closureOnTheRouteMakesTheTripAtRiskAndNotifiesOnce() {
        EventBus bus = new EventBus();
        List<TransitEvent> risks = new ArrayList<>();
        bus.subscribe(EventType.TRIP_AT_RISK, risks::add);
        ActiveTrip t = trip(direct, null, bus);
        t.update(NO_DELAYS, List.of(QP_CLOSED), direct.departure().plusMinutes(1));
        assertInstanceOf(AtRiskState.class, t.getState());
        assertTrue(t.lastRisk().reason().contains("not running through Queen's Park"), t.lastRisk().reason());
        t.update(NO_DELAYS, List.of(QP_CLOSED), direct.departure().plusMinutes(3));
        t.update(NO_DELAYS, List.of(QP_CLOSED), direct.departure().plusMinutes(5));
        assertEquals(1, risks.size(), "the same disruption is reported once");

        t.update(NO_DELAYS, List.of(), direct.departure().plusMinutes(6));
        assertInstanceOf(OnTrackState.class, t.getState(), "risk cleared when the alert ends");
    }

    @Test
    void lowConfidenceAndOffRouteDisruptionsAreIgnored() {
        Disruption unsure = new Disruption("D2", "A2", DisruptionKind.CLOSURE, Set.of("1"), Set.of("STN_QP"),
                null, null, 0.3, true, "maybe");
        Disruption elsewhere = new Disruption("D3", "A3", DisruptionKind.CLOSURE, Set.of("2"), Set.of("STN_BY"),
                null, null, 1.0, false, "Line 2");
        ActiveTrip t = trip(direct, null, new EventBus());
        t.update(NO_DELAYS, List.of(unsure, elsewhere), direct.departure().plusMinutes(1));
        assertInstanceOf(OnTrackState.class, t.getState());
    }

    @Test
    void arriveByDeadlineAndDelays() {
        String tripId = direct.rideLegs().get(0).trip().id();
        RealtimeSnapshot late = new RealtimeSnapshot(Instant.EPOCH,
                Map.of(tripId, List.of(new RealtimeSnapshot.StopDelay(0, "", 360))), false);
        ActiveTrip t = trip(direct, direct.arrival().plusMinutes(3), new EventBus());
        t.update(late, List.of(), direct.departure().plusMinutes(1));
        assertInstanceOf(AtRiskState.class, t.getState());
        assertEquals(direct.arrival().plusMinutes(6), t.projectedArrival());
        assertTrue(t.lastRisk().reason().contains("after your arrive-by time"));

        ActiveTrip noDeadline = trip(direct, null, new EventBus());
        noDeadline.update(late, List.of(), direct.departure().plusMinutes(1));
        assertInstanceOf(OnTrackState.class, noDeadline.getState(), "6 min late without a deadline is fine");
    }

    @Test
    void serviceOffersAndAppliesARerouteAroundTheClosure() {
        RealtimeFeed feed = new RealtimeFeed() {
            @Override
            public List<ServiceAlert> alerts() {
                return new GtfsRealtimeAdapter("data/sample/realtime").alerts();
            }

            @Override
            public RealtimeSnapshot snapshot() {
                return NO_DELAYS;
            }
        };
        TripController c = new TripController(new JourneyPlanner(net), new PlaceResolver(net), null, feed);
        c.planWithDisruptions(TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM), Set.of(), true);
        ActiveTrip t = c.startTrip("opt-1").orElseThrow();
        c.monitor().poll(MON_8AM.minusMinutes(5));
        assertInstanceOf(AtRiskState.class, t.getState());
        assertFalse(t.rerouteOptions().isEmpty());
        Itinerary best = t.rerouteOptions().get(0);
        assertTrue(best.routeIds().contains("510") || best.routeIds().contains("2"), best.toString());

        assertTrue(c.acceptReroute(t.id(), best.id()).isPresent());
        assertInstanceOf(ReroutedState.class, t.getState());
        c.monitor().poll(MON_8AM.plusMinutes(5));
        assertInstanceOf(ReroutedState.class, t.getState(), "the new route avoids the closure");
        c.monitor().poll(best.arrival().plusMinutes(1));
        assertInstanceOf(CompletedState.class, t.getState());
        assertTrue(c.startTrip("opt-9").isEmpty());
    }
}
