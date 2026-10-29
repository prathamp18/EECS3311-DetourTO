package ca.yorku.eecs3311.detourto.agent;

import java.util.List;

/**
 * The only way the agents talk to a language model. Each provider is wrapped by an adapter
 * (Adapter pattern) so agents never see a vendor API: {@link GeminiAdapter} for Google Gemini,
 * {@link ScriptedLLMClient} in tests.
 */
public interface LLMClient {

    /**
     * Sends the conversation and returns the model's next turn.
     *
     * @param msgs   conversation so far, oldest first
     * @param tools  tools the model may call (may be empty)
     * @param system system prompt (may be empty)
     * @throws LLMException when the provider fails (bad key, quota, network)
     */
    LLMResponse complete(List<Message> msgs, List<ToolSpec> tools, String system);

    /** Provider and model, e.g. "gemini/gemini-flash-latest" (for logs and the trace). */
    String describe();
}
