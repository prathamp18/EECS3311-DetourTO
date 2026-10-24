package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TripControllerTest {

    static final LocalDateTime THU_8AM = LocalDateTime.of(2026, 10, 22, 8, 0);
    TransitNetwork net;
    TripController controller;

    @BeforeEach
    void setUp() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net));
    }

    @Test
    void plansAFormQueryAndRemembersIt() {
        TripQuery q = TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), THU_8AM);
        PlanResult r = controller.planFromForm(q);
        assertTrue(r.hasOptions());
        assertTrue(r.message().contains("fastest"));
        assertEquals(q, controller.currentQuery().orElseThrow());
        assertEquals(r.options(), controller.lastOptions());
    }

    @Test
    void setStrategyReplansTheCurrentQuery() {
        assertThrows(IllegalStateException.class, () -> controller.setStrategy(StrategyKind.ACCESSIBLE));
        controller.planFromForm(TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_SPT"), THU_8AM));
        PlanResult r = controller.setStrategy(StrategyKind.ACCESSIBLE);
        assertFalse(r.hasOptions());
        assertTrue(r.message().contains("step-free"));
    }

    @Test
    void sameOriginAndDestinationIsReported() {
        PlanResult r = controller.planFromForm(
                TripQuery.departAt(net.stopById("STN_UN"), net.stopById("STN_UN"), THU_8AM));
        assertFalse(r.hasOptions());
        assertTrue(r.message().contains("same"));
    }

    @Test
    void ambiguousOrUnknownPlacesAskForClarification() {
        TripController.Resolution finchAve = controller.resolvePlace("Finch Ave");
        assertTrue(finchAve.stop().isEmpty());
        assertNotNull(finchAve.clarification());
        assertTrue(finchAve.clarification().candidates().size() >= 2);

        TripController.Resolution unknown = controller.resolvePlace("Narnia");
        assertTrue(unknown.stop().isEmpty());
        assertTrue(unknown.clarification().candidates().isEmpty());

        assertEquals("STN_YU", controller.resolvePlace("York U").stop().orElseThrow().id());
    }
}
