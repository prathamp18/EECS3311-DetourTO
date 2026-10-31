package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Every closed stop must exist and come from a tool result; every route must exist; a closure
     * or detour must name at least one stop or route; and closed stops must be served by one of
     * the affected routes when routes are given (an alert about the 510 cannot close a Line 2 stop).
     */
    public ValidationReport validateDisruption(Disruption d, AgentContext ctx) {
        List<String> errors = new ArrayList<>();
        for (String id : d.closedStopIds()) {
            Optional<Stop> s = network.findStop(id);
            if (s.isEmpty()) {
                errors.add("stop " + id + " does not exist");
            } else if (!ctx.stopWasReturnedByTool(id)) {
                errors.add("stop " + id + " was not returned by find_stop or get_route_stops");
            }
        }
        for (String r : d.affectedRouteIds()) {
            if (network.findRoute(r).isEmpty()) {
                errors.add("route " + r + " does not exist");
            }
        }
        if ((d.kind() == DisruptionKind.CLOSURE || d.kind() == DisruptionKind.DETOUR)
                && d.closedStopIds().isEmpty() && d.affectedRouteIds().isEmpty()) {
            errors.add(d.kind() + " must name at least one stop or route");
        }
        if (errors.isEmpty() && !d.affectedRouteIds().isEmpty()) {
            Set<String> served = new HashSet<>();
            for (String r : d.affectedRouteIds()) {
                for (Stop s : network.stopsOfRoute(r)) {
                    served.add(s.id());
                    if (s.parentStation() != null) {
                        served.add(s.parentStation());
                    }
                }
            }
            for (String id : d.closedStopIds()) {
                if (!served.contains(id)) {
                    errors.add("stop " + id + " is not served by route(s) " + d.affectedRouteIds());
                }
            }
        }
        return ValidationReport.of(errors);
    }

    private static final java.util.regex.Pattern OPTION_ID = java.util.regex.Pattern.compile("\\bopt-\\d+\\b");
    private static final java.util.regex.Pattern MINUTES =
            java.util.regex.Pattern.compile("(\\d+)\\s*-?\\s*(?:min|mins|minute|minutes)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile("\\bLine\\s+(\\d+)\\b");
    private static final java.util.regex.Pattern NUMBERED_ROUTE =
            java.util.regex.Pattern.compile("\\b(?:route|bus|streetcar)\\s+(\\d{1,3})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * An explanation may only cite options that exist, minute values that come from the options
     * (or differences between them), and lines that the options use.
     */
    public ValidationReport validateExplanation(Explanation e, List<Itinerary> options, TripQuery q) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> routeNumbers = new HashSet<>();
        for (Itinerary it : options) {
            ids.add(it.id());
            for (RideLeg r : it.rideLegs()) {
                routeNumbers.add(r.route().shortName());
            }
        }
        String text = e.summary() + " " + String.join(" ", e.optionNotes().values());
        Set<String> mentioned = new java.util.LinkedHashSet<>(e.citedItineraryIds());
        mentioned.addAll(e.optionNotes().keySet());
        java.util.regex.Matcher m = OPTION_ID.matcher(text);
        while (m.find()) {
            mentioned.add(m.group());
        }
        for (String id : mentioned) {
            if (!ids.contains(id)) {
                errors.add("option " + id + " does not exist (options are " + ids.stream().sorted().toList() + ")");
            }
        }
        Set<Integer> allowed = allowedMinutes(options, q);
        List<Integer> cited = new ArrayList<>(e.citedMinutes());
        m = MINUTES.matcher(text);
        while (m.find()) {
            cited.add(Integer.parseInt(m.group(1)));
        }
        for (int minutes : cited) {
            if (!allowed.contains(minutes)) {
                errors.add(minutes + " min does not match any time in the options");
            }
        }
        for (java.util.regex.Pattern p : List.of(LINE, NUMBERED_ROUTE)) {
            m = p.matcher(text);
            while (m.find()) {
                if (!routeNumbers.contains(m.group(1))) {
                    errors.add("route " + m.group(1) + " is not used by any option");
                }
            }
        }
        return ValidationReport.of(errors);
    }

    /** Minute values that may be cited, allowing 1 minute of rounding either way. */
    static Set<Integer> allowedMinutes(List<Itinerary> options, TripQuery q) {
        Set<Integer> base = new HashSet<>();
        List<Integer> durations = new ArrayList<>();
        List<Long> arrivals = new ArrayList<>();
        List<Long> departures = new ArrayList<>();
        for (Itinerary it : options) {
            base.add(it.durationMinutes());
            base.add(it.transfers());
            base.add((int) Math.round(it.walkSeconds() / 60.0));
            durations.add(it.durationMinutes());
            arrivals.add(it.arrival().toEpochSecond(java.time.ZoneOffset.UTC) / 60);
            departures.add(it.departure().toEpochSecond(java.time.ZoneOffset.UTC) / 60);
            Leg prev = null;
            for (Leg leg : it.legs()) {
                base.add(leg.minutes());
                if (prev != null) {
                    base.add((int) java.time.Duration.between(prev.end(), leg.start()).toMinutes());
                }
                prev = leg;
            }
            if (q != null) {
                base.add((int) Math.abs(java.time.Duration.between(q.time(), it.departure()).toMinutes()));
                base.add((int) Math.abs(java.time.Duration.between(q.time(), it.arrival()).toMinutes()));
            }
        }
        for (int i = 0; i < options.size(); i++) {
            for (int j = 0; j < options.size(); j++) {
                base.add(Math.abs(durations.get(i) - durations.get(j)));
                base.add((int) Math.abs(arrivals.get(i) - arrivals.get(j)));
                base.add((int) Math.abs(departures.get(i) - departures.get(j)));
            }
        }
        Set<Integer> out = new HashSet<>();
        for (int v : base) {
            out.add(v - 1);
            out.add(v);
            out.add(v + 1);
        }
        return out;
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
