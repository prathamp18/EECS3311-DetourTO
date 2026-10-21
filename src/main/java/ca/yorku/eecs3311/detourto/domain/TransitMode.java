package ca.yorku.eecs3311.detourto.domain;

/** How a leg is travelled. GTFS route_type values are mapped onto the modes the TTC runs. */
public enum TransitMode {
    SUBWAY,
    STREETCAR,
    BUS,
    WALK;

    /** Maps a GTFS {@code route_type} (0 tram, 1 subway, 2 rail, 3 bus, ...) to a mode. */
    public static TransitMode fromGtfsRouteType(int routeType) {
        return switch (routeType) {
            case 0 -> STREETCAR;
            case 1, 2 -> SUBWAY;
            default -> BUS;
        };
    }
}
