package ca.yorku.eecs3311.detourto.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observer: appends every event as one JSON line to the trace file (default trace.jsonl). The
 * trace shows each LLM call, tool call and validation result, and is the evidence used by the
 * Stage 3 KUMA tests. It never contains API keys: events only carry agent steps and results.
 */
public final class TraceLogger implements EventListener {

    private final Path traceFile;
    private final ObjectMapper json = new ObjectMapper();

    public TraceLogger(Path traceFile) {
        this.traceFile = traceFile;
    }

    @Override
    public synchronized void onEvent(TransitEvent e) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", e.timestamp().toString());
        line.put("type", e.type().name());
        line.putAll(e.payload());
        try {
            Path parent = traceFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(traceFile, json.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not encode trace event", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("could not write " + traceFile + ": " + ex.getMessage(), ex);
        }
    }

    public Path traceFile() {
        return traceFile;
    }
}
