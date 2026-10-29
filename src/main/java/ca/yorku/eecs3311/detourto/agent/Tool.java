package ca.yorku.eecs3311.detourto.agent;

/**
 * A deterministic service the model may call. Each tool wraps exactly one service (place
 * resolver, journey planner, ...); the model never touches those services directly.
 */
public interface Tool {

    ToolSpec spec();

    ToolResult execute(ToolCall call, AgentContext ctx);
}
