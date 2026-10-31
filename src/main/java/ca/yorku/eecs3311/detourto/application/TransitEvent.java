package ca.yorku.eecs3311.detourto.application;

import java.time.Instant;
import java.util.Map;

/** Something that happened. {@code payload} holds simple values (strings, numbers, lists). */
public record TransitEvent(EventType type, Map<String, Object> payload, Instant timestamp) {

    public TransitEvent {
        payload = Map.copyOf(payload);
    }

    public static TransitEvent of(EventType type, Map<String, Object> payload) {
        return new TransitEvent(type, payload, Instant.now());
    }
}
