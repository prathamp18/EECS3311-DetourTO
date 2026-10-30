package ca.yorku.eecs3311.detourto.domain;

import java.time.Instant;
import java.util.List;

/**
 * A TTC service alert as published in GTFS-Realtime. {@code effect} and {@code cause} are the
 * GTFS-RT enum names (e.g. NO_SERVICE, DETOUR, ACCESSIBILITY_ISSUE); the text is often the only
 * place that says what actually happens, which is why alerts are interpreted (feature F04).
 *
 * @param activeTo end of the active period, or null when open-ended
 */
public record ServiceAlert(String id, String header, String description, List<String> informedRouteIds,
                           List<String> informedStopIds, Instant activeFrom, Instant activeTo,
                           String cause, String effect) {

    public ServiceAlert {
        informedRouteIds = List.copyOf(informedRouteIds);
        informedStopIds = List.copyOf(informedStopIds);
        header = header == null ? "" : header;
        description = description == null ? "" : description;
    }

    public boolean isActiveAt(Instant t) {
        return (activeFrom == null || !t.isBefore(activeFrom)) && (activeTo == null || t.isBefore(activeTo));
    }
}
