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

class ArrivalsCommandTest {

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
        ArrivalsCommand cmd = new ArrivalsCommand();
        cmd.controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
        return new CommandLine(cmd).execute(args);
    }

    @Test
    void textBoardShowsLiveAndScheduledTimes() throws IOException {
        assertEquals(0, run("Spadina", "--at", "2026-11-02T08:00", "-n", "10"));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("Spadina Station  (08:00)"), out);
        assertTrue(out.contains("scheduled"), out);
        assertTrue(out.contains("510 Spadina"), out);
    }

    @Test
    void jsonBoard() throws IOException {
        assertEquals(0, run("Spadina & College", "--at", "2026-11-02T08:00", "--json"));
        JsonNode root = new ObjectMapper().readTree(captured.toString(StandardCharsets.UTF_8));
        assertEquals("SPCO_510", root.at("/stop/id").asText());
        assertTrue(root.get("liveDataAvailable").asBoolean());
        assertTrue(root.at("/arrivals/0/realtime").asBoolean());
    }

    @Test
    void ambiguousStop() throws IOException {
        assertEquals(PlanCommand.EXIT_CLARIFY, run("Finch Ave", "--json"));
    }
}
