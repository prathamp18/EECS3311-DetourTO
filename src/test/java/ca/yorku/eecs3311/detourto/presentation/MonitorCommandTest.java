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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MonitorCommandTest {

    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;

    @BeforeEach
    void capture() {
        original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    int run(String... args) throws IOException {
        TransitNetwork net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        MonitorCommand cmd = new MonitorCommand();
        cmd.controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
        return new CommandLine(cmd).execute(args);
    }

    @Test
    void rerouteAroundTheLine1Closure() throws IOException {
        assertEquals(0, run("--from", "York U", "--to", "Union", "--date", "2026-11-02", "--depart", "08:00",
                "--accept-reroute", "--json"));
        JsonNode root = new ObjectMapper().readTree(captured.toString(StandardCharsets.UTF_8));
        assertEquals("COMPLETED", root.get("finalState").asText());
        String states = root.get("changes").toString();
        assertTrue(states.contains("\"to\":\"AT_RISK\"") && states.contains("\"to\":\"REROUTED\""), states);
    }

    @Test
    void textOutputWithoutReroute() throws IOException {
        assertEquals(0, run("--from", "York U", "--to", "Union", "--date", "2026-11-02", "--depart", "08:00"));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("PLANNED -> AT_RISK"), out);
        assertTrue(out.contains("reroute available"), out);
    }
}
