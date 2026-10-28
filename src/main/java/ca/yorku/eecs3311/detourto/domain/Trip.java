package ca.yorku.eecs3311.detourto.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One scheduled run of a vehicle along a route.
 *
 * <p>The full TTC feed has millions of stop times, so a trip stores its calls as three parallel
 * int arrays (stop index into {@link TransitNetwork}, arrival, departure) instead of millions of
 * small objects. {@link #stopTimes(TransitNetwork)} gives the readable view.
 */
public final class Trip {

    private final String id;
    private final Route route;
    private final String serviceId;
    private final String headsign;
    private final int[] stopIndex;
    private final int[] arrival;
    private final int[] departure;

    public Trip(String id, Route route, String serviceId, String headsign,
                int[] stopIndex, int[] arrival, int[] departure) {
        if (stopIndex.length != arrival.length || arrival.length != departure.length) {
            throw new IllegalArgumentException("stop time arrays differ in length for trip " + id);
        }
        this.id = id;
        this.route = route;
        this.serviceId = serviceId;
        this.headsign = headsign;
        this.stopIndex = stopIndex;
        this.arrival = arrival;
        this.departure = departure;
    }

    public String id() {
        return id;
    }

    public Route route() {
        return route;
    }

    public String serviceId() {
        return serviceId;
    }

    public String headsign() {
        return headsign;
    }

    public int size() {
        return stopIndex.length;
    }

    public int stopIndexAt(int pos) {
        return stopIndex[pos];
    }

    public int arrivalAt(int pos) {
        return arrival[pos];
    }

    public int departureAt(int pos) {
        return departure[pos];
    }

    /** The ordered stop indices; trips with the same sequence share a {@link RoutePattern}. */
    public int[] stopSequence() {
        return stopIndex.clone();
    }

    /** Readable stop times (allocates; not for use in the planner's inner loop). */
    public List<StopTime> stopTimes(TransitNetwork network) {
        List<StopTime> out = new ArrayList<>(stopIndex.length);
        for (int i = 0; i < stopIndex.length; i++) {
            out.add(new StopTime(network.stopAt(stopIndex[i]), arrival[i], departure[i], i + 1));
        }
        return out;
    }

    boolean sameSequenceAs(int[] other) {
        return Arrays.equals(stopIndex, other);
    }

    @Override
    public String toString() {
        return "Trip[" + id + " " + route.shortName() + " to " + headsign + "]";
    }
}
