package ca.yorku.eecs3311.detourto.domain;

/**
 * One change to the rider profile (feature F09).
 *
 * <ul>
 *   <li>{@code save_place} value "label=stopId", {@code forget_place} value "label"</li>
 *   <li>{@code avoid_route} / {@code allow_route} value route id</li>
 *   <li>{@code default_strategy} value fastest | fewest-transfers | least-walking | accessible</li>
 *   <li>{@code step_free} value true | false</li>
 * </ul>
 */
public record PreferenceUpdate(String key, String value) {

    public static final String SAVE_PLACE = "save_place";
    public static final String FORGET_PLACE = "forget_place";
    public static final String AVOID_ROUTE = "avoid_route";
    public static final String ALLOW_ROUTE = "allow_route";
    public static final String DEFAULT_STRATEGY = "default_strategy";
    public static final String STEP_FREE = "step_free";

    /** Human-readable form, e.g. "avoid route 29". */
    public String describe() {
        return switch (key) {
            case SAVE_PLACE -> "save \"" + value.substring(0, Math.max(0, value.indexOf('='))) + "\" as "
                    + value.substring(value.indexOf('=') + 1);
            case FORGET_PLACE -> "forget place \"" + value + "\"";
            case AVOID_ROUTE -> "avoid route " + value;
            case ALLOW_ROUTE -> "allow route " + value + " again";
            case DEFAULT_STRATEGY -> "rank trips by " + value + " by default";
            case STEP_FREE -> Boolean.parseBoolean(value) ? "always plan step-free trips" : "no step-free requirement";
            default -> key + "=" + value;
        };
    }
}
