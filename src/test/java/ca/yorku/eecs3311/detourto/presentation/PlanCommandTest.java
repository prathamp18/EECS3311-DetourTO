package ca.yorku.eecs3311.detourto.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class PlanCommandTest {

    static TripController controller;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;

    @BeforeAll
    static void load() throws IOException {
        TransitNetwork net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net));
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

    int run(String... args) {
        PlanCommand cmd = new PlanCommand();
        cmd.controller = controller;
        CommandLine cl = new CommandLine(cmd);
        cl.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
        return cl.execute(args);
    }

    String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void jsonOutputHasOptionsAndLegs() throws IOException {
        int code = run("--from", "Keele", "--to", "Union", "--date", "2026-10-22", "--depart", "8:00", "--json");
        assertEquals(0, code, out());
        JsonNode root = new ObjectMapper().readTree(out());
        assertEquals("ok", root.get("status").asText());
        assertEquals("STN_KE", root.at("/query/from/id").asText());
        assertEquals("2026-10-22T08:00", root.at("/query/departAt").asText());
        JsonNode first = root.get("options").get(0);
        assertEquals("opt-1", first.get("id").asText());
        assertEquals(1, first.get("transfers").asInt());
        assertEquals("SUBWAY", first.at("/legs/0/mode").asText());
    }

    @Test
    void textOutputIsReadable() {
        int code = run("--from", "York U", "--to", "St George", "--date", "2026-10-22", "--arrive-by", "09:00");
        assertEquals(0, code);
        String text = out();
        assertTrue(text.contains("York University Station -> St George Station, arrive by"), text);
        assertTrue(text.contains("opt-1"), text);
        assertTrue(text.contains("Line 1 Yonge-University"), text);
    }

    @Test
    void ambiguousPlaceExitsWithClarification() throws IOException {
        int code = run("--from", "Finch Ave", "--to", "Union", "--json");
        assertEquals(PlanCommand.EXIT_CLARIFY, code);
        JsonNode root = new ObjectMapper().readTree(out());
        assertEquals("clarification_needed", root.get("status").asText());
        assertTrue(root.at("/clarification/candidates").size() >= 2);
    }

    @Test
    void noRouteExitCode() {
        int code = run("--from", "York U", "--to", "St Patrick", "--date", "2026-10-22", "--depart", "08:00",
                "--strategy", "accessible");
        assertEquals(PlanCommand.EXIT_NO_ROUTE, code, out());
    }

    @Test
    void plainEnglishRequestUsesTheAgent() throws IOException {
        TransitNetwork net = controller.network();
        ScriptedLLMClient llm = new ScriptedLLMClient(
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "Keele")))),
                LLMResponse.toolCalls(List.of(new ToolCall("local-2", "find_stop", Map.of("query", "Union")))),
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_KE\",\"destination_stop_id\":\"STN_UN\","
                        + "\"time\":\"2026-10-28T08:00\",\"time_type\":\"depart_at\"}"));
        PlanCommand cmd = new PlanCommand();
        cmd.controller = new TripController(new JourneyPlanner(net), new PlaceResolver(net), llm);
        CommandLine cl = new CommandLine(cmd);
        int code = cl.execute("Keele to Union", "--date", "2026-10-28", "--depart", "08:00", "--json");
        assertEquals(0, code, out());
        JsonNode root = new ObjectMapper().readTree(out());
        assertEquals("STN_KE", root.at("/query/from/id").asText());
        assertEquals("opt-1", root.at("/options/0/id").asText());
    }

    @Test
    void plainEnglishWithoutAnLlmExplainsWhy() {
        int code = run("York U to Union");
        assertEquals(PlanCommand.EXIT_NOT_UNDERSTOOD, code);
        assertTrue(out().contains("needs an LLM"), out());
    }

    @Test
    void badOptionsAreUsageErrors() {
        assertEquals(2, run("York U to Union", "--from", "Keele"));
        assertEquals(2, run("--from", "York U"));
        assertEquals(2, run("--from", "York U", "--to", "Union", "--depart", "8pm"));
        assertEquals(2, run("--from", "York U", "--to", "Union", "--strategy", "cheapest"));
        assertEquals(2, run("--from", "York U", "--to", "Union", "--depart", "08:00", "--arrive-by", "09:00"));
    }
}
