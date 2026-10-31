package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.AgentContext;
import ca.yorku.eecs3311.detourto.agent.AlertInterpreterAgent;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.RealtimeFeed;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Feature F04: turns live TTC alerts into {@link Disruption}s. Alerts whose structured fields
 * already say what is closed are converted directly (confidence 1). Only the rest go to the
 * {@link AlertInterpreterAgent}. Results are cached by alert id and text, so each alert is
 * interpreted once.
 */
public final class AlertService {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final RealtimeFeed feed;
    private final AlertInterpreterAgent interpreter;
    private final TransitNetwork network;
    private final Map<String, Disruption> cache = new HashMap<>();
    private int interpretedCount;

    /** {@code interpreter} may be null: free-text alerts are then kept as UNKNOWN. */
    public AlertService(RealtimeFeed feed, AlertInterpreterAgent interpreter, TransitNetwork network) {
        this.feed = feed;
        this.interpreter = interpreter;
        this.network = network;
    }

    /** Disruptions for the alerts active at {@code at}, in feed order. */
    public synchronized List<Disruption> currentDisruptions(LocalDateTime at) {
        List<Disruption> out = new ArrayList<>();
        for (ServiceAlert a : feed.alerts()) {
            if (!a.isActiveAt(at.atZone(TORONTO).toInstant())) {
                continue;
            }
            String key = a.id() + "|" + (a.header() + a.description() + a.effect() + a.informedStopIds()).hashCode();
            Disruption d = cache.get(key);
            if (d == null) {
                d = fromStructuredFields(a).orElseGet(() -> interpret(a, at));
                cache.put(key, d);
            }
            out.add(d);
        }
        return out;
    }

    public synchronized Optional<Disruption> find(String disruptionId, LocalDateTime at) {
        return currentDisruptions(at).stream()
                .filter(d -> d.id().equals(disruptionId) || d.sourceAlertId().equals(disruptionId))
                .findFirst();
    }

    /** How many alerts were sent to the LLM so far (for the trace and benchmarks). */
    public synchronized int interpretedCount() {
        return interpretedCount;
    }

    /**
     * Deterministic conversion when the GTFS-RT fields are enough. Empty when the meaning is only
     * in the text (for example a DETOUR or UNKNOWN_EFFECT alert without stops).
     */
    Optional<Disruption> fromStructuredFields(ServiceAlert a) {
        Set<String> stops = new LinkedHashSet<>();
        for (String id : a.informedStopIds()) {
            if (network.findStop(id).isPresent()) {
                stops.add(id);
            }
        }
        Set<String> routes = new LinkedHashSet<>();
        for (String id : a.informedRouteIds()) {
            if (network.findRoute(id).isPresent()) {
                routes.add(id);
            }
        }
        DisruptionKind kind = switch (a.effect()) {
            case "NO_SERVICE" -> stops.isEmpty() && routes.isEmpty() ? null : DisruptionKind.CLOSURE;
            case "DETOUR", "STOP_MOVED" -> stops.isEmpty() ? null : DisruptionKind.DETOUR;
            case "ACCESSIBILITY_ISSUE" -> stops.isEmpty() ? null : DisruptionKind.ELEVATOR_OUTAGE;
            case "SIGNIFICANT_DELAYS", "REDUCED_SERVICE" -> DisruptionKind.DELAY;
            case "NO_EFFECT", "ADDITIONAL_SERVICE" -> DisruptionKind.UNKNOWN;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        Set<String> closed = kind == DisruptionKind.DELAY || kind == DisruptionKind.UNKNOWN ? Set.of() : stops;
        return Optional.of(new Disruption("D-" + a.id(), a.id(), kind, routes, closed,
                local(a.activeFrom()), local(a.activeTo()), 1.0, false, a.header()));
    }

    private Disruption interpret(ServiceAlert a, LocalDateTime at) {
        if (interpreter == null) {
            return new Disruption("D-" + a.id(), a.id(), DisruptionKind.UNKNOWN, Set.copyOf(a.informedRouteIds()),
                    Set.of(), local(a.activeFrom()), local(a.activeTo()), 0.0, false,
                    a.header() + " (not interpreted: no LLM configured)");
        }
        interpretedCount++;
        return interpreter.interpret(a, AgentContext.at(at));
    }

    private static LocalDateTime local(java.time.Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, TORONTO);
    }
}
