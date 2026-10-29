package ca.yorku.eecs3311.detourto.agent;

import java.util.List;

/** Result of checking an LLM output against the real network: valid, or a list of reasons. */
public record ValidationReport(boolean valid, List<String> errors) {

    public ValidationReport {
        errors = List.copyOf(errors);
    }

    public static ValidationReport ok() {
        return new ValidationReport(true, List.of());
    }

    public static ValidationReport of(List<String> errors) {
        return new ValidationReport(errors.isEmpty(), errors);
    }
}
