package ca.yorku.eecs3311.detourto.domain;

/**
 * A GTFS route, e.g. "1 Line 1 Yonge-University" or "510 Spadina".
 */
public record Route(String id, String shortName, String longName, TransitMode mode) {

    /** Short label for display, e.g. "Line 1" style names fall back to the long name. */
    public String label() {
        if (shortName == null || shortName.isBlank()) {
            return longName;
        }
        return shortName + " " + longName;
    }
}
