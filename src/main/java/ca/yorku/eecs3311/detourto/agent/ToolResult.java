package ca.yorku.eecs3311.detourto.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** What a tool returns to the model: data on success, or an error message it can react to. */
public record ToolResult(boolean ok, Object data, String error) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message);
    }

    /** JSON object sent back to the model as the tool response. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        if (ok) {
            m.put("data", data);
        } else {
            m.put("error", error);
        }
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"tool result could not be encoded\"}";
        }
    }
}
