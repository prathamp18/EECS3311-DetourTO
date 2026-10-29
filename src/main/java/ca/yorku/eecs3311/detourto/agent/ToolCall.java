package ca.yorku.eecs3311.detourto.agent;

import java.util.Map;

/** The model asking to run one tool with the given arguments. */
public record ToolCall(String id, String toolName, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** A string argument, or the fallback when it is missing. */
    public String stringArg(String name, String fallback) {
        Object v = arguments.get(name);
        return v == null ? fallback : v.toString();
    }
}
