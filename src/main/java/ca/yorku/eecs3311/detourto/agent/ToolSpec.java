package ca.yorku.eecs3311.detourto.agent;

import java.util.Map;

/**
 * A tool the model may call: its name, what it does, and a JSON-schema object describing its
 * arguments (type "object" with "properties" and "required").
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public ToolSpec {
        inputSchema = Map.copyOf(inputSchema);
    }
}
