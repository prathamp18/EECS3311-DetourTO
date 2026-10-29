package ca.yorku.eecs3311.detourto.agent;

import java.util.List;
import java.util.Objects;

/**
 * One turn in the conversation with the LLM. Besides plain text, a turn can carry the tool calls
 * the model made (ASSISTANT) or the result of one tool call (TOOL).
 *
 * <p>{@code providerData} is opaque data a provider needs echoed back unchanged on the next
 * request (Gemini's thought signatures). Only the adapter that produced it reads it.
 */
public final class Message {

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;
    private final String providerData;

    private Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId,
                    String toolName, String providerData) {
        this.role = Objects.requireNonNull(role);
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.providerData = providerData;
    }

    public static Message user(String text) {
        return new Message(Role.USER, text, null, null, null, null);
    }

    public static Message assistant(String text, List<ToolCall> toolCalls, String providerData) {
        return new Message(Role.ASSISTANT, text, toolCalls, null, null, providerData);
    }

    /** The result of running {@code call}; {@code resultJson} is a JSON object as text. */
    public static Message toolResult(ToolCall call, String resultJson) {
        return new Message(Role.TOOL, resultJson, null, call.id(), call.toolName(), null);
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public String providerData() {
        return providerData;
    }

    @Override
    public String toString() {
        return role + ": " + (toolCalls.isEmpty() ? content : toolCalls);
    }
}
