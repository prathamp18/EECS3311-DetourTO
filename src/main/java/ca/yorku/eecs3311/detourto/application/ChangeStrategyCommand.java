package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TripQuery;

/** Switches how options are ranked (F03). */
public final class ChangeStrategyCommand implements QueryCommand {

    private final TripQuery query;
    private final StrategyKind newKind;
    private StrategyKind oldKind;

    public ChangeStrategyCommand(TripQuery query, StrategyKind newKind) {
        this.query = query;
        this.newKind = newKind;
    }

    @Override
    public void execute() {
        oldKind = query.strategyKind();
        query.setStrategyKind(newKind);
    }

    @Override
    public void undo() {
        query.setStrategyKind(oldKind);
    }

    @Override
    public String describe() {
        return "Rank by " + newKind.cliName();
    }
}
