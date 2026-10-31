package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.AlertInterpreterAgent;
import ca.yorku.eecs3311.detourto.agent.FindStopTool;
import ca.yorku.eecs3311.detourto.agent.GroundingValidator;
import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.ResponseParser;
import ca.yorku.eecs3311.detourto.agent.RouteStopsTool;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.agent.ToolRegistry;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AlertServiceTest {

    static TransitNetwork net;
    static final LocalDateTime MON_8AM = LocalDateTime.of(2026, 11, 2, 8, 0);
    static final GtfsRealtimeAdapter SAMPLE_FEED = new GtfsRealtimeAdapter("data/sample/realtime");

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    static AlertInterpreterAgent interpreter(ScriptedLLMClient llm) {
        ToolRegistry tools = new ToolRegistry().register(new RouteStopsTool(net))
                .register(new FindStopTool(new PlaceResolver(net)));
        return new AlertInterpreterAgent(llm, tools, new ResponseParser(), new GroundingValidator(net), net);
    }

    static LLMResponse routeStops(String route) {
        return LLMResponse.toolCalls(List.of(new ToolCall("local-r", "get_route_stops", Map.of("route_id", route))));
    }

    @Test
    void structuredAlertsAreConvertedWithoutTheLlm() {
        AlertService service = new AlertService(SAMPLE_FEED, null, net);
        List<Disruption> ds = service.currentDisruptions(MON_8AM);
        assertEquals(4, ds.size());

        Disruption closure = ds.get(0);
        assertEquals(DisruptionKind.CLOSURE, closure.kind());
        assertEquals(Set.of("STN_MU", "STN_QP", "STN_SPT", "STN_OS"), closure.closedStopIds());
        assertEquals(1.0, closure.confidence());
        assertFalse(closure.llmDerived());
        assertTrue(closure.appliesByDefault());

        assertEquals(DisruptionKind.ELEVATOR_OUTAGE, ds.get(1).kind());
        assertEquals(DisruptionKind.DELAY, ds.get(3).kind());
        assertFalse(ds.get(3).affectsRouting());

        Disruption freeText = ds.get(2);
        assertEquals(DisruptionKind.UNKNOWN, freeText.kind(), "no LLM: free-text alert is kept as text");
        assertEquals(0.0, freeText.confidence());
        assertEquals(0, service.interpretedCount());
    }

    @Test
    void freeTextAlertIsInterpretedOnceAndGrounded() {
        ScriptedLLMClient llm = new ScriptedLLMClient(routeStops("510"), LLMResponse.text("""
                {"kind":"DETOUR","closed_stop_ids":["SPQU_510"],"affected_route_ids":["510"],"confidence":0.9,
                 "summary":"510 streetcars skip Spadina Ave at Queen; board at King instead."}"""));
        AlertService service = new AlertService(SAMPLE_FEED, interpreter(llm), net);

        Disruption d = service.currentDisruptions(MON_8AM).get(2);
        assertEquals(DisruptionKind.DETOUR, d.kind());
        assertEquals(Set.of("SPQU_510"), d.closedStopIds());
        assertTrue(d.llmDerived());
        assertTrue(d.appliesByDefault());
        assertTrue(llm.requests().get(0).msgs().get(0).content().contains("Spadina Ave at Queen St West"),
                "the alert text is sent to the model");

        service.currentDisruptions(MON_8AM.plusMinutes(5));
        assertEquals(1, service.interpretedCount(), "cached by alert id and text");
        assertEquals(0, llm.remaining());
    }

    @Test
    void stopFromTheWrongRouteIsRejectedAndTheAlertStaysUnknown() {
        String wrong = """
                {"kind":"DETOUR","closed_stop_ids":["SG_L2"],"affected_route_ids":["510"],"confidence":0.8,"summary":"x"}""";
        ScriptedLLMClient llm = new ScriptedLLMClient(routeStops("510"), LLMResponse.text(wrong),
                LLMResponse.text(wrong));
        Disruption d = new AlertService(SAMPLE_FEED, interpreter(llm), net).currentDisruptions(MON_8AM).get(2);
        assertEquals(DisruptionKind.UNKNOWN, d.kind());
        assertEquals(0.0, d.confidence());
        assertFalse(d.affectsRouting());
        String feedback = llm.requests().get(2).msgs().get(llm.requests().get(2).msgs().size() - 1).content();
        assertTrue(feedback.contains("not returned by find_stop or get_route_stops")
                || feedback.contains("not served by"), feedback);
    }

    @Test
    void lowConfidenceInterpretationIsListedButNotAppliedByDefault() {
        ScriptedLLMClient llm = new ScriptedLLMClient(routeStops("510"), LLMResponse.text("""
                {"kind":"DETOUR","closed_stop_ids":["SPQU_510"],"affected_route_ids":["510"],"confidence":0.3,"summary":"maybe"}"""));
        Disruption d = new AlertService(SAMPLE_FEED, interpreter(llm), net).currentDisruptions(MON_8AM).get(2);
        assertTrue(d.affectsRouting());
        assertFalse(d.appliesByDefault());
    }
}
