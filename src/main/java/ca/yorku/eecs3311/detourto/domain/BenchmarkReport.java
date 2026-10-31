package ca.yorku.eecs3311.detourto.domain;

/**
 * Result of replaying one scenario.
 *
 * @param feasible            a route exists with the disruptions applied
 * @param arrivalDelayMinutes how much later the best disrupted option arrives than the best
 *                            undisrupted one (for arrive-by: how much earlier it must leave)
 * @param disruptionMatched   the stops DetourTO closed equal the expected closed stops
 * @param groundingViolations LLM answers rejected by the validator during this scenario
 * @param error               why the scenario failed, or null
 */
public record BenchmarkReport(String scenario, boolean feasible, int arrivalDelayMinutes, boolean disruptionMatched,
                              int groundingViolations, int llmCalls, long latencyMs, boolean usedLlm, String error) {

    public boolean passed() {
        return error == null && disruptionMatched;
    }
}
