package ca.yorku.eecs3311.detourto.domain;

/**
 * Step-free trips only: every boarding and alighting stop must have wheelchair_boarding = 1 and
 * no current elevator outage (at the stop or its station). Ranked like {@link FastestStrategy}.
 */
public final class AccessibleStrategy implements RouteScoringStrategy {

    @Override
    public StrategyKind kind() {
        return StrategyKind.ACCESSIBLE;
    }

    @Override
    public String label() {
        return "accessible (step-free)";
    }

    @Override
    public boolean canBoard(Stop stop, Route route, NetworkView view) {
        return view.isStepFree(stop);
    }

    @Override
    public boolean canAlight(Stop stop, NetworkView view) {
        return view.isStepFree(stop);
    }

    @Override
    public double score(Itinerary i, TripQuery q) {
        return RouteScoringStrategy.riderMinutes(i, q);
    }
}
