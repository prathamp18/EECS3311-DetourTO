package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TripRequestAgentTest {

    static TransitNetwork net;
    static ToolRegistry tools;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        tools = new ToolRegistry()
                .register(new FindStopTool(new PlaceResolver(net)))
                .register(new PlanJourneyTool(new JourneyPlanner(net)));
    }

    static TripRequestAgent agent(ScriptedLLMClient llm) {
        return new TripRequestAgent(llm, tools, new ResponseParser(), new GroundingValidator(net), net);
    }

    static LLMResponse findStop(String id, String query) {
        return LLMResponse.toolCalls(List.of(new ToolCall(id, "find_stop", Map.of("query", query))));
    }

    static AgentResult run(ScriptedLLMClient llm, String text) {
        return agent(llm).run(new AgentRequest("trip_request", text, AgentContext.at(NOW)));
    }

    @Test
    void groundedRequestBecomesATripQuery() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                findStop("local-1", "York U"),
                findStop("local-2", "Union"),
                LLMResponse.text("""
                        ```json
                        {"type":"trip","origin_stop_id":"STN_YU","destination_stop_id":"STN_UN",
                         "time":"2026-10-28T10:00","time_type":"arrive_by","strategy":"accessible",
                         "avoid_modes":["STREETCAR"],"avoid_route_ids":[],"max_walk_meters":500}
                        ```"""));
        AgentResult r = run(llm, "York U to Union by 10, no streetcars, I use a wheelchair");

        assertTrue(r.success(), r.errors().toString());
        TripQuery q = r.outputAs(TripQuery.class).orElseThrow();
        assertEquals("STN_YU", q.origin().id());
        assertEquals("STN_UN", q.destination().id());
        assertTrue(q.isArriveBy());
        assertEquals(LocalDateTime.of(2026, 10, 28, 10, 0), q.arriveBy());
        assertEquals(StrategyKind.ACCESSIBLE, q.strategyKind());
        assertEquals(Set.of(TransitMode.STREETCAR), q.avoidModes());
        assertEquals(500, q.maxWalkMeters());
        assertEquals(3, r.llmCallCount());
        assertEquals(2, r.toolCallCount());

        // the model saw the tool results and the current time
        var lastRequest = llm.requests().get(2);
        assertTrue(lastRequest.msgs().get(0).content().contains("2026-10-28T08:00"));
        assertTrue(lastRequest.msgs().stream().anyMatch(m -> m.role() == Role.TOOL && m.content().contains("STN_YU")));
        assertEquals(List.of("find_stop", "plan_journey"),
                lastRequest.tools().stream().map(ToolSpec::name).toList());
    }

    @Test
    void inventedStopIdIsRejectedThenRepaired() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                findStop("local-1", "York U"),
                // STN_KE is real, but the model never looked it up: not grounded
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_YU\",\"destination_stop_id\":\"STN_KE\"}"),
                findStop("local-2", "Keele"),
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_YU\",\"destination_stop_id\":\"STN_KE\"}"));
        AgentResult r = run(llm, "York U to Keele");

        assertTrue(r.success(), r.errors().toString());
        var repairPrompt = llm.requests().get(2).msgs();
        String feedback = repairPrompt.get(repairPrompt.size() - 1).content();
        assertTrue(feedback.contains("was not returned by find_stop"), feedback);
        TripQuery q = (TripQuery) r.output();
        assertFalse(q.isArriveBy());
        assertEquals(NOW, q.departAt(), "no time given means leave now");
    }

    @Test
    void madeUpStopIdFailsAfterOneRepair() {
        String bad = "{\"type\":\"trip\",\"origin_stop_id\":\"STN_YORKDALE\",\"destination_stop_id\":\"STN_UN\"}";
        ScriptedLLMClient llm = new ScriptedLLMClient(LLMResponse.text(bad), LLMResponse.text(bad));
        AgentResult r = run(llm, "Yorkdale to Union");

        assertFalse(r.success());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("STN_YORKDALE")), r.errors().toString());
        assertEquals(2, r.llmCallCount());
    }

    @Test
    void ambiguousPlaceGivesAGroundedClarification() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                findStop("local-1", "Finch Ave"),
                LLMResponse.text("{\"type\":\"clarification\",\"question\":\"Which stop on Finch Ave?\","
                        + "\"candidate_stop_ids\":[\"FWKE_36\",\"FWBA_36\"]}"));
        AgentResult r = run(llm, "from Finch Ave to Union");

        assertTrue(r.success(), r.errors().toString());
        ClarificationRequest c = r.outputAs(ClarificationRequest.class).orElseThrow();
        assertEquals(2, c.candidates().size());
    }

    @Test
    void pastTimesAndNonJsonAnswersAreRejected() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                findStop("local-1", "York U"), findStop("local-2", "Union"),
                LLMResponse.text("Sure! You should take Line 1."),
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_YU\",\"destination_stop_id\":\"STN_UN\","
                        + "\"time\":\"2026-10-20T09:00\",\"time_type\":\"depart_at\"}"));
        AgentResult r = run(llm, "York U to Union");
        assertFalse(r.success());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("in the past")), r.errors().toString());
        String firstFeedback = llm.requests().get(3).msgs().get(llm.requests().get(3).msgs().size() - 1).content();
        assertTrue(firstFeedback.contains("JSON object"), firstFeedback);
    }

    @Test
    void toolLoopIsBounded() {
        ScriptedLLMClient llm = new ScriptedLLMClient();
        for (int i = 0; i < TransitAgent.DEFAULT_MAX_TOOL_ROUNDS + 1; i++) {
            llm.then(findStop("local-" + i, "York U"));
        }
        AgentResult r = run(llm, "loop forever");
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("too many tool rounds"));
    }

    @Test
    void llmErrorsBecomeAFailedResult() {
        LLMClient down = new LLMClient() {
            @Override
            public LLMResponse complete(List<Message> msgs, List<ToolSpec> t, String system) {
                throw new LLMException(LLMException.Kind.RATE_LIMIT, "quota");
            }

            @Override
            public String describe() {
                return "down";
            }
        };
        AgentResult r = new TripRequestAgent(down, tools, new ResponseParser(), new GroundingValidator(net), net)
                .run(new AgentRequest("trip_request", "x", AgentContext.at(NOW)));
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("RATE_LIMIT"));
    }
}
