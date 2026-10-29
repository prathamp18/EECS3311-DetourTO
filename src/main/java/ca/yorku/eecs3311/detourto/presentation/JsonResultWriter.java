package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.PlanResult;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.domain.WalkLeg;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes results as JSON for scripts and the Stage 3 KUMA harness. The JSON shape is built here
 * from plain maps, so domain classes stay free of serialization annotations and the format only
 * changes when this class changes.
 */
public final class JsonResultWriter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Object result, PrintStream out) {
        Object tree = result instanceof PlanResult p ? toMap(p) : result;
        try {
            out.println(mapper.writeValueAsString(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write JSON", e);
        }
    }

    Map<String, Object> toMap(PlanResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.needsClarification() ? "clarification_needed"
                : r.query() == null ? "error" : r.hasOptions() ? "ok" : "no_route");
        m.put("message", r.message());
        m.put("query", r.query() == null ? null : query(r.query()));
        List<Object> options = new ArrayList<>();
        r.options().forEach(i -> options.add(itinerary(i)));
        m.put("options", options);
        if (r.needsClarification()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("question", r.clarification().question());
            c.put("candidates", r.clarification().candidates().stream().map(this::stop).toList());
            m.put("clarification", c);
        }
        return m;
    }

    private Map<String, Object> query(TripQuery q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", stop(q.origin()));
        m.put("to", stop(q.destination()));
        m.put(q.isArriveBy() ? "arriveBy" : "departAt", time(q.time()));
        m.put("strategy", q.strategyKind().cliName());
        m.put("avoidRoutes", List.copyOf(q.avoidRouteIds()));
        m.put("avoidModes", q.avoidModes().stream().map(Enum::name).toList());
        m.put("maxWalkMeters", q.maxWalkMeters());
        return m;
    }

    private Map<String, Object> itinerary(Itinerary i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("departure", time(i.departure()));
        m.put("arrival", time(i.arrival()));
        m.put("durationMinutes", i.durationMinutes());
        m.put("transfers", i.transfers());
        m.put("walkMeters", i.walkMeters());
        m.put("routes", List.copyOf(i.routeIds()));
        m.put("legs", i.legs().stream().map(this::leg).toList());
        return m;
    }

    private Map<String, Object> leg(Leg leg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", leg.mode().name());
        if (leg instanceof RideLeg r) {
            m.put("routeId", r.route().id());
            m.put("routeName", r.route().label());
            m.put("tripId", r.trip().id());
            m.put("headsign", r.trip().headsign());
            m.put("stops", r.stopCount());
        }
        m.put("from", stop(leg.from()));
        m.put("to", stop(leg.to()));
        m.put("start", time(leg.start()));
        m.put("end", time(leg.end()));
        m.put("minutes", leg.minutes());
        if (leg instanceof WalkLeg w) {
            m.put("meters", w.meters());
        }
        return m;
    }

    private Map<String, Object> stop(Stop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("lat", s.lat());
        m.put("lon", s.lon());
        return m;
    }

    private static String time(LocalDateTime t) {
        return t.withNano(0).toString();
    }
}
