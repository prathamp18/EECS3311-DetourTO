package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.domain.WalkLeg;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature F06: compares the computed options in plain language. The model gets the options as
 * data and may only cite option ids, minute values and lines that exist in them; the validator
 * checks this. If the answer stays invalid, a deterministic comparison is returned instead.
 */
public final class ExplanationAgent extends TransitAgent {

    static final String SYSTEM_PROMPT = """
            You explain Toronto (TTC) trip options to a rider in plain, friendly language.
            You get the computed options as JSON. Compare them: time, transfers, walking, waiting, and
            any disruptions that were avoided. Recommend one option for the rider's strategy.
            Strict rules:
            - Refer to options only by their ids (opt-1, opt-2, ...).
            - Every number of minutes you write must appear in the data or be the difference between
              two values in the data. Write minutes as digits, e.g. "28 min".
            - Mention only lines and routes that appear in the options.
            Answer with JSON only:
            {"summary":"2-3 sentences","option_notes":{"opt-1":"one sentence"},
             "cited_itinerary_ids":["opt-1"],"cited_minutes":[28]}
            """;

    private static final java.time.format.DateTimeFormatter HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper json = new ObjectMapper();
    private List<Itinerary> current = List.of();

    public ExplanationAgent(LLMClient llm, ResponseParser parser, GroundingValidator validator) {
        super(llm, new ToolRegistry(), parser, validator);
    }

    /** Never throws: falls back to a deterministic comparison. */
    public synchronized Explanation explain(TripQuery q, List<Itinerary> options, List<Disruption> avoided,
                                            LocalDateTime now) {
        if (options.isEmpty()) {
            return fallback(q, options);
        }
        current = options;
        AgentContext ctx = new AgentContext(now, q, options, new ConversationHistory());
        AgentResult r = run(new AgentRequest("explain", dataJson(q, options, avoided), ctx));
        return r.success() ? (Explanation) r.output() : fallback(q, options);
    }

    /** The comparison shown when there is no (valid) LLM answer. Uses only computed values. */
    public static Explanation fallback(TripQuery q, List<Itinerary> options) {
        if (options.isEmpty()) {
            return new Explanation("There are no options to compare.", Map.of(), List.of(), List.of(), false);
        }
        Map<String, String> notes = new LinkedHashMap<>();
        List<Integer> minutes = new ArrayList<>();
        for (Itinerary it : options) {
            String routes = String.join(", ", it.rideLegs().stream().map(l -> l.route().label()).distinct().toList());
            notes.put(it.id(), String.format("Leaves %s, arrives %s: %d min, %d transfer%s, %d m walking via %s.",
                    HM.format(it.departure()), HM.format(it.arrival()), it.durationMinutes(), it.transfers(),
                    it.transfers() == 1 ? "" : "s", it.walkMeters(), routes.isEmpty() ? "walking" : routes));
            minutes.add(it.durationMinutes());
        }
        Itinerary best = options.get(0);
        String summary = String.format("%s is the best match for %s: %d min with %d transfer%s.", best.id(),
                q.strategyKind().cliName(), best.durationMinutes(), best.transfers(), best.transfers() == 1 ? "" : "s");
        return new Explanation(summary, notes, options.stream().map(Itinerary::id).toList(), minutes, false);
    }

    String dataJson(TripQuery q, List<Itinerary> options, List<Disruption> avoided) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", q.origin().name());
        query.put("to", q.destination().name());
        query.put(q.isArriveBy() ? "arrive_by" : "depart_at", q.time().toString());
        query.put("strategy", q.strategyKind().cliName());
        root.put("query", query);
        List<Object> opts = new ArrayList<>();
        for (Itinerary it : options) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", it.id());
            o.put("departure", HM.format(it.departure()));
            o.put("arrival", HM.format(it.arrival()));
            o.put("minutes", it.durationMinutes());
            o.put("transfers", it.transfers());
            o.put("walk_meters", it.walkMeters());
            o.put("walk_minutes", Math.round(it.walkSeconds() / 60.0));
            List<Object> legs = new ArrayList<>();
            for (Leg leg : it.legs()) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("mode", leg.mode().name());
                if (leg instanceof RideLeg r) {
                    l.put("route", r.route().label());
                }
                l.put("from", leg.from().name());
                l.put("to", leg.to().name());
                l.put("start", HM.format(leg.start()));
                l.put("minutes", leg.minutes());
                if (leg instanceof WalkLeg w) {
                    l.put("meters", w.meters());
                }
                legs.add(l);
            }
            o.put("legs", legs);
            opts.add(o);
        }
        root.put("options", opts);
        root.put("disruptions_avoided", avoided.stream().map(Disruption::summary).toList());
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected List<String> toolNames() {
        return List.of();
    }

    @Override
    protected Object parseFinal(LLMResponse r, AgentContext ctx) {
        return parser.toExplanation(r.text());
    }

    @Override
    protected ValidationReport validate(Object output, AgentContext ctx) {
        return validator.validateExplanation((Explanation) output, current, ctx.query().orElse(null));
    }
}
