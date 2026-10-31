package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.ClarificationRequest;
import ca.yorku.eecs3311.detourto.domain.Disruption;
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
 * @param disruptionsApplied disruptions the planner routed around
 */
public record PlanResult(TripQuery query, List<Itinerary> options,
                         ClarificationRequest clarification, String message,
                         List<Disruption> disruptionsApplied) {

    public PlanResult {
        options = List.copyOf(options);
        disruptionsApplied = disruptionsApplied == null ? List.of() : List.copyOf(disruptionsApplied);
    }

    public PlanResult(TripQuery query, List<Itinerary> options, ClarificationRequest clarification, String message) {
        this(query, options, clarification, message, List.of());
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
