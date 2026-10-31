package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * A structured reading of one service alert: what is closed and how sure we are.
 *
 * @param confidence 1.0 for alerts converted from structured fields; the model's own estimate
 *                   (validated) for interpreted free-text alerts; 0 when interpretation failed
 * @param llmDerived true when the closure came from the AlertInterpreterAgent
 * @param summary    one plain-language sentence for the rider
 */
public record Disruption(String id, String sourceAlertId, DisruptionKind kind, Set<String> affectedRouteIds,
                         Set<String> closedStopIds, LocalDateTime start, LocalDateTime end, double confidence,
                         boolean llmDerived, String summary) {

    /** Disruptions below this confidence are listed but not used for routing unless the rider asks. */
    public static final double APPLY_THRESHOLD = 0.5;

    public Disruption {
        affectedRouteIds = Set.copyOf(affectedRouteIds);
        closedStopIds = Set.copyOf(closedStopIds);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    /** True when this disruption changes which stops or routes can be used. */
    public boolean affectsRouting() {
        return switch (kind) {
            case CLOSURE -> !closedStopIds.isEmpty() || !affectedRouteIds.isEmpty();
            case DETOUR -> !closedStopIds.isEmpty();
            case ELEVATOR_OUTAGE -> !closedStopIds.isEmpty();
            case DELAY, UNKNOWN -> false;
        };
    }

    public boolean appliesByDefault() {
        return affectsRouting() && confidence >= APPLY_THRESHOLD;
    }

    public List<String> sortedStopIds() {
        return closedStopIds.stream().sorted().toList();
    }
}
