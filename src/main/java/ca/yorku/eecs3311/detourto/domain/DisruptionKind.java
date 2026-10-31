package ca.yorku.eecs3311.detourto.domain;

/** What an alert means for riders. */
public enum DisruptionKind {
    /** Stops or a whole route cannot be used. */
    CLOSURE,
    /** Service runs but late. Shown to the rider; not used to change routes. */
    DELAY,
    /** Vehicles skip some stops. */
    DETOUR,
    /** No step-free access at a station (elevator out of service). */
    ELEVATOR_OUTAGE,
    /** Could not be interpreted; shown as text only. */
    UNKNOWN
}
