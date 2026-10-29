package ca.yorku.eecs3311.detourto.agent;

/** Who wrote a message in the conversation with the LLM. */
public enum Role {
    /** The rider, or the agent speaking on the rider's behalf. */
    USER,
    /** The model. */
    ASSISTANT,
    /** The result of a tool call, sent back to the model. */
    TOOL
}
