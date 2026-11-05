package ca.yorku.eecs3311.detourto.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What DetourTO remembers about the rider (feature F09): saved places ("home" -> a stop id),
 * a default strategy, routes to avoid, and whether trips must be step-free.
 */
public final class RiderProfile {

    private final Map<String, String> savedPlaces = new LinkedHashMap<>();
    private StrategyKind defaultStrategy = StrategyKind.FASTEST;
    private final Set<String> avoidedRouteIds = new LinkedHashSet<>();
    private boolean needsStepFree;

    /** Applies one update. @throws IllegalArgumentException for an unknown key or bad value */
    public void apply(PreferenceUpdate u) {
        String v = u.value() == null ? "" : u.value().strip();
        switch (u.key()) {
            case PreferenceUpdate.SAVE_PLACE -> {
                int eq = v.indexOf('=');
                if (eq <= 0 || eq == v.length() - 1) {
                    throw new IllegalArgumentException("save_place needs label=stopId");
                }
                savedPlaces.put(normalize(v.substring(0, eq)), v.substring(eq + 1).strip());
            }
            case PreferenceUpdate.FORGET_PLACE -> savedPlaces.remove(normalize(v));
            case PreferenceUpdate.AVOID_ROUTE -> avoidedRouteIds.add(v);
            case PreferenceUpdate.ALLOW_ROUTE -> avoidedRouteIds.remove(v);
            case PreferenceUpdate.DEFAULT_STRATEGY -> defaultStrategy = StrategyKind.parse(v);
            case PreferenceUpdate.STEP_FREE -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("step_free must be true or false");
                }
                needsStepFree = Boolean.parseBoolean(v);
            }
            default -> throw new IllegalArgumentException("unknown preference '" + u.key() + "'");
        }
    }

    /**
     * Fills in profile defaults on a new query: avoided routes always; the strategy only when the
     * rider did not choose one (step-free riders get the accessible strategy).
     */
    public void applyDefaults(TripQuery q, boolean strategyChosen) {
        avoidedRouteIds.forEach(q::avoidRoute);
        if (needsStepFree) {
            q.setStrategyKind(StrategyKind.ACCESSIBLE);
        } else if (!strategyChosen) {
            q.setStrategyKind(defaultStrategy);
        }
    }

    public Optional<String> placeStopId(String label) {
        return Optional.ofNullable(savedPlaces.get(normalize(label)));
    }

    public Map<String, String> savedPlaces() {
        return Map.copyOf(savedPlaces);
    }

    /** Saved places in the order they were added. */
    public Map<String, String> savedPlacesInOrder() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(savedPlaces));
    }

    public StrategyKind defaultStrategy() {
        return defaultStrategy;
    }

    public Set<String> avoidedRouteIds() {
        return Set.copyOf(avoidedRouteIds);
    }

    public boolean needsStepFree() {
        return needsStepFree;
    }

    /** "My Home", "home" and " HOME " are the same label. */
    public static String normalize(String label) {
        String s = label.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("my ")) {
            s = s.substring(3).strip();
        }
        return s;
    }
}
