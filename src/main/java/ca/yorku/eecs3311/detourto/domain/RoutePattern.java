package ca.yorku.eecs3311.detourto.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Trips of one route that visit exactly the same stops in the same order, sorted by departure
 * time at the first stop. RAPTOR scans patterns, not GTFS routes, because one GTFS route usually
 * has several patterns (two directions, short turns, branches).
 */
public final class RoutePattern {

    private final int index;
    private final Route route;
    private final int[] stops;
    private final List<Trip> trips;

    RoutePattern(int index, Route route, int[] stops, List<Trip> trips) {
        this.index = index;
        this.route = route;
        this.stops = stops;
        this.trips = trips.stream().sorted(Comparator.comparingInt(t -> t.departureAt(0))).toList();
    }

    public int index() {
        return index;
    }

    public Route route() {
        return route;
    }

    public int stopCount() {
        return stops.length;
    }

    public int stopAt(int pos) {
        return stops[pos];
    }

    public List<Trip> trips() {
        return trips;
    }
}
