package ca.yorku.eecs3311.detourto.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TransitNetworkTest {

    static TransitNetwork net;

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    @Test
    void stGeorgePlatformsAreConnectedByAFootpath() {
        int l1 = net.indexOf(net.stopById("SG_L1"));
        int l2 = net.indexOf(net.stopById("SG_L2"));
        TransitNetwork.Footpath fp = net.footpathsFrom(l1).stream()
                .filter(f -> f.toStop() == l2).findFirst().orElseThrow();
        assertEquals(TransitNetwork.MIN_TRANSFER_SECONDS, fp.seconds());
    }

    @Test
    void stationResolvesToItsPlatforms() {
        List<Stop> platforms = net.boardingStopsFor(net.stopById("STN_SG"));
        assertEquals(Set.of("SG_L1", "SG_L2"),
                Set.copyOf(platforms.stream().map(Stop::id).toList()));
        assertEquals(List.of(net.stopById("SG_L1")), net.boardingStopsFor(net.stopById("SG_L1")));
    }

    @Test
    void tripsWithTheSameStopsShareAPattern() {
        int yu = net.indexOf(net.stopById("YU_L1"));
        assertFalse(net.patternsAt(yu).isEmpty());
        int total = net.patterns().stream().mapToInt(p -> p.trips().size()).sum();
        assertEquals(net.tripCount(), total);
        assertTrue(net.patterns().size() < 20, "expected a few patterns, got " + net.patterns().size());
    }

    @Test
    void calendarHandlesWeekdaysWeekendsAndChristmas() {
        ServiceCalendar cal = net.calendar();
        LocalDate thursday = LocalDate.of(2026, 10, 22);
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        LocalDate christmas = LocalDate.of(2026, 12, 25); // a Friday
        assertEquals(Set.of("WEEKDAY"), cal.activeServices(thursday));
        assertEquals(Set.of("WEEKEND"), cal.activeServices(saturday));
        assertEquals(Set.of("WEEKEND"), cal.activeServices(christmas));
        assertFalse(cal.isActive("WEEKDAY", LocalDate.of(2028, 1, 3)));
    }

    @Test
    void closuresAndElevatorOutagesShowInTheView() {
        Stop sgPlatform = net.stopById("SG_L1");
        Route line1 = net.findRoute("1").orElseThrow();
        NetworkView normal = net.fullView();
        assertFalse(normal.isStopClosed(sgPlatform));
        assertTrue(normal.isStepFree(sgPlatform));
        assertFalse(normal.isStepFree(net.stopById("STN_SPT")));

        NetworkView disrupted = net.withClosures(Set.of("STN_SG"), Set.of("1"), Set.of("STN_BAY"));
        assertTrue(disrupted.isStopClosed(sgPlatform)); // closed through its parent station
        assertTrue(disrupted.isRouteClosed(line1));
        assertFalse(disrupted.isStepFree(net.stopById("BAY_L2")));
    }

    @Test
    void stopsNearFindsCloseStopsOnly() {
        Stop union = net.stopById("STN_UN");
        List<Stop> near = net.stopsNear(union.lat(), union.lon(), 50);
        assertTrue(near.stream().anyMatch(s -> s.id().startsWith("UN")));
        assertTrue(near.stream().allMatch(s -> s.distanceMetersTo(union) <= 50));
    }
}
