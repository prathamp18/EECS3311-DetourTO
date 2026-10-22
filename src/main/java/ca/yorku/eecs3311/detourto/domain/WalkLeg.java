package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;

/** Walking between two stops (a transfer, or to/from the first or last stop). */
public final class WalkLeg extends Leg {

    private final int meters;

    public WalkLeg(Stop from, Stop to, LocalDateTime start, LocalDateTime end, int meters) {
        super(from, to, start, end);
        this.meters = meters;
    }

    @Override
    public TransitMode mode() {
        return TransitMode.WALK;
    }

    public int meters() {
        return meters;
    }

    @Override
    public String toString() {
        return "Walk " + meters + " m " + from.name() + " -> " + to.name();
    }
}
