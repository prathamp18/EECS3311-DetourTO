package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.DisruptionKind;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the model's final text into typed objects. Models often wrap JSON in ```json fences or
 * add a sentence around it, so the first {...} block is extracted before parsing. Anything that
 * does not fit throws {@link ResponseFormatException}, which the agent loop sends back to the
 * model as a correction request.
 */
public final class ResponseParser {

    private final ObjectMapper json = new ObjectMapper();

    /** The JSON object inside the model's text. */
    public JsonNode extractJson(String text) {
        if (text == null) {
            throw new ResponseFormatException("empty answer");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ResponseFormatException("answer must be a JSON object");
        }
        try {
            JsonNode node = json.readTree(text.substring(start, end + 1));
            if (!node.isObject()) {
                throw new ResponseFormatException("answer must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new ResponseFormatException("answer is not valid JSON: " + e.getOriginalMessage());
        }
    }

    /** Either a {@link TripQuery} ("type":"trip") or a {@link ClarificationRequest}. */
    public Object toTripRequest(String text, TransitNetwork network, LocalDateTime now) {
        JsonNode root = extractJson(text);
        String type = root.path("type").asText("trip");
        if (type.equals("clarification")) {
            return toClarification(root, network);
        }
        if (!type.equals("trip")) {
            throw new ResponseFormatException("type must be 'trip' or 'clarification'");
        }
        return toTripQuery(root, network, now);
    }

    public TripQuery toTripQuery(JsonNode root, TransitNetwork network, LocalDateTime now) {
        Stop origin = stop(root, "origin_stop_id", network);
        Stop destination = stop(root, "destination_stop_id", network);
        LocalDateTime time = now;
        String timeText = root.path("time").asText("");
        if (!timeText.isBlank()) {
            try {
                time = LocalDateTime.parse(timeText.strip());
            } catch (DateTimeParseException e) {
                throw new ResponseFormatException("time must look like 2026-10-28T09:30, got '" + timeText + "'");
            }
        }
        String timeType = root.path("time_type").asText("depart_at");
        TripQuery q = switch (timeType) {
            case "arrive_by" -> TripQuery.arriveBy(origin, destination, time);
            case "depart_at" -> TripQuery.departAt(origin, destination, time);
            default -> throw new ResponseFormatException("time_type must be depart_at or arrive_by");
        };
        try {
            if (root.hasNonNull("strategy")) {
                q.setStrategyKind(StrategyKind.parse(root.get("strategy").asText()));
            }
            for (JsonNode m : root.path("avoid_modes")) {
                q.avoidMode(TransitMode.valueOf(m.asText().strip().toUpperCase()));
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseFormatException("bad strategy or mode: " + e.getMessage());
        }
        for (JsonNode r : root.path("avoid_route_ids")) {
            q.avoidRoute(r.asText());
        }
        if (root.has("max_walk_meters") && root.get("max_walk_meters").canConvertToInt()) {
            int meters = root.get("max_walk_meters").asInt();
            if (meters < 0) {
                throw new ResponseFormatException("max_walk_meters must be >= 0");
            }
            q.setMaxWalkMeters(meters);
        }
        return q;
    }

    public ClarificationRequest toClarification(JsonNode root, TransitNetwork network) {
        String question = root.path("question").asText("").strip();
        if (question.isEmpty()) {
            throw new ResponseFormatException("clarification needs a question");
        }
        List<Stop> candidates = new ArrayList<>();
        for (JsonNode id : root.path("candidate_stop_ids")) {
            candidates.add(network.findStop(id.asText())
                    .orElseThrow(() -> new ResponseFormatException("unknown stop id " + id.asText())));
        }
        return new ClarificationRequest(question, candidates);
    }

    /**
     * The interpreter's reading of an alert. Unknown stop ids are a format error (the model is
     * asked to fix them); grounding against tool results is checked by the validator.
     */
    public Disruption toDisruption(String text, ServiceAlert alert, TransitNetwork network) {
        JsonNode root = extractJson(text);
        DisruptionKind kind;
        try {
            kind = DisruptionKind.valueOf(root.path("kind").asText("UNKNOWN").strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseFormatException("kind must be CLOSURE, DELAY, DETOUR, ELEVATOR_OUTAGE or UNKNOWN");
        }
        Set<String> stops = new LinkedHashSet<>();
        for (JsonNode id : root.path("closed_stop_ids")) {
            String sid = id.asText().strip();
            if (network.findStop(sid).isEmpty()) {
                throw new ResponseFormatException("closed_stop_ids contains '" + sid
                        + "', which is not a real stop id; use find_stop or get_route_stops");
            }
            stops.add(sid);
        }
        Set<String> routes = new LinkedHashSet<>();
        for (JsonNode id : root.path("affected_route_ids")) {
            routes.add(id.asText().strip());
        }
        double confidence = root.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            throw new ResponseFormatException("confidence must be a number between 0 and 1");
        }
        String summary = root.path("summary").asText("").strip();
        if (summary.isEmpty()) {
            summary = alert.header();
        }
        return new Disruption("D-" + alert.id(), alert.id(), kind, routes, stops,
                toLocal(alert.activeFrom()), toLocal(alert.activeTo()), confidence, true, summary);
    }

    public Explanation toExplanation(String text) {
        JsonNode root = extractJson(text);
        String summary = root.path("summary").asText("").strip();
        if (summary.isEmpty()) {
            throw new ResponseFormatException("summary is required");
        }
        java.util.Map<String, String> notes = new java.util.LinkedHashMap<>();
        root.path("option_notes").fields().forEachRemaining(e -> notes.put(e.getKey(), e.getValue().asText()));
        List<String> ids = new ArrayList<>();
        root.path("cited_itinerary_ids").forEach(n -> ids.add(n.asText()));
        List<Integer> minutes = new ArrayList<>();
        for (JsonNode n : root.path("cited_minutes")) {
            if (!n.canConvertToInt()) {
                throw new ResponseFormatException("cited_minutes must be whole numbers");
            }
            minutes.add(n.asInt());
        }
        return new Explanation(summary, notes, ids, minutes, true);
    }

    public FollowUpIntent toFollowUpIntent(String text) {
        JsonNode root = extractJson(text);
        IntentKind kind;
        try {
            kind = IntentKind.valueOf(root.path("kind").asText("").strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseFormatException("kind must be one of " + java.util.Arrays.toString(IntentKind.values()));
        }
        return new FollowUpIntent(kind, root.path("value").asText("").strip());
    }

    public List<ca.yorku.eecs3311.detourto.domain.PreferenceUpdate> toPreferences(String text) {
        JsonNode root = extractJson(text);
        JsonNode updates = root.path("updates");
        if (!updates.isArray()) {
            throw new ResponseFormatException("answer must have an \"updates\" list");
        }
        List<ca.yorku.eecs3311.detourto.domain.PreferenceUpdate> out = new ArrayList<>();
        for (JsonNode u : updates) {
            out.add(new ca.yorku.eecs3311.detourto.domain.PreferenceUpdate(u.path("key").asText("").strip(),
                    u.path("value").asText("").strip()));
        }
        return out;
    }

    static LocalDateTime toLocal(java.time.Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, java.time.ZoneId.of("America/Toronto"));
    }

    private static Stop stop(JsonNode root, String field, TransitNetwork network) {
        String id = root.path(field).asText("").strip();
        if (id.isEmpty()) {
            throw new ResponseFormatException(field + " is required");
        }
        return network.findStop(id).orElseThrow(() ->
                new ResponseFormatException(field + " '" + id + "' is not a real stop id; use find_stop"));
    }
}
