package ca.yorku.eecs3311.detourto.application;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Result of checking an active trip.
 *
 * @param key  what the risk is about (disruption ids, "late", "missed connection"), so the same
 *             risk is only reported once
 */
public record TripRisk(boolean atRisk, String reason, LocalDateTime projectedArrival, Set<String> key) {

    public static TripRisk none(LocalDateTime projected) {
        return new TripRisk(false, "", projected, Set.of());
    }
}
