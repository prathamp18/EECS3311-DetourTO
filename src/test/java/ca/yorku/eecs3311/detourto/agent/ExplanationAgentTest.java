package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExplanationAgentTest {

    static TransitNetwork net;
    static TripQuery query;
    static List<Itinerary> options;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 8, 0);

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
        query = TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), NOW);
        options = new JourneyPlanner(net).plan(query);
    }

    static ExplanationAgent agent(ScriptedLLMClient llm) {
        return new ExplanationAgent(llm, new ResponseParser(), new GroundingValidator(net));
    }

    @Test
    void groundedExplanationIsAccepted() {
        int d1 = options.get(0).durationMinutes();
        ScriptedLLMClient llm = new ScriptedLLMClient(LLMResponse.text("""
                {"summary":"Take opt-1: it takes %d min with one change at Spadina.",
                 "option_notes":{"opt-1":"Fastest.","opt-2":"Same trip 5 min later."},
                 "cited_itinerary_ids":["opt-1","opt-2"],"cited_minutes":[%d,5]}""".formatted(d1, d1)));
        Explanation e = agent(llm).explain(query, options, List.of(), NOW);
        assertTrue(e.llmGenerated(), e.summary());
        assertTrue(e.summary().contains("opt-1"));
        String data = llm.requests().get(0).msgs().get(0).content();
        assertTrue(data.contains("\"id\":\"opt-1\"") && data.contains("\"minutes\":" + d1), "options sent as data");
        assertTrue(llm.requests().get(0).tools().isEmpty(), "explanations use no tools");
    }

    @Test
    void inventedMinutesAndOptionsAreCaughtAndFallBackToTheTable() {
        String bad = """
                {"summary":"opt-7 saves 97 min on Line 4.","option_notes":{},"cited_itinerary_ids":["opt-7"],"cited_minutes":[97]}""";
        ScriptedLLMClient llm = new ScriptedLLMClient(LLMResponse.text(bad), LLMResponse.text(bad));
        Explanation e = agent(llm).explain(query, options, List.of(), NOW);
        assertFalse(e.llmGenerated());
        assertTrue(e.summary().startsWith("opt-1 is the best match"), e.summary());
        var repair = llm.requests().get(1).msgs();
        String feedback = repair.get(repair.size() - 1).content();
        assertTrue(feedback.contains("opt-7 does not exist"), feedback);
        assertTrue(feedback.contains("97 min does not match"), feedback);
        assertTrue(feedback.contains("route 4 is not used"), feedback);
    }

    @Test
    void validatorAllowsDifferencesBetweenOptions() {
        GroundingValidator v = new GroundingValidator(net);
        int gap = (int) java.time.Duration.between(options.get(0).arrival(), options.get(2).arrival()).toMinutes();
        Explanation e = new Explanation("opt-3 arrives " + gap + " minutes after opt-1.", java.util.Map.of(),
                List.of("opt-1", "opt-3"), List.of(), true);
        assertTrue(v.validateExplanation(e, options, query).valid());
    }

    @Test
    void fallbackUsesOnlyComputedValues() {
        Explanation e = ExplanationAgent.fallback(query, options);
        assertFalse(e.llmGenerated());
        assertEquals(options.size(), e.optionNotes().size());
        assertTrue(new GroundingValidator(net).validateExplanation(e, options, query).valid(),
                "the deterministic comparison passes the same check");
    }
}
