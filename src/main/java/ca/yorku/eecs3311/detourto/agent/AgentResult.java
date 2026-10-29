package ca.yorku.eecs3311.detourto.agent;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of an agent run. {@code output} is the validated object (a TripQuery, a
 * ClarificationRequest, ...). Counts are reported in the trace and in replay benchmarks.
 */
public record AgentResult(boolean success, Object output, List<String> errors, int toolCallCount,
                          int llmCallCount) {

    public AgentResult {
        errors = List.copyOf(errors);
    }

    public <T> Optional<T> outputAs(Class<T> type) {
        return type.isInstance(output) ? Optional.of(type.cast(output)) : Optional.empty();
    }
}
