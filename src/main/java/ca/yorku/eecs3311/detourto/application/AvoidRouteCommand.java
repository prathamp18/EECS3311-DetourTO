package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.TripQuery;

/** Stops using one route. Undo only re-allows it if it was allowed before. */
public final class AvoidRouteCommand implements QueryCommand {

    private final TripQuery query;
    private final String routeId;
    private final String routeLabel;
    private boolean wasAvoided;

    public AvoidRouteCommand(TripQuery query, String routeId, String routeLabel) {
        this.query = query;
        this.routeId = routeId;
        this.routeLabel = routeLabel;
    }

    @Override
    public void execute() {
        wasAvoided = query.avoidRouteIds().contains(routeId);
        query.avoidRoute(routeId);
    }

    @Override
    public void undo() {
        if (!wasAvoided) {
            query.allowRoute(routeId);
        }
    }

    @Override
    public String describe() {
        return "Avoid " + routeLabel;
    }
}
