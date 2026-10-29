package ca.yorku.eecs3311.detourto.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Offline stand-in for a real model: returns prepared responses in order and records every
 * request. Used by unit tests (deterministic, free) and for demos without network access.
 */
public final class ScriptedLLMClient implements LLMClient {

    /** One recorded call. */
    public record Request(List<Message> msgs, List<ToolSpec> tools, String system) {
    }

    private final Queue<LLMResponse> script = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();

    public ScriptedLLMClient(List<LLMResponse> responses) {
        script.addAll(responses);
    }

    public ScriptedLLMClient(LLMResponse... responses) {
        this(List.of(responses));
    }

    public ScriptedLLMClient then(LLMResponse response) {
        script.add(response);
        return this;
    }

    @Override
    public synchronized LLMResponse complete(List<Message> msgs, List<ToolSpec> tools, String system) {
        requests.add(new Request(List.copyOf(msgs), List.copyOf(tools), system));
        LLMResponse next = script.poll();
        if (next == null) {
            throw new IllegalStateException("ScriptedLLMClient ran out of responses after "
                    + requests.size() + " calls");
        }
        return next;
    }

    @Override
    public String describe() {
        return "scripted";
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public int remaining() {
        return script.size();
    }
}
