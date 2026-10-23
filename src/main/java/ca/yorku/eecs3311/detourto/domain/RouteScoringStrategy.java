package ca.yorku.eecs3311.detourto.domain;

import java.time.Duration;

/**
 * Strategy pattern (feature F03): how itineraries are filtered and ranked. {@link JourneyPlanner}
 * is the context; it runs the same RAPTOR search for every strategy and asks the strategy which
 * stops may be used and how good each finished itinerary is.
 *
 * <p>{@link #canBoard} and {@link #canAlight} are checked inside the search so that, for example,
 * the accessible strategy never even considers a station with stairs only. {@link #admissible}
 * is the final check on each leg of a finished itinerary.
 */
public interface RouteScoringStrategy {

    StrategyKind kind();

    /** Short name for the CLI and GUI, e.g. "fewest transfers". */
    String label();

    /** May a rider using this strategy get on {@code route} at {@code stop}? */
    default boolean canBoard(Stop stop, Route route, NetworkView view) {
        return true;
    }

    /** May a rider using this strategy get off at {@code stop}? */
    default boolean canAlight(Stop stop, NetworkView view) {
        return true;
    }

    /** Final check on one leg of a computed itinerary. */
    default boolean admissible(Leg leg, TripQuery q, NetworkView view) {
        if (leg instanceof RideLeg ride) {
            return canBoard(ride.from(), ride.route(), view) && canAlight(ride.to(), view);
        }
        return true;
    }

    /** Lower is better. */
    double score(Itinerary i, TripQuery q);

    /**
     * Minutes the trip takes from the rider's point of view, including waiting: from the requested
     * departure time to arrival, or (arrive-by) from leaving to the requested arrival time.
     */
    static double riderMinutes(Itinerary i, TripQuery q) {
        Duration d = q.isArriveBy()
                ? Duration.between(i.departure(), q.arriveBy())
                : Duration.between(q.departAt(), i.arrival());
        return d.getSeconds() / 60.0;
    }

    /** One instance per kind (simple factory used by the controller and the CLI). */
    static RouteScoringStrategy of(StrategyKind kind) {
        return switch (kind) {
            case FASTEST -> new FastestStrategy();
            case FEWEST_TRANSFERS -> new FewestTransfersStrategy();
            case LEAST_WALKING -> new LeastWalkingStrategy();
            case ACCESSIBLE -> new AccessibleStrategy();
        };
    }
}
