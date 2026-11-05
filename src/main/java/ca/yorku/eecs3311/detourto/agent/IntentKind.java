package ca.yorku.eecs3311.detourto.agent;

/** What a follow-up message asks for (feature F10). */
public enum IntentKind {
    /** value: "+15" / "-10" (minutes), "depart@09:30" or "arrive@10:00". */
    CHANGE_TIME,
    /** value: route id, e.g. "2" or "510". */
    AVOID_ROUTE,
    /** value: SUBWAY, STREETCAR or BUS. */
    AVOID_MODE,
    /** value: fastest, fewest-transfers, least-walking or accessible. */
    CHANGE_STRATEGY,
    /** value: a stop id (LLM path) or the place text (offline path). */
    CHANGE_DESTINATION,
    UNDO,
    REDO,
    /** The message could not be understood; value holds the reason. */
    UNKNOWN
}
