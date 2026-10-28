package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.ClarificationRequest;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.util.List;

/**
 * What the facade returns for every planning call: the query that was planned, the options, a
 * clarification question when a place was ambiguous, and a short message for the rider.
 *
 * @param query         the planned query, or null when places could not be resolved
 * @param options       ranked itineraries (may be empty)
 * @param clarification non-null when the rider must pick a stop first
 * @param message       one-line status for the CLI and GUI
 */
public record PlanResult(TripQuery query, List<Itinerary> options,
                         ClarificationRequest clarification, String message) {

    public PlanResult {
        options = List.copyOf(options);
    }

    public static PlanResult ok(TripQuery q, List<Itinerary> options, String message) {
        return new PlanResult(q, options, null, message);
    }

    public static PlanResult needsClarification(ClarificationRequest c) {
        return new PlanResult(null, List.of(), c, c.question());
    }

    public boolean hasOptions() {
        return !options.isEmpty();
    }

    public boolean needsClarification() {
        return clarification != null;
    }
}
