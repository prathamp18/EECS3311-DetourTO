package ca.yorku.eecs3311.detourto.application;

/** Kinds of events published on the {@link EventBus}. */
public enum EventType {
    ALERTS_UPDATED,
    ITINERARY_UPDATED,
    TRIP_STATE_CHANGED,
    TRIP_AT_RISK,
    LEAVE_BY_ALERT,
    AGENT_STEP
}
