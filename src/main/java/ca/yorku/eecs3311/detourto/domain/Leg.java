package ca.yorku.eecs3311.detourto.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/** One part of an itinerary: riding a vehicle or walking between stops. */
public abstract class Leg {

    protected final Stop from;
    protected final Stop to;
    protected final LocalDateTime start;
    protected final LocalDateTime end;

    protected Leg(Stop from, Stop to, LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("leg ends before it starts: " + from.id() + " -> " + to.id());
        }
        this.from = from;
        this.to = to;
        this.start = start;
        this.end = end;
    }

    public abstract TransitMode mode();

    public Stop from() {
        return from;
    }

    public Stop to() {
        return to;
    }

    public LocalDateTime start() {
        return start;
    }

    public LocalDateTime end() {
        return end;
    }

    public long seconds() {
        return Duration.between(start, end).getSeconds();
    }

    /** Length in whole minutes, rounded to the nearest minute. */
    public int minutes() {
        return (int) Math.round(seconds() / 60.0);
    }
}
