package ca.yorku.eecs3311.detourto.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Application settings.
 *
 * <p>Values are read, in order of priority, from real environment variables and then from a local
 * {@code .env} file (KEY=VALUE lines). The .env file holds secrets such as the Gemini API key, is listed in
 * .gitignore and is never committed; {@code .env.example} shows which keys exist.
 */
public final class AppConfig {

    public static final String DEFAULT_GTFS_PATH = "data/sample/mini-gtfs";
    public static final String DEFAULT_FEED_BASE_URL = "https://bustime.ttc.ca/gtfsrt";
    public static final String DEFAULT_LLM_PROVIDER = "gemini";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-flash-latest";

    private final Map<String, String> values;

    private AppConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** Loads {@code .env} from the given file (if it exists) and overlays environment variables. */
    public static AppConfig load(Path envFile) {
        Map<String, String> merged = new HashMap<>();
        if (envFile != null && Files.isRegularFile(envFile)) {
            merged.putAll(parseEnvFile(envFile));
        }
        merged.putAll(System.getenv());
        return new AppConfig(merged);
    }

    /** Loads {@code .env} from the current working directory. */
    public static AppConfig load() {
        return load(Path.of(".env"));
    }

    /** Config built from an explicit map (used by tests). */
    public static AppConfig of(Map<String, String> values) {
        return new AppConfig(values);
    }

    static Map<String, String> parseEnvFile(Path file) {
        Map<String, String> out = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
        return out;
    }

    public Optional<String> get(String key) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }

    public String getOrDefault(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    /** Path to a GTFS zip or an unzipped GTFS folder. Defaults to the small sample network. */
    public Path gtfsPath() {
        return Path.of(getOrDefault("GTFS_PATH", DEFAULT_GTFS_PATH));
    }

    public String feedBaseUrl() {
        return getOrDefault("TTC_FEED_BASE_URL", DEFAULT_FEED_BASE_URL);
    }

    public Path traceFile() {
        return Path.of(getOrDefault("TRACE_FILE", "trace.jsonl"));
    }

    /** Which LLM adapter to use: gemini or scripted. */
    public String llmProvider() {
        return getOrDefault("LLM_PROVIDER", DEFAULT_LLM_PROVIDER);
    }

    /** Google AI Studio API key (secret: only in .env, never committed). */
    public Optional<String> geminiApiKey() {
        return get("GEMINI_API_KEY");
    }

    public String geminiModel() {
        return getOrDefault("GEMINI_MODEL", DEFAULT_GEMINI_MODEL);
    }

    /** true = use the offline ScriptedLLMClient instead of Gemini (tests, demos without a key). */
    public boolean useScriptedLlm() {
        return get("USE_SCRIPTED_LLM").map(Boolean::parseBoolean).orElse(false);
    }
}
