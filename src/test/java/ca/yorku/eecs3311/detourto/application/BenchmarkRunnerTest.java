package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.domain.BenchmarkReport;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.ReplayScenario;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import ca.yorku.eecs3311.detourto.infrastructure.ScenarioLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunnerTest {

    static TransitNetwork net;
    static List<ReplayScenario> scenarios;

    @TempDir
    Path dir;

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        scenarios = new ScenarioLoader().load(Path.of("scenarios"));
    }

    static BenchmarkRunner runner(ScriptedLLMClient llm) {
        return new BenchmarkRunner(new JourneyPlanner(net), new PlaceResolver(net), llm);
    }

    @Test
    void sampleScenariosLoadInFileOrder() {
        assertEquals(5, scenarios.size());
        assertEquals("Line 1 closed St George to St Andrew", scenarios.get(0).name());
        assertTrue(scenarios.get(4).arriveBy());
    }

    @Test
    void withoutAnLlmStructuredAlertsMatchAndFreeTextDoesNot() {
        List<BenchmarkReport> r = runner(null).runAll(scenarios);
        BenchmarkReport closure = r.get(0);
        assertTrue(closure.disruptionMatched());
        assertTrue(closure.feasible());
        assertTrue(closure.arrivalDelayMinutes() > 0, "the closure makes the trip longer");
        assertNull(closure.error());

        assertTrue(r.get(1).disruptionMatched());
        assertFalse(r.get(1).feasible(), "no step-free way into Bay while its elevator is out");
        assertFalse(r.get(2).disruptionMatched(), "free-text alert needs the interpreter");
        assertTrue(r.get(3).disruptionMatched(), "a delay closes nothing");
        assertEquals(0, r.get(4).arrivalDelayMinutes());
        assertTrue(r.stream().allMatch(x -> x.llmCalls() == 0 && !x.usedLlm()));
    }

    @Test
    void withAnLlmTheFreeTextAlertIsInterpretedAndCounted() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                // trip request agent
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "Union")))),
                LLMResponse.toolCalls(List.of(new ToolCall("local-2", "find_stop", Map.of("query", "Spadina and Queen")))),
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_UN\",\"destination_stop_id\":\"SPQU_510\","
                        + "\"time\":\"2026-11-02T08:00\",\"time_type\":\"depart_at\"}"),
                // alert interpreter: first answer is ungrounded, then it looks the route up
                LLMResponse.text("{\"kind\":\"DETOUR\",\"closed_stop_ids\":[\"SPQU_510\"],\"affected_route_ids\":[\"510\"],"
                        + "\"confidence\":0.9,\"summary\":\"skips Queen\"}"),
                LLMResponse.toolCalls(List.of(new ToolCall("local-3", "get_route_stops", Map.of("route_id", "510")))),
                LLMResponse.text("{\"kind\":\"DETOUR\",\"closed_stop_ids\":[\"SPQU_510\"],\"affected_route_ids\":[\"510\"],"
                        + "\"confidence\":0.9,\"summary\":\"skips Queen\"}"));
        BenchmarkReport r = runner(llm).run(scenarios.get(2));
        assertNull(r.error());
        assertTrue(r.usedLlm());
        assertTrue(r.disruptionMatched());
        assertEquals(1, r.groundingViolations());
        assertEquals(6, r.llmCalls());
        assertFalse(r.feasible(), "the destination itself is skipped by the 510");
    }

    @Test
    void agentFailureMarksOnlyThatScenarioFailed() {
        ScriptedLLMClient llm = new ScriptedLLMClient(LLMResponse.text("not json"), LLMResponse.text("still not json"));
        BenchmarkReport r = runner(llm).run(scenarios.get(0));
        assertFalse(r.passed());
        assertTrue(r.error().contains("agent failed"), r.error());
    }

    @Test
    void invalidScenarioFilesAreRejectedWithTheFileName() throws IOException {
        Files.writeString(dir.resolve("bad.json"), "{\"name\":\"x\",\"at\":\"tomorrow\",\"from\":\"a\",\"to\":\"b\"}");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScenarioLoader().load(dir.resolve("bad.json")));
        assertTrue(e.getMessage().startsWith("bad.json"), e.getMessage());
        Files.writeString(dir.resolve("bad.json"), "{\"name\":\"x\",\"at\":\"2026-11-02T08:00\"}");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScenarioLoader().load(dir.resolve("bad.json"))).getMessage().contains("request"));
    }
}
