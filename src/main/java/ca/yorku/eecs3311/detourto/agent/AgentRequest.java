package ca.yorku.eecs3311.detourto.agent;

/** One job for an agent: a short task name, the rider's text, and the context. */
public record AgentRequest(String task, String userText, AgentContext context) {
}
