package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.LocalDateTime;

/** Changes the trip time: shift by minutes, or set a new depart-at / arrive-by time. */
public final class ChangeDepartureCommand implements QueryCommand {

    private final TripQuery query;
    private final LocalDateTime newTime;
    private final boolean newArriveBy;
    private final String description;
    private LocalDateTime oldTime;
    private boolean oldArriveBy;

    public ChangeDepartureCommand(TripQuery query, LocalDateTime newTime, boolean newArriveBy, String description) {
        this.query = query;
        this.newTime = newTime;
        this.newArriveBy = newArriveBy;
        this.description = description;
    }

    @Override
    public void execute() {
        oldTime = query.time();
        oldArriveBy = query.isArriveBy();
        set(newTime, newArriveBy);
    }

    @Override
    public void undo() {
        set(oldTime, oldArriveBy);
    }

    private void set(LocalDateTime t, boolean arriveBy) {
        if (arriveBy) {
            query.setArriveBy(t);
        } else {
            query.setDepartAt(t);
        }
    }

    @Override
    public String describe() {
        return description;
    }
}
