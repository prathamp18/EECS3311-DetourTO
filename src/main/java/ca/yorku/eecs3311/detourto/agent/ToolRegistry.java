package ca.yorku.eecs3311.detourto.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All tools by name. Agents ask for the subset they are allowed to use. */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool t) {
        tools.put(t.spec().name(), t);
        return this;
    }

    public List<ToolSpec> specsFor(List<String> names) {
        List<ToolSpec> out = new ArrayList<>();
        for (String n : names) {
            Tool t = tools.get(n);
            if (t == null) {
                throw new IllegalArgumentException("no tool registered with name " + n);
            }
            out.add(t.spec());
        }
        return out;
    }

    /** Runs a call; unknown tools and tool crashes become error results the model can see. */
    public ToolResult execute(ToolCall call, AgentContext ctx) {
        Tool t = tools.get(call.toolName());
        if (t == null) {
            return ToolResult.error("unknown tool: " + call.toolName());
        }
        try {
            return t.execute(call, ctx);
        } catch (RuntimeException e) {
            return ToolResult.error(call.toolName() + " failed: " + e.getMessage());
        }
    }
}
