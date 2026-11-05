package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.PreferenceRules;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
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

class ProfileTest {

    static TransitNetwork net;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    static TripController offline() {
        return new TripController(new JourneyPlanner(net), new PlaceResolver(net));
    }

    @Test
    void savedPlacesWorkAsPlaceNames() {
        TripController c = offline();
        assertEquals("STN_YU", c.savePlace("School", "York U").stop().orElseThrow().id());
        assertEquals("STN_YU", c.resolvePlace("school").stop().orElseThrow().id());
        assertEquals("STN_YU", c.resolvePlace("my school").stop().orElseThrow().id());
        assertTrue(c.savePlace("gym", "Finch Ave").stop().isEmpty(), "ambiguous place is not saved");
        assertFalse(c.profile().placeStopId("gym").isPresent());
    }

    @Test
    void profileDefaultsApplyToNewTrips() {
        TripController c = offline();
        c.updateProfile(List.of(new PreferenceUpdate("avoid_route", "1"),
                new PreferenceUpdate("default_strategy", "fewest-transfers")));
        TripQuery q = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), NOW);
        c.applyProfile(q, false);
        assertEquals(Set.of("1"), q.avoidRouteIds());
        assertEquals(StrategyKind.FEWEST_TRANSFERS, q.strategyKind());
        TripQuery chosen = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), NOW);
        c.applyProfile(chosen, true);
        assertEquals(StrategyKind.FASTEST, chosen.strategyKind(), "a strategy the rider chose wins");

        c.updateProfile(List.of(new PreferenceUpdate("step_free", "true")));
        TripQuery wheelchair = TripQuery.departAt(net.stopById("STN_YU"), net.stopById("STN_SPT"), NOW);
        c.applyProfile(wheelchair, true);
        assertEquals(StrategyKind.ACCESSIBLE, wheelchair.strategyKind(), "step-free riders always get step-free trips");
        assertTrue(c.planFromForm(wheelchair).options().isEmpty());
    }

    @Test
    void rulesLearnLastingPreferencesOffline() {
        TripController c = offline();
        assertTrue(PreferenceRules.mentionsPreference("never put me on the 29"));
        assertFalse(PreferenceRules.mentionsPreference("Keele to Union, no streetcars"));
        List<String> learned = c.learnPreferences("Never put me on the 29. My school is York U. I use a wheelchair.", NOW);
        assertEquals(3, learned.size(), learned.toString());
        assertEquals(Set.of("29"), c.profile().avoidedRouteIds());
        assertEquals("STN_YU", c.profile().placeStopId("school").orElseThrow());
        assertTrue(c.profile().needsStepFree());
        assertTrue(c.learnPreferences("never take route 999", NOW).isEmpty(), "unknown routes are not saved");
    }

    @Test
    void agentPreferencesAreGrounded() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                // invented stop id first: rejected
                LLMResponse.text("{\"updates\":[{\"key\":\"save_place\",\"value\":\"home=STN_DF\"}]}"),
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "Dufferin")))),
                LLMResponse.text("{\"updates\":[{\"key\":\"save_place\",\"value\":\"home=STN_DF\"},"
                        + "{\"key\":\"avoid_route\",\"value\":\"29\"}]}"));
        TripController c = new TripController(new JourneyPlanner(net), new PlaceResolver(net), llm);
        List<String> learned = c.learnPreferences("I live near Dufferin and I never want the 29", NOW);
        assertEquals(2, learned.size(), learned.toString());
        assertEquals("STN_DF", c.profile().placeStopId("home").orElseThrow());
        assertEquals(Set.of("29"), c.profile().avoidedRouteIds());
    }
}
