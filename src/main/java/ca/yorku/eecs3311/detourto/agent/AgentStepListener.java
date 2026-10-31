package ca.yorku.eecs3311.detourto.agent;

/**
 * Receives each step of an agent run (LLM call, tool call, validation). The application layer
 * forwards these to its EventBus; the agent package stays independent of the application.
 */
@FunctionalInterface
public interface AgentStepListener {

    void onStep(String agent, String kind, String detail);
}
