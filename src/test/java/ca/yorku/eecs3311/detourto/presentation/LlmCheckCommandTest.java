package ca.yorku.eecs3311.detourto.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.agent.LLMResponse;
import ca.yorku.eecs3311.detourto.agent.Role;
import ca.yorku.eecs3311.detourto.agent.ScriptedLLMClient;
import ca.yorku.eecs3311.detourto.agent.ToolCall;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class LlmCheckCommandTest {

    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original;

    @BeforeEach
    void capture() {
        original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    @Test
    void toolRoundTripWithScriptedModel() {
        ScriptedLLMClient llm = new ScriptedLLMClient(
                LLMResponse.text("DetourTO LLM OK"),
                LLMResponse.toolCalls(List.of(new ToolCall("local-1", "get_toronto_time", Map.of()))),
                LLMResponse.text("It is 08:00 in Toronto."));
        LlmCheckCommand cmd = new LlmCheckCommand();
        cmd.client = llm;
        int code = new CommandLine(cmd).execute("--tools");

        String out = captured.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.contains("provider: scripted"));
        assertTrue(out.contains("reply: DetourTO LLM OK"));
        assertTrue(out.contains("tools: OK"));
        // third request: user, assistant tool call, tool result
        var third = llm.requests().get(2).msgs();
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL), third.stream().map(m -> m.role()).toList());
        assertTrue(third.get(2).content().contains("toronto_time"));
    }

    @Test
    void modelThatIgnoresTheToolFails() {
        LlmCheckCommand cmd = new LlmCheckCommand();
        cmd.client = new ScriptedLLMClient(LLMResponse.text("ok"), LLMResponse.text("I think it is noon"));
        assertEquals(1, new CommandLine(cmd).execute("--tools"));
    }
}
