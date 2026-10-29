package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The LLM proposes, this class verifies. Every stop in an agent's answer must exist in the
 * network and must have come from a tool result in the same run (or from the rider's current
 * query), so a made-up or remembered stop id is rejected.
 */
public final class GroundingValidator {

    static final Duration MAX_PAST = Duration.ofHours(1);
    static final Duration MAX_FUTURE = Duration.ofDays(30);

    private final TransitNetwork network;

    public GroundingValidator(TransitNetwork network) {
        this.network = network;
    }

    public ValidationReport validateQuery(TripQuery q, AgentContext ctx) {
        List<String> errors = new ArrayList<>();
        checkStop(q.origin(), "origin", ctx, errors);
        checkStop(q.destination(), "destination", ctx, errors);
        if (q.origin().equals(q.destination())) {
            errors.add("origin and destination are the same stop");
        }
        if (q.time().isBefore(ctx.now().minus(MAX_PAST))) {
            errors.add("time " + q.time() + " is in the past (now is " + ctx.now() + ")");
        }
        if (q.time().isAfter(ctx.now().plus(MAX_FUTURE))) {
            errors.add("time " + q.time() + " is more than 30 days ahead");
        }
        for (String r : q.avoidRouteIds()) {
            if (network.findRoute(r).isEmpty()) {
                errors.add("avoid_route_ids contains unknown route '" + r + "'");
            }
        }
        return ValidationReport.of(errors);
    }

    public ValidationReport validateClarification(ClarificationRequest c, AgentContext ctx) {
        List<String> errors = new ArrayList<>();
        for (Stop s : c.candidates()) {
            checkStop(s, "candidate", ctx, errors);
        }
        return ValidationReport.of(errors);
    }

    private void checkStop(Stop s, String role, AgentContext ctx, List<String> errors) {
        if (network.findStop(s.id()).isEmpty()) {
            errors.add(role + " stop " + s.id() + " does not exist");
            return;
        }
        boolean fromCurrentQuery = ctx.query()
                .map(q -> q.origin().id().equals(s.id()) || q.destination().id().equals(s.id()))
                .orElse(false);
        if (!ctx.stopWasReturnedByTool(s.id()) && !fromCurrentQuery) {
            errors.add(role + " stop " + s.id() + " was not returned by find_stop in this conversation");
        }
    }
}
