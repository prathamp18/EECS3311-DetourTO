package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.util.List;
import java.util.Set;

/**
 * Feature F04: reads a free-text TTC alert and states which stops are closed. The model must look
 * the stops up with tools; the validator checks every id. If it still fails, the alert is kept
 * as UNKNOWN with confidence 0 so the rider sees the original text and routing is unchanged.
 */
public final class AlertInterpreterAgent extends TransitAgent {

    static final String SYSTEM_PROMPT = """
            You read Toronto (TTC) service alerts and state their effect on trip planning.
            Use get_route_stops for the route(s) named in the alert, and find_stop for any other place,
            to get exact stop ids. Never invent ids.
            kind:
              CLOSURE         no service at the listed stops (or on the whole route if no stops are listed)
              DETOUR          vehicles run but skip the listed stops
              DELAY           service runs late; list no stops
              ELEVATOR_OUTAGE no step-free access at the listed stations
              UNKNOWN         the alert does not say what changes
            closed_stop_ids are the stops riders cannot use (for DETOUR: the skipped stops only, not
            the suggested alternatives). confidence is how sure you are, from 0 to 1.
            Answer with JSON only:
            {"kind":"...","closed_stop_ids":[],"affected_route_ids":[],"confidence":0.9,
             "summary":"one short sentence for riders"}
            """;

    private final TransitNetwork network;
    private ServiceAlert current;

    public AlertInterpreterAgent(LLMClient llm, ToolRegistry tools, ResponseParser parser,
                                 GroundingValidator validator, TransitNetwork network) {
        super(llm, tools, parser, validator);
        this.network = network;
    }

    /** Interprets one alert. Never throws; failures become an UNKNOWN disruption. */
    public synchronized Disruption interpret(ServiceAlert alert, AgentContext ctx) {
        current = alert;
        String text = "Alert id: " + alert.id()
                + "\nRoutes named in the alert: " + alert.informedRouteIds()
                + "\nStops named in the alert: " + alert.informedStopIds()
                + "\nEffect field: " + alert.effect() + ", cause: " + alert.cause()
                + "\nHeader: " + alert.header()
                + "\nDescription: " + alert.description();
        AgentResult r = run(new AgentRequest("interpret_alert", text, ctx));
        if (r.success()) {
            return (Disruption) r.output();
        }
        return unknown(alert, String.join("; ", r.errors()));
    }

    static Disruption unknown(ServiceAlert alert, String reason) {
        return new Disruption("D-" + alert.id(), alert.id(), DisruptionKind.UNKNOWN,
                Set.copyOf(alert.informedRouteIds()), Set.of(), ResponseParser.toLocal(alert.activeFrom()),
                ResponseParser.toLocal(alert.activeTo()), 0.0, true,
                alert.header() + (reason.isEmpty() ? "" : " (could not be interpreted)"));
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected List<String> toolNames() {
        return List.of(RouteStopsTool.NAME, FindStopTool.NAME);
    }

    @Override
    protected Object parseFinal(LLMResponse r, AgentContext ctx) {
        return parser.toDisruption(r.text(), current, network);
    }

    @Override
    protected ValidationReport validate(Object output, AgentContext ctx) {
        return validator.validateDisruption((Disruption) output, ctx);
    }
}
