package ca.yorku.eecs3311.detourto.domain;

/**
 * A GTFS route, e.g. "1 Line 1 Yonge-University" or "510 Spadina".
 */
public record Route(String id, String shortName, String longName, TransitMode mode) {

    /**
     * Display name: "510 Spadina", or just the long name when it already contains the number
     * ("Line 1 Yonge-University" rather than "1 Line 1 Yonge-University").
     */
    public String label() {
        if (shortName == null || shortName.isBlank()) {
            return longName;
        }
        if (longName == null || longName.isBlank()) {
            return shortName;
        }
        if (longName.matches("(?i).*\\b" + java.util.regex.Pattern.quote(shortName) + "\\b.*")) {
            return longName;
        }
        return shortName + " " + longName;
    }
}
