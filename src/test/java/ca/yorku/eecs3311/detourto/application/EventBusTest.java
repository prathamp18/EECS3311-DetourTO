package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventBusTest {

    @TempDir
    Path dir;

    @Test
    void listenersGetOnlyTheirTypesAndCanUnsubscribe() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        EventListener l = e -> got.add(e.type().name());
        bus.subscribe(EventType.ALERTS_UPDATED, l);
        bus.publish(TransitEvent.of(EventType.ALERTS_UPDATED, Map.of()));
        bus.publish(TransitEvent.of(EventType.AGENT_STEP, Map.of()));
        bus.unsubscribe(EventType.ALERTS_UPDATED, l);
        bus.publish(TransitEvent.of(EventType.ALERTS_UPDATED, Map.of()));
        assertEquals(List.of("ALERTS_UPDATED"), got);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        bus.subscribe(EventType.AGENT_STEP, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(EventType.AGENT_STEP, e -> got.add("second"));
        bus.publish(TransitEvent.of(EventType.AGENT_STEP, Map.of()));
        assertEquals(List.of("second"), got);
    }

    @Test
    void traceLoggerWritesOneJsonLinePerEvent() throws IOException {
        Path trace = dir.resolve("logs/trace.jsonl");
        TraceLogger logger = new TraceLogger(trace);
        logger.onEvent(TransitEvent.of(EventType.AGENT_STEP, Map.of("agent", "TripRequestAgent", "kind", "tool_call")));
        logger.onEvent(TransitEvent.of(EventType.ITINERARY_UPDATED, Map.of("options", List.of("opt-1"))));
        List<String> lines = Files.readAllLines(trace);
        assertEquals(2, lines.size());
        JsonNode first = new ObjectMapper().readTree(lines.get(0));
        assertEquals("AGENT_STEP", first.get("type").asText());
        assertEquals("tool_call", first.get("kind").asText());
        assertTrue(first.has("ts"));
    }

    @Test
    void agentStepsFromTheControllerReachTheBus() throws IOException {
        TransitNetwork net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        ScriptedLLMClient llm = new ScriptedLLMClient(
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "Keele")))),
                LLMResponse.toolCalls(List.of(new ToolCall("local-2", "find_stop", Map.of("query", "Union")))),
                LLMResponse.text("{\"type\":\"trip\",\"origin_stop_id\":\"STN_KE\",\"destination_stop_id\":\"STN_UN\","
                        + "\"time\":\"2026-10-28T08:00\",\"time_type\":\"depart_at\"}"));
        TripController c = new TripController(new JourneyPlanner(net), new PlaceResolver(net), llm);
        List<TransitEvent> events = new ArrayList<>();
        c.eventBus().subscribeAll(events::add);
        c.planFromText("Keele to Union", LocalDateTime.of(2026, 10, 28, 8, 0));

        List<String> kinds = events.stream().filter(e -> e.type() == EventType.AGENT_STEP)
                .map(e -> (String) e.payload().get("kind")).toList();
        assertEquals(List.of("llm_call", "tool_call", "llm_call", "tool_call", "llm_call", "validated"), kinds);
        assertEquals(EventType.ITINERARY_UPDATED, events.get(events.size() - 1).type());
    }
}
