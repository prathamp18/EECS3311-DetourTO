package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.ReplayScenario;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads replay scenarios from JSON files (one scenario per file). See {@code scenarios/} for
 * examples. Problems are reported as {@link IllegalArgumentException} with the file name.
 */
public final class ScenarioLoader {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private final ObjectMapper json = new ObjectMapper();

    /** One file, or every *.json file in a folder (sorted by name). */
    public List<ReplayScenario> load(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<ReplayScenario> out = new ArrayList<>();
            try (Stream<Path> files = Files.list(path)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    out.add(loadFile(f));
                }
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("no .json scenario files in " + path);
            }
            return out;
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("scenario file not found: " + path);
        }
        return List.of(loadFile(path));
    }

    ReplayScenario loadFile(Path f) throws IOException {
        JsonNode root;
        try {
            root = json.readTree(Files.readString(f));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(f.getFileName() + ": not valid JSON: " + e.getOriginalMessage());
        }
        String where = f.getFileName().toString();
        String name = required(root, "name", where);
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(required(root, "at", where));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + ": 'at' must look like 2026-11-02T08:00");
        }
        String request = root.path("request").asText(null);
        String from = root.path("from").asText(null);
        String to = root.path("to").asText(null);
        if ((from == null || to == null) && request == null) {
            throw new IllegalArgumentException(where + ": give 'request', or both 'from' and 'to'");
        }
        StrategyKind strategy;
        try {
            strategy = StrategyKind.parse(root.path("strategy").asText("fastest"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": unknown strategy");
        }
        List<ServiceAlert> alerts = new ArrayList<>();
        int i = 0;
        for (JsonNode a : root.path("alerts")) {
            i++;
            Instant start = a.hasNonNull("activeFrom")
                    ? LocalDateTime.parse(a.get("activeFrom").asText()).atZone(TORONTO).toInstant() : null;
            alerts.add(new ServiceAlert(a.path("id").asText("alert-" + i), a.path("header").asText(""),
                    a.path("description").asText(""), strings(a.path("routes")), strings(a.path("stops")),
                    start, null, a.path("cause").asText("UNKNOWN_CAUSE"), a.path("effect").asText("UNKNOWN_EFFECT")));
        }
        Set<String> expected = new LinkedHashSet<>(strings(root.path("expectedClosedStopIds")));
        return new ReplayScenario(name, at, request, from, to, strategy, root.path("arriveBy").asBoolean(false),
                alerts, expected);
    }

    private static String required(JsonNode root, String field, String where) {
        String v = root.path(field).asText("").strip();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(where + ": '" + field + "' is required");
        }
        return v;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
