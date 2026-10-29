package ca.yorku.eecs3311.detourto.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LLMClientFactoryTest {

    @Test
    void picksTheConfiguredProvider() {
        LLMClient gemini = LLMClientFactory.create(AppConfig.of(Map.of(
                "LLM_PROVIDER", "gemini", "GEMINI_API_KEY", "k", "GEMINI_MODEL", "gemini-x")));
        assertInstanceOf(GeminiAdapter.class, gemini);
        assertEquals("gemini/gemini-x", gemini.describe());

        assertInstanceOf(ScriptedLLMClient.class,
                LLMClientFactory.create(AppConfig.of(Map.of("LLM_PROVIDER", "scripted"))));
        assertInstanceOf(ScriptedLLMClient.class, LLMClientFactory.create(AppConfig.of(Map.of(
                "LLM_PROVIDER", "gemini", "USE_SCRIPTED_LLM", "true"))), "scripted flag wins");
    }

    @Test
    void defaultsToGeminiWithTheLatestFlashAlias() {
        LLMClient c = LLMClientFactory.create(AppConfig.of(Map.of("GEMINI_API_KEY", "k")));
        assertEquals("gemini/" + AppConfig.DEFAULT_GEMINI_MODEL, c.describe());
    }

    @Test
    void helpfulErrorsForMissingKeyAndUnknownProvider() {
        assertEquals(LLMException.Kind.CONFIG, assertThrows(LLMException.class,
                () -> LLMClientFactory.create(AppConfig.of(Map.of()))).kind());
        assertEquals(LLMException.Kind.CONFIG, assertThrows(LLMException.class,
                () -> LLMClientFactory.create(AppConfig.of(Map.of("LLM_PROVIDER", "openai")))).kind());
    }

    @Test
    void scriptedClientReplaysInOrderAndRecordsRequests() {
        ScriptedLLMClient s = new ScriptedLLMClient(LLMResponse.text("one"))
                .then(LLMResponse.toolCalls(List.of(new ToolCall("local-1", "find_stop", Map.of("query", "Union")))));
        assertEquals("one", s.complete(List.of(Message.user("a")), List.of(), "sys").text());
        assertEquals("find_stop", s.complete(List.of(Message.user("b")), List.of(), "").toolCalls().get(0).toolName());
        assertEquals(2, s.requests().size());
        assertEquals("sys", s.requests().get(0).system());
        assertEquals(0, s.remaining());
        assertThrows(IllegalStateException.class, () -> s.complete(List.of(), List.of(), ""));
    }
}
