package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.agent.LLMClient;
import ca.yorku.eecs3311.detourto.agent.LLMClientFactory;
import ca.yorku.eecs3311.detourto.agent.LLMException;
import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.Message;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import ca.yorku.eecs3311.detourto.agent.ToolSpec;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code detour llm-check [--tools]}: checks that the configured LLM provider answers, and with
 * {@code --tools} that a full tool-calling round trip works (model asks for a tool, we run it,
 * the model uses the result). Prints token usage so costs can be logged.
 */
@Command(name = "llm-check",
        mixinStandardHelpOptions = true,
        description = "Check the LLM connection (provider and model come from .env).")
public class LlmCheckCommand implements Callable<Integer> {

    static final ToolSpec CLOCK_TOOL = new ToolSpec("get_toronto_time",
            "Returns the current date and time in Toronto.",
            Map.of("type", "object", "properties", Map.of()));

    @Option(names = "--tools", description = "Also test a tool-calling round trip")
    boolean tools;

    /** For tests: use this client instead of the configured one. */
    LLMClient client;

    @Override
    public Integer call() {
        PrintStream out = System.out;
        try {
            LLMClient llm = client != null ? client : LLMClientFactory.create(AppConfig.load());
            out.println("provider: " + llm.describe());
            long start = System.nanoTime();
            LLMResponse r = llm.complete(List.of(Message.user("Reply with exactly: DetourTO LLM OK")),
                    List.of(), "You are a connectivity check. Follow the instruction exactly.");
            out.printf("reply: %s%n", r.text());
            out.printf("tokens: %d in, %d out, %d ms%n", r.inputTokens(), r.outputTokens(), ms(start));
            if (tools) {
                return toolRoundTrip(llm, out);
            }
            return 0;
        } catch (LLMException e) {
            System.err.println("LLM check failed (" + e.kind() + "): " + e.getMessage());
            return 1;
        }
    }

    private int toolRoundTrip(LLMClient llm, PrintStream out) {
        List<Message> convo = new ArrayList<>();
        convo.add(Message.user("What time is it in Toronto right now? Use the tool, then answer in one sentence."));
        long start = System.nanoTime();
        LLMResponse first = llm.complete(convo, List.of(CLOCK_TOOL), "Use tools when they help.");
        if (!first.wantsTools()) {
            out.println("tools: FAILED, the model answered without calling the tool: " + first.text());
            return 1;
        }
        convo.add(first.toMessage());
        for (ToolCall call : first.toolCalls()) {
            out.println("tool call: " + call.toolName() + " " + call.arguments());
            String now = ZonedDateTime.now(ZoneId.of("America/Toronto"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            convo.add(Message.toolResult(call, "{\"toronto_time\":\"" + now + "\"}"));
        }
        LLMResponse second = llm.complete(convo, List.of(CLOCK_TOOL), "Use tools when they help.");
        out.println("final answer: " + second.text());
        out.printf("tokens: %d in, %d out, %d ms (2 calls)%n",
                first.inputTokens() + second.inputTokens(), first.outputTokens() + second.outputTokens(), ms(start));
        out.println("tools: OK");
        return 0;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
