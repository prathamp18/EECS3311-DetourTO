package ca.yorku.eecs3311.detourto.domain;

/**
 * A GTFS stop: a subway platform, a station (parent of its platforms), or a street stop.
 *
 * @param id                 GTFS stop_id
 * @param code               stop_code shown on TTC signs (may be empty)
 * @param name               stop_name
 * @param lat                latitude
 * @param lon                longitude
 * @param wheelchairBoarding true when GTFS wheelchair_boarding = 1 (step-free access)
 * @param parentStation      stop_id of the parent station, or null
 * @param station            true when location_type = 1 (a station grouping platforms)
 */
public record Stop(String id, String code, String name, double lat, double lon,
                   boolean wheelchairBoarding, String parentStation, boolean station) {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /** Great-circle distance in metres (haversine). */
    public double distanceMetersTo(double otherLat, double otherLon) {
        double dLat = Math.toRadians(otherLat - lat);
        double dLon = Math.toRadians(otherLon - lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(otherLat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    public double distanceMetersTo(Stop other) {
        return distanceMetersTo(other.lat, other.lon);
    }
}
