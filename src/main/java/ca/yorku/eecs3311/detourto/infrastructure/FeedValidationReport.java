package ca.yorku.eecs3311.detourto.infrastructure;

import java.util.List;

/**
 * Result of checking a loaded GTFS feed: basic counts plus problems found while loading
 * (unknown stop ids, trips without stop times, and so on). {@code ok()} is false only for
 * problems that make planning impossible.
 */
public record FeedValidationReport(int stopCount, int routeCount, int tripCount, int stopTimeCount,
                                   int serviceCount, List<String> warnings, List<String> errors) {

    public FeedValidationReport {
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    /** Human-readable summary for the CLI. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("stops=%d routes=%d trips=%d stop_times=%d services=%d%n",
                stopCount, routeCount, tripCount, stopTimeCount, serviceCount));
        sb.append(ok() ? "status: OK" : "status: ERRORS").append(System.lineSeparator());
        errors.forEach(e -> sb.append("error: ").append(e).append(System.lineSeparator()));
        int shown = Math.min(warnings.size(), 20);
        for (int i = 0; i < shown; i++) {
            sb.append("warning: ").append(warnings.get(i)).append(System.lineSeparator());
        }
        if (warnings.size() > shown) {
            sb.append("... and ").append(warnings.size() - shown).append(" more warnings")
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }
}
