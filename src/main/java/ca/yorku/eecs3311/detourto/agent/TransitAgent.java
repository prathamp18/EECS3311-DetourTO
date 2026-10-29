package ca.yorku.eecs3311.detourto.agent;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Template Method pattern: {@link #run} is the one tool-using loop shared by every agent.
 *
 * <ol>
 *   <li>build the messages,</li>
 *   <li>call the model; while it asks for tools, run them and send the results back,</li>
 *   <li>parse the final answer and validate it against the real network,</li>
 *   <li>if it is invalid, tell the model what was wrong and let it try again (once).</li>
 * </ol>
 *
 * Subclasses fill in only the steps that differ: the system prompt, which tools they may use, how
 * to parse the answer and how to validate it.
 */
public abstract class TransitAgent {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 6;
    public static final int DEFAULT_MAX_REPAIRS = 1;
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm", Locale.CANADA);

    protected final LLMClient llm;
    protected final ToolRegistry tools;
    protected final ResponseParser parser;
    protected final GroundingValidator validator;
    protected final int maxToolRounds;
    protected final int maxRepairs;

    protected TransitAgent(LLMClient llm, ToolRegistry tools, ResponseParser parser,
                           GroundingValidator validator) {
        this(llm, tools, parser, validator, DEFAULT_MAX_TOOL_ROUNDS, DEFAULT_MAX_REPAIRS);
    }

    protected TransitAgent(LLMClient llm, ToolRegistry tools, ResponseParser parser,
                           GroundingValidator validator, int maxToolRounds, int maxRepairs) {
        this.llm = llm;
        this.tools = tools;
        this.parser = parser;
        this.validator = validator;
        this.maxToolRounds = maxToolRounds;
        this.maxRepairs = maxRepairs;
    }

    /** The template method. Final so every agent follows the same safe loop. */
    public final AgentResult run(AgentRequest req) {
        AgentContext ctx = req.context();
        List<Message> msgs = new ArrayList<>(buildMessages(req));
        List<ToolSpec> specs = tools.specsFor(toolNames());
        int llmCalls = 0;
        int toolCalls = 0;
        int repairs = 0;
        int rounds = 0;
        List<String> lastErrors = List.of();
        while (true) {
            LLMResponse r;
            try {
                r = llm.complete(msgs, specs, systemPrompt());
            } catch (LLMException e) {
                return new AgentResult(false, null, List.of("LLM error (" + e.kind() + "): " + e.getMessage()),
                        toolCalls, llmCalls);
            }
            llmCalls++;
            onStep("llm_call", r.wantsTools() ? r.toolCalls().size() + " tool call(s)" : "final answer");
            if (r.wantsTools()) {
                if (++rounds > maxToolRounds) {
                    return new AgentResult(false, null, List.of("too many tool rounds (" + maxToolRounds + ")"),
                            toolCalls, llmCalls);
                }
                msgs.add(r.toMessage());
                for (ToolCall call : r.toolCalls()) {
                    ToolResult result = tools.execute(call, ctx);
                    toolCalls++;
                    onStep("tool_call", call.toolName() + " " + call.arguments() + " -> " + (result.ok() ? "ok" : result.error()));
                    msgs.add(Message.toolResult(call, result.toJson()));
                }
                continue;
            }
            ValidationReport report;
            Object output = null;
            try {
                output = parseFinal(r, ctx);
                report = validate(output, ctx);
            } catch (ResponseFormatException e) {
                report = ValidationReport.of(List.of(e.getMessage()));
            }
            if (report.valid()) {
                onStep("validated", output.getClass().getSimpleName());
                return new AgentResult(true, output, List.of(), toolCalls, llmCalls);
            }
            lastErrors = report.errors();
            onStep("rejected", String.join("; ", lastErrors));
            if (repairs++ >= maxRepairs) {
                return onInvalid(report, req, toolCalls, llmCalls);
            }
            msgs.add(r.toMessage());
            msgs.add(Message.user("Your answer was rejected by the validator: " + String.join("; ", lastErrors)
                    + ". Use the tools if needed, then answer again with the JSON object only."));
        }
    }

    /** Default: recent conversation, then the request with the current Toronto time. */
    protected List<Message> buildMessages(AgentRequest req) {
        List<Message> msgs = new ArrayList<>();
        req.context().conversation().recent(6).stream()
                .filter(m -> m.role() == Role.USER || (m.role() == Role.ASSISTANT && m.toolCalls().isEmpty()))
                .forEach(msgs::add);
        msgs.add(Message.user("Current date and time in Toronto: " + NOW.format(req.context().now())
                + "\nRequest: " + req.userText()));
        return msgs;
    }

    protected abstract String systemPrompt();

    protected abstract List<String> toolNames();

    /** Turns the final answer into a typed object; throws ResponseFormatException if it can't. */
    protected abstract Object parseFinal(LLMResponse r, AgentContext ctx);

    protected abstract ValidationReport validate(Object output, AgentContext ctx);

    /** Called when the answer is still invalid after the allowed repairs. */
    protected AgentResult onInvalid(ValidationReport report, AgentRequest req, int toolCalls, int llmCalls) {
        List<String> errors = new ArrayList<>();
        errors.add("the model's answer failed validation");
        errors.addAll(report.errors());
        return new AgentResult(false, null, errors, toolCalls, llmCalls);
    }

    /** Hook for logging each step (the event bus and trace file are attached in a later step). */
    protected void onStep(String kind, String detail) {
    }
}
