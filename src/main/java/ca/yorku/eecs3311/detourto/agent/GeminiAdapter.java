package ca.yorku.eecs3311.detourto.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter pattern: makes Google's Gemini API (REST {@code generateContent} with function calling)
 * look like our {@link LLMClient}. The agents only see {@link Message}, {@link ToolSpec},
 * {@link ToolCall} and {@link LLMResponse}; all Gemini JSON stays in this class.
 *
 * <p>Gemini 3 and later return a {@code thoughtSignature} on function-call parts that must be
 * sent back unchanged on the next request, otherwise the API answers 400. The model's raw parts
 * are therefore kept in {@link LLMResponse#providerData()} and replayed as they were received.
 *
 * <p>The API key is sent in the {@code x-goog-api-key} header and never logged or put in an
 * exception message.
 */
public final class GeminiAdapter implements LLMClient {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    /** Placeholder Google documents for function calls that the API did not generate itself. */
    static final String SKIP_SIGNATURE = "skip_thought_signature_validator";
    static final String LOCAL_ID_PREFIX = "local-";
    private static final Pattern RETRY_DELAY = Pattern.compile("(\\d+(?:\\.\\d+)?)s");
    private static final long MAX_RETRY_WAIT_MS = 30_000;

    /** How the adapter waits between retries (replaced in tests so they run instantly). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient http;
    private final int maxRetries;
    private final Sleeper sleeper;
    private final int maxOutputTokens;
    private final ObjectMapper json = new ObjectMapper();
    private int localIds;

    public GeminiAdapter(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build(), 2, Thread::sleep, 2048);
    }

    public GeminiAdapter(String apiKey, String model, String baseUrl, HttpClient http,
                         int maxRetries, Sleeper sleeper, int maxOutputTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMException(LLMException.Kind.CONFIG,
                    "GEMINI_API_KEY is not set. Create a key at https://aistudio.google.com/apikey "
                            + "and put it in your .env file.");
        }
        if (model == null || model.isBlank()) {
            throw new LLMException(LLMException.Kind.CONFIG, "GEMINI_MODEL is not set in .env");
        }
        this.apiKey = apiKey.strip();
        this.model = model.strip();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String describe() {
        return "gemini/" + model;
    }

    @Override
    public LLMResponse complete(List<Message> msgs, List<ToolSpec> tools, String system) {
        String body = toRequestJson(msgs, tools, system);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    pause(backoff(attempt, null));
                    continue;
                }
                throw new LLMException(LLMException.Kind.UNAVAILABLE,
                        "Could not reach the Gemini API: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException(LLMException.Kind.UNAVAILABLE, "Interrupted while calling Gemini", e);
            }
            int status = response.statusCode();
            if (status == 200) {
                return fromResponseJson(response.body());
            }
            boolean retryable = status == 429 || status == 500 || status == 503;
            if (retryable && attempt < maxRetries) {
                pause(backoff(attempt, response.body()));
                continue;
            }
            throw toException(status, response.body());
        }
    }

    // ------------------------------------------------------------------ request

    String toRequestJson(List<Message> msgs, List<ToolSpec> tools, String system) {
        ObjectNode root = json.createObjectNode();
        if (system != null && !system.isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }
        ArrayNode contents = root.putArray("contents");
        ArrayNode pendingToolResults = null;
        for (Message m : msgs) {
            if (m.role() == Role.TOOL) {
                // all results for one model turn go into a single user turn, in call order
                if (pendingToolResults == null) {
                    ObjectNode turn = contents.addObject();
                    turn.put("role", "user");
                    pendingToolResults = turn.putArray("parts");
                }
                pendingToolResults.add(functionResponsePart(m));
                continue;
            }
            pendingToolResults = null;
            ObjectNode turn = contents.addObject();
            if (m.role() == Role.USER) {
                turn.put("role", "user");
                turn.putArray("parts").addObject().put("text", m.content());
            } else {
                turn.put("role", "model");
                turn.set("parts", modelParts(m));
            }
        }
        if (!tools.isEmpty()) {
            ArrayNode decls = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec t : tools) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                d.put("description", t.description());
                if (hasProperties(t.inputSchema())) {
                    d.set("parameters", json.valueToTree(t.inputSchema()));
                }
            }
        }
        root.putObject("generationConfig").put("maxOutputTokens", maxOutputTokens);
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.BAD_REQUEST, "Could not build Gemini request", e);
        }
    }

    /** Gemini rejects an OBJECT schema with no properties, so such tools are sent without one. */
    private static boolean hasProperties(Map<String, Object> schema) {
        Object props = schema.get("properties");
        return !(props instanceof Map<?, ?> m) || !m.isEmpty();
    }

    /** The model's earlier turn: replay Gemini's own parts when we have them, else rebuild. */
    private ArrayNode modelParts(Message m) {
        if (m.providerData() != null) {
            try {
                JsonNode raw = json.readTree(m.providerData());
                if (raw.isArray()) {
                    return (ArrayNode) raw;
                }
            } catch (JsonProcessingException e) {
                // fall through and rebuild the parts
            }
        }
        ArrayNode parts = json.createArrayNode();
        if (!m.content().isEmpty()) {
            parts.addObject().put("text", m.content());
        }
        boolean first = true;
        for (ToolCall c : m.toolCalls()) {
            ObjectNode part = parts.addObject();
            ObjectNode fc = part.putObject("functionCall");
            fc.put("name", c.toolName());
            fc.set("args", json.valueToTree(c.arguments()));
            if (c.id() != null && !c.id().startsWith(LOCAL_ID_PREFIX)) {
                fc.put("id", c.id());
            }
            if (first) {
                part.put("thoughtSignature", SKIP_SIGNATURE);
                first = false;
            }
        }
        return parts;
    }

    private ObjectNode functionResponsePart(Message m) {
        ObjectNode part = json.createObjectNode();
        ObjectNode fr = part.putObject("functionResponse");
        fr.put("name", m.toolName());
        if (m.toolCallId() != null && !m.toolCallId().startsWith(LOCAL_ID_PREFIX)) {
            fr.put("id", m.toolCallId());
        }
        JsonNode result;
        try {
            result = json.readTree(m.content());
        } catch (JsonProcessingException e) {
            result = json.getNodeFactory().textNode(m.content());
        }
        if (result == null || !result.isObject()) {
            ObjectNode wrapped = json.createObjectNode();
            wrapped.set("result", result);
            result = wrapped;
        }
        fr.set("response", result);
        return part;
    }

    // ------------------------------------------------------------------ response

    LLMResponse fromResponseJson(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.UNAVAILABLE, "Gemini returned invalid JSON", e);
        }
        JsonNode usage = root.path("usageMetadata");
        int in = usage.path("promptTokenCount").asInt(0);
        int out = usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String reason = root.path("promptFeedback").path("blockReason").asText("no candidates");
            return new LLMResponse("", List.of(), "blocked: " + reason, in, out, null);
        }
        JsonNode parts = candidate.path("content").path("parts");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue; // thinking summary, not part of the answer
            }
            if (part.has("text")) {
                text.append(part.get("text").asText());
            }
            JsonNode fc = part.path("functionCall");
            if (!fc.isMissingNode()) {
                Map<String, Object> args = json.convertValue(fc.path("args"),
                        new TypeReference<Map<String, Object>>() { });
                String id = fc.hasNonNull("id") ? fc.get("id").asText() : LOCAL_ID_PREFIX + (++localIds);
                calls.add(new ToolCall(id, fc.path("name").asText(), args));
            }
        }
        String finish = candidate.path("finishReason").asText("");
        String stop = !calls.isEmpty() ? "tool_use"
                : switch (finish) {
                    case "STOP", "" -> "end_turn";
                    case "MAX_TOKENS" -> "max_tokens";
                    default -> finish.toLowerCase();
                };
        String raw = parts.isArray() ? parts.toString() : null;
        return new LLMResponse(text.toString().strip(), calls, stop, in, out, raw);
    }

    private LLMException toException(int status, String body) {
        String detail = errorMessage(body);
        if (status == 401 || status == 403 || detail.toLowerCase().contains("api key")) {
            return new LLMException(LLMException.Kind.AUTH,
                    "Gemini rejected the API key (HTTP " + status + "). Check GEMINI_API_KEY in .env.");
        }
        if (status == 429) {
            return new LLMException(LLMException.Kind.RATE_LIMIT,
                    "Gemini rate limit or free-tier quota reached: " + detail);
        }
        if (status == 404) {
            return new LLMException(LLMException.Kind.BAD_REQUEST,
                    "Gemini model '" + model + "' not found. Check GEMINI_MODEL in .env: " + detail);
        }
        if (status >= 500) {
            return new LLMException(LLMException.Kind.UNAVAILABLE, "Gemini server error " + status + ": " + detail);
        }
        return new LLMException(LLMException.Kind.BAD_REQUEST, "Gemini HTTP " + status + ": " + detail);
    }

    private String errorMessage(String body) {
        try {
            JsonNode msg = json.readTree(body).path("error").path("message");
            if (!msg.isMissingNode()) {
                return msg.asText();
            }
        } catch (JsonProcessingException e) {
            // not JSON
        }
        return body == null ? "" : body.substring(0, Math.min(200, body.length()));
    }

    /** Wait time before retry {@code attempt}: Gemini's RetryInfo delay if given, else 2^n s. */
    long backoff(int attempt, String body) {
        if (body != null) {
            Matcher m = RETRY_DELAY.matcher(body.contains("retryDelay")
                    ? body.substring(body.indexOf("retryDelay")) : "");
            if (m.find()) {
                return Math.min(MAX_RETRY_WAIT_MS, (long) (Double.parseDouble(m.group(1)) * 1000));
            }
        }
        return Math.min(MAX_RETRY_WAIT_MS, 1000L << (attempt + 1));
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException(LLMException.Kind.UNAVAILABLE, "Interrupted while waiting to retry", e);
        }
    }
}
