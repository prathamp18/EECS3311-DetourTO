package ca.yorku.eecs3311.detourto.domain;

/** Each vehicle change costs as much as {@code transferPenaltyMin} extra minutes. */
public final class FewestTransfersStrategy implements RouteScoringStrategy {

    private final int transferPenaltyMin;

    public FewestTransfersStrategy() {
        this(10);
    }

    public FewestTransfersStrategy(int transferPenaltyMin) {
        this.transferPenaltyMin = transferPenaltyMin;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.FEWEST_TRANSFERS;
    }

    @Override
    public String label() {
        return "fewest transfers";
    }

    @Override
    public double score(Itinerary i, TripQuery q) {
        return RouteScoringStrategy.riderMinutes(i, q) + transferPenaltyMin * i.transfers();
    }
}
