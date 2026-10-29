package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.util.List;

/**
 * Feature F01: turns "York U by 10, no streetcars, step-free" into a validated TripQuery, or asks
 * the rider which stop they meant.
 */
public final class TripRequestAgent extends TransitAgent {

    static final String SYSTEM_PROMPT = """
            You are the trip-request step of DetourTO, a Toronto (TTC) trip planner.
            Turn the rider's request into ONE JSON object. Rules:
            1. Call find_stop for every place the rider mentions. Use only stop_id values returned by
               find_stop. Never invent or remember stop ids.
            2. If a place has several plausible candidates with similar scores and the request does not
               say which, answer with a clarification instead of guessing.
            3. Times are Toronto local time. "by 10" means arrive_by 10:00 today (tomorrow if that time
               has passed). No time given means depart_at the current time.
            4. Strategy: "fewest transfers" -> fewest-transfers, "less walking" -> least-walking,
               wheelchair / stroller / "no stairs" / "step-free" -> accessible, otherwise fastest.
            5. avoid_modes may contain SUBWAY, STREETCAR, BUS. avoid_route_ids holds route numbers
               like "29" only if the rider names them.
            You may call plan_journey to check that a trip exists, but you do not have to.

            Final answer formats (JSON only, no other text):
            {"type":"trip","origin_stop_id":"...","destination_stop_id":"...","time":"YYYY-MM-DDTHH:MM",
             "time_type":"depart_at"|"arrive_by","strategy":"fastest"|"fewest-transfers"|"least-walking"|"accessible",
             "avoid_modes":[],"avoid_route_ids":[],"max_walk_meters":800}
            {"type":"clarification","question":"...","candidate_stop_ids":["...","..."]}
            """;

    private final TransitNetwork network;

    public TripRequestAgent(LLMClient llm, ToolRegistry tools, ResponseParser parser,
                            GroundingValidator validator, TransitNetwork network) {
        super(llm, tools, parser, validator);
        this.network = network;
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected List<String> toolNames() {
        return List.of(FindStopTool.NAME, PlanJourneyTool.NAME);
    }

    @Override
    protected Object parseFinal(LLMResponse r, AgentContext ctx) {
        return parser.toTripRequest(r.text(), network, ctx.now());
    }

    @Override
    protected ValidationReport validate(Object output, AgentContext ctx) {
        if (output instanceof TripQuery q) {
            return validator.validateQuery(q, ctx);
        }
        if (output instanceof ClarificationRequest c) {
            return validator.validateClarification(c, ctx);
        }
        return ValidationReport.of(List.of("unexpected output type"));
    }
}
