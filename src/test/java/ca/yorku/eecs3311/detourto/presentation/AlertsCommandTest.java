package ca.yorku.eecs3311.detourto.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AlertsCommandTest {

    static TransitNetwork net;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    @BeforeEach
    void capture() {
        original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    TripController controller() {
        return new TripController(new JourneyPlanner(net), new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
    }

    String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void listsAlertsAsJson() throws IOException {
        AlertsCommand cmd = new AlertsCommand();
        cmd.controller = controller();
        assertEquals(0, new CommandLine(cmd).execute("--at", "2026-11-02T08:00", "--json"));
        JsonNode root = new ObjectMapper().readTree(out());
        assertEquals(4, root.get("disruptions").size());
        assertEquals("CLOSURE", root.at("/disruptions/0/kind").asText());
        assertEquals("structured", root.at("/disruptions/0/source").asText());
        assertEquals("STN_MU", root.at("/disruptions/0/closedStops/0").asText());
    }

    @Test
    void textListUsesStopNames() {
        AlertsCommand cmd = new AlertsCommand();
        cmd.controller = controller();
        assertEquals(0, new CommandLine(cmd).execute("--at", "2026-11-02T08:00"));
        assertTrue(out().contains("Queen's Park Station"), out());
        assertTrue(out().contains("not interpreted"), out());
    }

    @Test
    void planJsonListsDisruptionsAndIgnoreAlertsTurnsThemOff() throws IOException {
        PlanCommand cmd = new PlanCommand();
        cmd.controller = controller();
        assertEquals(0, new CommandLine(cmd).execute("--from", "York U", "--to", "Union",
                "--date", "2026-11-02", "--depart", "08:00", "--json"));
        JsonNode avoided = new ObjectMapper().readTree(out());
        assertEquals(2, avoided.get("disruptionsApplied").size());
        captured.reset();
        PlanCommand plain = new PlanCommand();
        plain.controller = controller();
        assertEquals(0, new CommandLine(plain).execute("--from", "York U", "--to", "Union",
                "--date", "2026-11-02", "--depart", "08:00", "--json", "--ignore-alerts"));
        assertEquals(0, new ObjectMapper().readTree(out()).get("disruptionsApplied").size());
    }
}
