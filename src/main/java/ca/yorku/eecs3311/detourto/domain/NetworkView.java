package ca.yorku.eecs3311.detourto.domain;

import java.util.Map;
import java.util.Set;

/**
 * The transit network as the planner should see it right now: the base network with some stops
 * and routes closed (disruptions) and some stations without working elevators. Nothing is copied;
 * the view just answers "can this be used?".
 */
public final class NetworkView {

    private final TransitNetwork base;
    private final Set<String> closedStopIds;
    private final Set<String> closedRouteIds;
    private final Set<String> outageStopIds;
    /** route id -> stops its vehicles cannot run through (a closed section of the line). */
    private final Map<String, Set<String>> closedSections;

    NetworkView(TransitNetwork base, Set<String> closedStopIds, Set<String> closedRouteIds,
                Set<String> outageStopIds) {
        this(base, closedStopIds, closedRouteIds, outageStopIds, Map.of());
    }

    NetworkView(TransitNetwork base, Set<String> closedStopIds, Set<String> closedRouteIds,
                Set<String> outageStopIds, Map<String, Set<String>> closedSections) {
        this.base = base;
        this.closedStopIds = Set.copyOf(closedStopIds);
        this.closedRouteIds = Set.copyOf(closedRouteIds);
        this.outageStopIds = Set.copyOf(outageStopIds);
        this.closedSections = Map.copyOf(closedSections);
    }

    public TransitNetwork base() {
        return base;
    }

    /** A stop is closed if it, or the station it belongs to, is closed. */
    public boolean isStopClosed(Stop s) {
        return closedStopIds.contains(s.id())
                || (s.parentStation() != null && closedStopIds.contains(s.parentStation()));
    }

    public boolean isStopClosed(int stopIndex) {
        return isStopClosed(base.stopAt(stopIndex));
    }

    public boolean isRouteClosed(Route r) {
        return closedRouteIds.contains(r.id());
    }

    /**
     * True when vehicles of {@code r} cannot run through this stop because that section of the
     * line is closed. A plain station closure (no route) still lets vehicles pass through.
     */
    public boolean isSectionClosed(Route r, Stop s) {
        Set<String> section = closedSections.get(r.id());
        return section != null && (section.contains(s.id())
                || (s.parentStation() != null && section.contains(s.parentStation())));
    }

    public boolean isSectionClosed(Route r, int stopIndex) {
        return !closedSections.isEmpty() && isSectionClosed(r, base.stopAt(stopIndex));
    }

    /** True if the trip may be ridden from {@code from} to {@code to}. */
    public boolean isUsable(Trip t, Stop from, Stop to) {
        return !isRouteClosed(t.route()) && !isStopClosed(from) && !isStopClosed(to);
    }

    /** Step-free = GTFS says wheelchair accessible and no elevator outage is reported there. */
    public boolean isStepFree(Stop s) {
        if (!s.wheelchairBoarding()) {
            return false;
        }
        return !outageStopIds.contains(s.id())
                && (s.parentStation() == null || !outageStopIds.contains(s.parentStation()));
    }

    public Set<String> closedStopIds() {
        return closedStopIds;
    }

    public Set<String> closedRouteIds() {
        return closedRouteIds;
    }

    public Set<String> outageStopIds() {
        return outageStopIds;
    }
}
