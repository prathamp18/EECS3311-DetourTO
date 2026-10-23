package ca.yorku.eecs3311.detourto.domain;

/** A minute of walking counts as {@code walkWeight} minutes of riding or waiting. */
public final class LeastWalkingStrategy implements RouteScoringStrategy {

    private final double walkWeight;

    public LeastWalkingStrategy() {
        this(3.0);
    }

    public LeastWalkingStrategy(double walkWeight) {
        this.walkWeight = walkWeight;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.LEAST_WALKING;
    }

    @Override
    public String label() {
        return "least walking";
    }

    @Override
    public double score(Itinerary i, TripQuery q) {
        // riderMinutes already includes the walk once, so add the extra weight only
        return RouteScoringStrategy.riderMinutes(i, q) + (walkWeight - 1) * i.walkSeconds() / 60.0;
    }
}
