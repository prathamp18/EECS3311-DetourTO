package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Arrival;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.FeedException;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import ca.yorku.eecs3311.detourto.infrastructure.ResilientRealtimeFeed;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArrivalBoardTest {

    static TransitNetwork net;
    static JourneyPlanner planner;
    static final LocalDateTime MON_8AM = LocalDateTime.of(2026, 11, 2, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        planner = new JourneyPlanner(net);
    }

    @Test
    void scheduledDeparturesAreSortedAndSkipLastStops() {
        List<Arrival> deps = planner.scheduledDepartures(net.stopById("STN_UN"), MON_8AM, 10);
        assertEquals(10, deps.size());
        for (int i = 1; i < deps.size(); i++) {
            assertFalse(deps.get(i).scheduled().isBefore(deps.get(i - 1).scheduled()));
        }
        assertTrue(deps.stream().allMatch(a -> !a.scheduled().isBefore(MON_8AM)));
        // the 510 ends at Union heading south: only northbound 510 trips depart from Union
        assertTrue(deps.stream().filter(a -> a.route().id().equals("510"))
                .allMatch(a -> a.headsign().contains("Spadina")), deps.toString());
    }

    @Test
    void liveDelaysApplyToStreetcarsButNotTheSubway() {
        ArrivalBoard board = new ArrivalBoard(planner, new GtfsRealtimeAdapter("data/sample/realtime"));
        ArrivalBoard.Board b = board.arrivals(net.stopById("STN_SP"), MON_8AM, 12);
        assertTrue(b.liveDataAvailable());
        assertTrue(b.arrivals().stream().anyMatch(a -> a.route().id().equals("510") && a.realtime()));
        for (Arrival a : b.arrivals()) {
            if (a.route().mode() == TransitMode.SUBWAY) {
                assertFalse(a.realtime(), "subway has no TTC real-time");
                assertEquals(a.scheduled(), a.predicted());
            } else if (a.realtime()) {
                assertEquals(a.scheduled().plusSeconds(a.delaySeconds()), a.predicted());
            }
            assertFalse(a.predicted().isBefore(MON_8AM));
        }
        for (int i = 1; i < b.arrivals().size(); i++) {
            assertFalse(b.arrivals().get(i).predicted().isBefore(b.arrivals().get(i - 1).predicted()));
        }
    }

    @Test
    void feedFailureFallsBackToTheSchedule() {
        RealtimeFeed broken = new RealtimeFeed() {
            @Override
            public List<ServiceAlert> alerts() {
                throw new FeedException("down");
            }

            @Override
            public RealtimeSnapshot snapshot() {
                throw new FeedException("down");
            }
        };
        ArrivalBoard board = new ArrivalBoard(planner, new ResilientRealtimeFeed(broken));
        ArrivalBoard.Board b = board.arrivals(net.stopById("STN_SP"), MON_8AM, 5);
        assertFalse(b.liveDataAvailable());
        assertEquals(5, b.arrivals().size());
        assertTrue(b.arrivals().stream().noneMatch(Arrival::realtime));
        assertTrue(b.note().contains("unavailable"));
    }

    @Test
    void controllerResolvesTheStopFirst() {
        TripController c = new TripController(planner, new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
        TripController.ArrivalsResult ok = c.getArrivals("Spadina", MON_8AM, 5);
        assertNotNull(ok.board());
        assertEquals("STN_SP", ok.board().stop().id());
        TripController.ArrivalsResult ambiguous = c.getArrivals("Finch Ave", MON_8AM, 5);
        assertNotNull(ambiguous.clarification());
    }
}
