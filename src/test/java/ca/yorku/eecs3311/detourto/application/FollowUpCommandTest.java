package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FollowUpCommandTest {

    static TransitNetwork net;
    static final LocalDateTime MON_8AM = LocalDateTime.of(2026, 11, 2, 8, 0);
    TripController c;
    TripQuery q;

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    @BeforeEach
    void plan() {
        c = new TripController(new JourneyPlanner(net), new PlaceResolver(net));
        q = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), MON_8AM);
        c.planFromForm(q);
    }

    @Test
    void leaveLaterThenUndoAndRedo() {
        PlanResult later = c.followUp("leave 15 minutes later", MON_8AM);
        assertEquals(MON_8AM.plusMinutes(15), q.departAt());
        assertTrue(later.message().startsWith("Leave 15 min later."), later.message());
        assertFalse(later.options().get(0).departure().isBefore(MON_8AM.plusMinutes(15)));

        c.undo();
        assertEquals(MON_8AM, q.departAt());
        c.redo();
        assertEquals(MON_8AM.plusMinutes(15), q.departAt());
        assertEquals(List.of("Leave 15 min later"), c.history().applied());
    }

    @Test
    void severalChangesUndoInReverseOrder() {
        c.followUp("avoid line 1", MON_8AM);
        c.followUp("no streetcars", MON_8AM);
        c.followUp("fewest transfers", MON_8AM);
        assertEquals(Set.of("1"), q.avoidRouteIds());
        assertEquals(Set.of(TransitMode.STREETCAR), q.avoidModes());
        assertEquals(StrategyKind.FEWEST_TRANSFERS, q.strategyKind());
        for (Itinerary it : c.lastOptions()) {
            assertFalse(it.routeIds().contains("1"));
            assertTrue(it.rideLegs().stream().noneMatch(l -> l.mode() == TransitMode.STREETCAR));
        }

        c.followUp("undo", MON_8AM);
        assertEquals(StrategyKind.FASTEST, q.strategyKind());
        c.followUp("undo", MON_8AM);
        assertTrue(q.avoidModes().isEmpty());
        c.followUp("undo", MON_8AM);
        assertTrue(q.avoidRouteIds().isEmpty());
        assertTrue(c.undo().message().startsWith("Nothing to undo."));
    }

    @Test
    void destinationChangeAndArriveBy() {
        c.followUp("what about St George instead?", MON_8AM);
        assertEquals("STN_SG", q.destination().id());
        c.followUp("I need to be there by 9:30", MON_8AM);
        assertTrue(q.isArriveBy());
        assertEquals(MON_8AM.withHour(9).withMinute(30), q.arriveBy());
        c.undo();
        assertFalse(q.isArriveBy());
        assertEquals(MON_8AM, q.departAt());
    }

    @Test
    void newRedoIsClearedByANewCommandAndANewTripClearsHistory() {
        c.followUp("leave 10 minutes later", MON_8AM);
        c.undo();
        c.followUp("avoid line 2", MON_8AM);
        assertTrue(c.redo().message().startsWith("Nothing to redo."));
        c.planFromForm(TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_UN"), MON_8AM));
        assertFalse(c.history().canUndo());
    }

    @Test
    void badFollowUpsKeepTheTripUnchanged() {
        PlanResult r = c.followUp("avoid route 999", MON_8AM);
        assertTrue(r.message().contains("no route 999"), r.message());
        assertTrue(q.avoidRouteIds().isEmpty());
        assertTrue(c.followUp("tell me a joke", MON_8AM).message().startsWith("Sorry"));
        assertFalse(c.history().canUndo());
        assertTrue(r.hasOptions(), "the current options are still shown");
    }

    @Test
    void llmFollowUpIsGroundedAndInvalidAnswersFallBackToRules() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "St George")))),
                LLMResponse.text("{\"kind\":\"CHANGE_DESTINATION\",\"value\":\"STN_SG\"}"),
                // second follow-up: invented route twice, then the rules handle it
                LLMResponse.text("{\"kind\":\"AVOID_ROUTE\",\"value\":\"77\"}"),
                LLMResponse.text("{\"kind\":\"AVOID_ROUTE\",\"value\":\"77\"}"));
        TripController withLlm = new TripController(new JourneyPlanner(net), new PlaceResolver(net), llm);
        TripQuery tq = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), MON_8AM);
        withLlm.planFromForm(tq);

        withLlm.followUp("actually take me to st george", MON_8AM);
        assertEquals("STN_SG", tq.destination().id());
        assertTrue(llm.requests().get(0).system().contains("follow-up step"));

        withLlm.followUp("avoid line 2", MON_8AM);
        assertEquals(Set.of("2"), tq.avoidRouteIds(), "rules fallback after the invalid route");
    }
}
