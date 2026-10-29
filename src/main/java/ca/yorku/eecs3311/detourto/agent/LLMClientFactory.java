package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;

/**
 * Factory pattern: picks the LLM provider from configuration so no other class names a provider.
 * Switching providers is a one-line change in .env ({@code LLM_PROVIDER}).
 */
public final class LLMClientFactory {

    public enum Provider {
        GEMINI, SCRIPTED;

        static Provider parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new LLMException(LLMException.Kind.CONFIG,
                        "Unknown LLM_PROVIDER '" + text + "'. Use gemini or scripted.");
            }
        }
    }

    private LLMClientFactory() {
    }

    public static Provider provider(AppConfig cfg) {
        if (cfg.useScriptedLlm()) {
            return Provider.SCRIPTED;
        }
        return Provider.parse(cfg.llmProvider());
    }

    public static LLMClient create(AppConfig cfg) {
        return switch (provider(cfg)) {
            case GEMINI -> new GeminiAdapter(cfg.geminiApiKey().orElse(null), cfg.geminiModel());
            case SCRIPTED -> new ScriptedLLMClient(LLMResponse.text(
                    "Hello from the scripted LLM client (no network call was made)."));
        };
    }
}
