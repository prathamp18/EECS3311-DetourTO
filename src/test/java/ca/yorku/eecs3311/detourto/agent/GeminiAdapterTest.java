package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the adapter against a local fake of the Gemini REST API. */
class GeminiAdapterTest {

    static final String KEY = "test-key-123";
    static final ObjectMapper JSON = new ObjectMapper();
    static final ToolSpec FIND_STOP = new ToolSpec("find_stop", "Find a stop by name",
            Map.of("type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")));

    record Reply(int status, String body) {
    }

    record Received(String path, String apiKey, JsonNode body) {
    }

    HttpServer server;
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<Received> received = new ArrayList<>();
    final List<Long> sleeps = new ArrayList<>();
    GeminiAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"), JSON.readTree(body)));
            Reply r = replies.isEmpty() ? new Reply(500, "{}") : replies.poll();
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        adapter = adapter(2);
    }

    GeminiAdapter adapter(int retries) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        return new GeminiAdapter(KEY, "gemini-test", base, HttpClient.newHttpClient(), retries, sleeps::add, 512);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static String textReply(String text) {
        return """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"%s"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":4,"thoughtsTokenCount":3}}
                """.formatted(text);
    }

    @Test
    void sendsSystemPromptAndParsesText() {
        replies.add(new Reply(200, textReply("DetourTO LLM OK")));
        LLMResponse r = adapter.complete(List.of(Message.user("hi")), List.of(), "be brief");

        assertEquals("DetourTO LLM OK", r.text());
        assertEquals("end_turn", r.stopReason());
        assertEquals(12, r.inputTokens());
        assertEquals(7, r.outputTokens(), "thinking tokens are billed as output");
        Received req = received.get(0);
        assertEquals("/v1beta/models/gemini-test:generateContent", req.path());
        assertEquals(KEY, req.apiKey());
        assertEquals("be brief", req.body().at("/systemInstruction/parts/0/text").asText());
        assertEquals("user", req.body().at("/contents/0/role").asText());
        assertEquals("hi", req.body().at("/contents/0/parts/0/text").asText());
        assertEquals(512, req.body().at("/generationConfig/maxOutputTokens").asInt());
        assertTrue(req.body().path("tools").isMissingNode());
    }

    @Test
    void toolCallRoundTripReplaysThoughtSignature() {
        replies.add(new Reply(200, """
                {"candidates":[{"content":{"role":"model","parts":[
                   {"functionCall":{"name":"find_stop","args":{"query":"York U"},"id":"fc-1"},"thoughtSignature":"SIG-abc"}]},
                  "finishReason":"STOP"}]}
                """));
        replies.add(new Reply(200, textReply("York University Station")));

        List<Message> convo = new ArrayList<>(List.of(Message.user("Where is York U?")));
        LLMResponse first = adapter.complete(convo, List.of(FIND_STOP), "");
        assertTrue(first.wantsTools());
        assertEquals("tool_use", first.stopReason());
        ToolCall call = first.toolCalls().get(0);
        assertEquals("find_stop", call.toolName());
        assertEquals("York U", call.stringArg("query", ""));

        convo.add(first.toMessage());
        convo.add(Message.toolResult(call, "{\"stopId\":\"STN_YU\"}"));
        LLMResponse second = adapter.complete(convo, List.of(FIND_STOP), "");
        assertEquals("York University Station", second.text());

        JsonNode tools = received.get(0).body().at("/tools/0/functionDeclarations/0");
        assertEquals("find_stop", tools.get("name").asText());
        assertEquals("string", tools.at("/parameters/properties/query/type").asText());

        JsonNode contents = received.get(1).body().get("contents");
        assertEquals(3, contents.size());
        assertEquals("model", contents.at("/1/role").asText());
        assertEquals("SIG-abc", contents.at("/1/parts/0/thoughtSignature").asText(), "signature sent back unchanged");
        assertEquals("user", contents.at("/2/role").asText());
        assertEquals("find_stop", contents.at("/2/parts/0/functionResponse/name").asText());
        assertEquals("fc-1", contents.at("/2/parts/0/functionResponse/id").asText());
        assertEquals("STN_YU", contents.at("/2/parts/0/functionResponse/response/stopId").asText());
    }

    @Test
    void toolWithoutArgumentsIsSentWithoutParameters() {
        replies.add(new Reply(200, textReply("ok")));
        ToolSpec clock = new ToolSpec("get_time", "time", Map.of("type", "object", "properties", Map.of()));
        adapter.complete(List.of(Message.user("time?")), List.of(clock, FIND_STOP), "");
        JsonNode decls = received.get(0).body().at("/tools/0/functionDeclarations");
        assertTrue(decls.get(0).path("parameters").isMissingNode());
        assertFalse(decls.get(1).path("parameters").isMissingNode());
    }

    @Test
    void parallelToolResultsGoInOneUserTurnInOrder() {
        replies.add(new Reply(200, textReply("done")));
        ToolCall a = new ToolCall("local-1", "find_stop", Map.of("query", "Union"));
        ToolCall b = new ToolCall("local-2", "find_stop", Map.of("query", "Keele"));
        List<Message> convo = List.of(Message.user("two stops"),
                Message.assistant("", List.of(a, b), null),
                Message.toolResult(a, "{\"stopId\":\"STN_UN\"}"),
                Message.toolResult(b, "not json"));
        adapter.complete(convo, List.of(FIND_STOP), "");

        JsonNode contents = received.get(0).body().get("contents");
        assertEquals(3, contents.size());
        JsonNode modelParts = contents.at("/1/parts");
        assertEquals(GeminiAdapter.SKIP_SIGNATURE, modelParts.at("/0/thoughtSignature").asText(),
                "rebuilt history gets the documented placeholder on the first call only");
        assertTrue(modelParts.get(1).path("thoughtSignature").isMissingNode());
        assertTrue(modelParts.at("/0/functionCall/id").isMissingNode(), "local ids are not sent");
        JsonNode results = contents.at("/2/parts");
        assertEquals(2, results.size());
        assertEquals("STN_UN", results.at("/0/functionResponse/response/stopId").asText());
        assertEquals("not json", results.at("/1/functionResponse/response/result").asText());
    }

    @Test
    void rateLimitIsRetriedUsingTheServerDelay() {
        replies.add(new Reply(429, """
                {"error":{"code":429,"message":"Resource exhausted","details":[{"retryDelay":"3s"}]}}"""));
        replies.add(new Reply(200, textReply("ok")));
        assertEquals("ok", adapter.complete(List.of(Message.user("hi")), List.of(), "").text());
        assertEquals(List.of(3000L), sleeps);
    }

    @Test
    void errorsAreMappedAndNeverContainTheKey() {
        GeminiAdapter noRetry = adapter(0);
        replies.add(new Reply(429, "{\"error\":{\"message\":\"Quota exceeded\"}}"));
        LLMException limit = assertThrows(LLMException.class,
                () -> noRetry.complete(List.of(Message.user("x")), List.of(), ""));
        assertEquals(LLMException.Kind.RATE_LIMIT, limit.kind());

        replies.add(new Reply(400, "{\"error\":{\"message\":\"API key not valid. Please pass a valid API key.\"}}"));
        LLMException auth = assertThrows(LLMException.class,
                () -> noRetry.complete(List.of(Message.user("x")), List.of(), ""));
        assertEquals(LLMException.Kind.AUTH, auth.kind());
        assertFalse(auth.getMessage().contains(KEY));

        replies.add(new Reply(404, "{\"error\":{\"message\":\"models/gemini-test is not found\"}}"));
        LLMException missing = assertThrows(LLMException.class,
                () -> noRetry.complete(List.of(Message.user("x")), List.of(), ""));
        assertEquals(LLMException.Kind.BAD_REQUEST, missing.kind());
        assertTrue(missing.getMessage().contains("GEMINI_MODEL"));
    }

    @Test
    void thoughtPartsAndBlockedPromptsAreHandled() {
        LLMResponse r = adapter.fromResponseJson("""
                {"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"answer"}]},
                  "finishReason":"MAX_TOKENS"}]}""");
        assertEquals("answer", r.text());
        assertEquals("max_tokens", r.stopReason());
        LLMResponse blocked = adapter.fromResponseJson("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertEquals("blocked: SAFETY", blocked.stopReason());
    }

    @Test
    void missingKeyOrModelIsAConfigError() {
        assertEquals(LLMException.Kind.CONFIG,
                assertThrows(LLMException.class, () -> new GeminiAdapter("", "m")).kind());
        assertEquals(LLMException.Kind.CONFIG,
                assertThrows(LLMException.class, () -> new GeminiAdapter("k", " ")).kind());
    }
}
