package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TripQuery;

/** Goes somewhere else ("what about Union instead?"). */
public final class ChangeDestinationCommand implements QueryCommand {

    private final TripQuery query;
    private final Stop newStop;
    private Stop oldStop;

    public ChangeDestinationCommand(TripQuery query, Stop newStop) {
        this.query = query;
        this.newStop = newStop;
    }

    @Override
    public void execute() {
        oldStop = query.destination();
        query.setDestination(newStop);
    }

    @Override
    public void undo() {
        query.setDestination(oldStop);
    }

    @Override
    public String describe() {
        return "Go to " + newStop.name();
    }
}
