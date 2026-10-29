package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ToolsTest {

    static TransitNetwork net;
    static ToolRegistry tools;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        tools = new ToolRegistry()
                .register(new FindStopTool(new PlaceResolver(net)))
                .register(new PlanJourneyTool(new JourneyPlanner(net)));
    }

    @Test
    void findStopReturnsCandidatesAndRecordsThem() throws IOException {
        AgentContext ctx = AgentContext.at(LocalDateTime.of(2026, 10, 28, 8, 0));
        ToolResult r = tools.execute(new ToolCall("1", "find_stop", Map.of("query", "St George")), ctx);
        assertTrue(r.ok());
        JsonNode json = JSON.readTree(r.toJson());
        assertEquals("STN_SG", json.at("/data/candidates/0/stop_id").asText());
        assertTrue(ctx.stopWasReturnedByTool("STN_SG"));
        assertFalse(tools.execute(new ToolCall("2", "find_stop", Map.of("query", "zzzz")), ctx).ok());
    }

    @Test
    void planJourneyRunsTheRealPlanner() throws IOException {
        AgentContext ctx = AgentContext.at(LocalDateTime.of(2026, 10, 28, 8, 0));
        ToolResult r = tools.execute(new ToolCall("1", "plan_journey", Map.of(
                "origin_stop_id", "STN_KE", "destination_stop_id", "STN_UN",
                "time", "2026-10-28T08:00", "time_type", "depart_at")), ctx);
        assertTrue(r.ok(), r.error());
        JsonNode json = JSON.readTree(r.toJson());
        assertEquals("opt-1", json.at("/data/options/0/id").asText());
        assertEquals(1, json.at("/data/options/0/transfers").asInt());
        assertFalse(tools.execute(new ToolCall("2", "plan_journey", Map.of(
                "origin_stop_id", "NOPE", "destination_stop_id", "STN_UN", "time", "2026-10-28T08:00")), ctx).ok());
    }

    @Test
    void unknownToolsAndCrashesAreReportedToTheModel() {
        AgentContext ctx = AgentContext.at(LocalDateTime.now());
        assertFalse(tools.execute(new ToolCall("1", "delete_everything", Map.of()), ctx).ok());
        Tool broken = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("broken", "always fails", Map.of("type", "object", "properties", Map.of()));
            }

            @Override
            public ToolResult execute(ToolCall call, AgentContext c) {
                throw new IllegalStateException("boom");
            }
        };
        ToolResult r = new ToolRegistry().register(broken).execute(new ToolCall("1", "broken", Map.of()), ctx);
        assertFalse(r.ok());
        assertTrue(r.error().contains("boom"));
    }
}
