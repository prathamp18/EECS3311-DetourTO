package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.util.List;

/**
 * Feature F09: picks lasting preferences out of what the rider says ("never put me on the 29",
 * "my school is York U"). Every route and stop is checked by the validator; nothing is saved
 * from a single trip request ("no streetcars today" is not a preference).
 */
public final class PreferenceExtractorAgent extends TransitAgent {

    static final String SYSTEM_PROMPT = """
            You find LASTING rider preferences in a message to a Toronto (TTC) trip planner.
            Only record things the rider wants every time ("never", "always", "I use a wheelchair",
            "my home is ..."), not one-off requests for this trip ("no streetcars today").
            Use find_stop to get stop ids for saved places. Never invent ids.
            Allowed updates:
              {"key":"save_place","value":"<label>=<stop_id>"}   label like home, school, work
              {"key":"avoid_route","value":"29"}                  or allow_route
              {"key":"default_strategy","value":"fewest-transfers"} fastest|fewest-transfers|least-walking|accessible
              {"key":"step_free","value":"true"}
            Answer with JSON only: {"updates":[ ... ]}   (an empty list if there are none)
            """;

    private final TransitNetwork network;

    public PreferenceExtractorAgent(LLMClient llm, ToolRegistry tools, ResponseParser parser,
                                    GroundingValidator validator, TransitNetwork network) {
        super(llm, tools, parser, validator);
        this.network = network;
    }

    /** Validated updates, or an empty list if there are none or the model's answer was invalid. */
    @SuppressWarnings("unchecked")
    public List<PreferenceUpdate> extract(String text, AgentContext ctx) {
        AgentResult r = run(new AgentRequest("extract_preferences", text, ctx));
        return r.success() ? (List<PreferenceUpdate>) r.output() : List.of();
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected List<String> toolNames() {
        return List.of(FindStopTool.NAME);
    }

    @Override
    protected Object parseFinal(LLMResponse r, AgentContext ctx) {
        return parser.toPreferences(r.text());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ValidationReport validate(Object output, AgentContext ctx) {
        return validator.validatePreferences((List<PreferenceUpdate>) output, ctx);
    }

    TransitNetwork network() {
        return network;
    }
}
