package ca.yorku.eecs3311.detourto.agent;

import java.util.List;

/**
 * What the model answered: text and/or tool calls, why it stopped, and token usage (logged for
 * the AI-cost section of the report).
 */
public record LLMResponse(String text, List<ToolCall> toolCalls, String stopReason,
                          int inputTokens, int outputTokens, String providerData) {

    public LLMResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LLMResponse text(String text) {
        return new LLMResponse(text, List.of(), "end_turn", 0, 0, null);
    }

    public static LLMResponse toolCalls(List<ToolCall> calls) {
        return new LLMResponse("", calls, "tool_use", 0, 0, null);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }

    /** This response as the next ASSISTANT turn of the conversation. */
    public Message toMessage() {
        return Message.assistant(text, toolCalls, providerData);
    }
}
