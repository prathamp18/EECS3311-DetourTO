package ca.yorku.eecs3311.detourto.domain;

/** Earliest arrival (depart-at) or latest departure (arrive-by). */
public final class FastestStrategy implements RouteScoringStrategy {

    @Override
    public StrategyKind kind() {
        return StrategyKind.FASTEST;
    }

    @Override
    public String label() {
        return "fastest";
    }

    @Override
    public double score(Itinerary i, TripQuery q) {
        return RouteScoringStrategy.riderMinutes(i, q);
    }
}
