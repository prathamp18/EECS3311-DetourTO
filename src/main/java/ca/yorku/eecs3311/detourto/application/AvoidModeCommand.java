package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TripQuery;

/** Stops using one mode ("no streetcars"). Undo only re-allows it if it was allowed before. */
public final class AvoidModeCommand implements QueryCommand {

    private final TripQuery query;
    private final TransitMode mode;
    private boolean wasAvoided;

    public AvoidModeCommand(TripQuery query, TransitMode mode) {
        this.query = query;
        this.mode = mode;
    }

    @Override
    public void execute() {
        wasAvoided = query.avoidModes().contains(mode);
        query.avoidMode(mode);
    }

    @Override
    public void undo() {
        if (!wasAvoided) {
            query.allowMode(mode);
        }
    }

    @Override
    public String describe() {
        return "No " + mode.name().toLowerCase() + (mode == TransitMode.BUS ? "es" : "s");
    }
}
