package ca.yorku.eecs3311.detourto.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ReplayCommandTest {

    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;

    @TempDir
    Path dir;

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
        ReplayCommand cmd = new ReplayCommand();
        cmd.controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net));
        CommandLine cl = new CommandLine(cmd);
        cl.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
        return cl.execute(args);
    }

    @Test
    void jsonReportHasScenariosAndSummary() throws IOException {
        assertEquals(0, run("scenarios", "--json"));
        JsonNode root = new ObjectMapper().readTree(captured.toString(StandardCharsets.UTF_8));
        assertEquals(5, root.get("scenarios").size());
        assertEquals(5, root.at("/summary/scenarios").asInt());
        assertEquals(4, root.at("/summary/matched").asInt());
        assertTrue(root.at("/scenarios/0/disruptionMatched").asBoolean());
    }

    @Test
    void textTableAndInvalidFileExitCode() throws IOException {
        assertEquals(0, run("scenarios/01-line1-closure.json"));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("1 of 1 scenarios matched"));
        Files.writeString(dir.resolve("broken.json"), "{ not json");
        assertEquals(1, run(dir.resolve("broken.json").toString()));
    }
}
