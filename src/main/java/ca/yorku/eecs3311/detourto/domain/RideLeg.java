package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;

/** Riding one trip from a boarding stop to an alighting stop. */
public final class RideLeg extends Leg {

    private final Trip trip;
    private final int boardPos;
    private final int alightPos;

    public RideLeg(Trip trip, int boardPos, int alightPos, Stop from, Stop to,
                   LocalDateTime start, LocalDateTime end) {
        super(from, to, start, end);
        if (alightPos <= boardPos) {
            throw new IllegalArgumentException("alight position must be after board position");
        }
        this.trip = trip;
        this.boardPos = boardPos;
        this.alightPos = alightPos;
    }

    @Override
    public TransitMode mode() {
        return trip.route().mode();
    }

    public Trip trip() {
        return trip;
    }

    public Route route() {
        return trip.route();
    }

    /** Position of the boarding stop within the trip (0-based). */
    public int boardPos() {
        return boardPos;
    }

    public int alightPos() {
        return alightPos;
    }

    /** Number of stops ridden through (not counting the boarding stop). */
    public int stopCount() {
        return alightPos - boardPos;
    }

    @Override
    public String toString() {
        return route().label() + " " + from.name() + " -> " + to.name();
    }
}
