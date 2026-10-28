package ca.yorku.eecs3311.detourto.domain;

/**
 * One scheduled call of a trip at a stop. Times are seconds after midnight of the service day,
 * so values above 24:00:00 (trips running past midnight) are allowed, as in GTFS.
 */
public record StopTime(Stop stop, int arrivalSec, int departureSec, int sequence) {

    /** Formats seconds after midnight as HH:MM (hours may exceed 23). */
    public static String hhmm(int sec) {
        return String.format("%02d:%02d", sec / 3600, (sec % 3600) / 60);
    }
}
