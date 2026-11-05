package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.FollowUpIntent;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** Factory: maps a follow-up intent to the command that applies it to the query. */
public final class QueryCommandFactory {

    private final TransitNetwork network;
    private final PlaceResolver places;

    public QueryCommandFactory(TransitNetwork network, PlaceResolver places) {
        this.network = network;
        this.places = places;
    }

    /**
     * @throws IllegalArgumentException with a rider-readable message when the intent cannot be
     *                                  applied (unknown route, place not found, ...)
     */
    public QueryCommand create(FollowUpIntent intent, TripQuery q) {
        String v = intent.value();
        return switch (intent.kind()) {
            case CHANGE_TIME -> time(v, q);
            case AVOID_ROUTE -> new AvoidRouteCommand(q, v, network.findRoute(v)
                    .orElseThrow(() -> new IllegalArgumentException("There is no route " + v + ".")).label());
            case AVOID_MODE -> new AvoidModeCommand(q, TransitMode.valueOf(v));
            case CHANGE_STRATEGY -> new ChangeStrategyCommand(q, StrategyKind.parse(v));
            case CHANGE_DESTINATION -> new ChangeDestinationCommand(q, destination(v));
            case UNDO, REDO, UNKNOWN -> throw new IllegalArgumentException("not a change to the trip: " + intent.kind());
        };
    }

    private QueryCommand time(String v, TripQuery q) {
        if (v.startsWith("+") || v.startsWith("-")) {
            int minutes = Integer.parseInt(v);
            String what = q.isArriveBy() ? "Arrive " : "Leave ";
            return new ChangeDepartureCommand(q, q.time().plusMinutes(minutes), q.isArriveBy(),
                    what + Math.abs(minutes) + " min " + (minutes >= 0 ? "later" : "earlier"));
        }
        boolean arrive = v.startsWith("arrive@");
        LocalTime clock = LocalTime.parse(v.substring(v.indexOf('@') + 1));
        LocalDateTime t = q.time().toLocalDate().atTime(clock);
        return new ChangeDepartureCommand(q, t, arrive, (arrive ? "Arrive by " : "Leave at ") + clock);
    }

    private Stop destination(String v) {
        Optional<Stop> exact = network.findStop(v);
        if (exact.isPresent()) {
            return exact.get();
        }
        List<PlaceResolver.Match> matches = places.match(v);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("I couldn't find a stop called \"" + v + "\".");
        }
        if (places.isAmbiguous(matches)) {
            throw new IllegalArgumentException("\"" + v + "\" matches several stops: "
                    + String.join(", ", matches.stream().limit(3).map(m -> m.stop().name()).toList()) + ".");
        }
        return matches.get(0).stop();
    }
}
