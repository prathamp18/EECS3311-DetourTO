package ca.yorku.eecs3311.detourto.domain;

/**
 * A walking connection between two stops, either from GTFS transfers.txt or generated for stops
 * that are close together (e.g. the Line 1 and Line 2 platforms at St George).
 */
public record Transfer(Stop from, Stop to, int minSeconds) {
}
