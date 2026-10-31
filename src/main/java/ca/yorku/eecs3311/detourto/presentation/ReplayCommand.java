package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.BenchmarkReport;
import ca.yorku.eecs3311.detourto.domain.ReplayScenario;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.infrastructure.ScenarioLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code detour replay scenarios/ [--json]}: disruption replay and benchmark (F12). */
@Command(name = "replay",
        mixinStandardHelpOptions = true,
        description = "Replay recorded disruption scenarios and report metrics.",
        footer = {"", "Exit codes: 0 ran (see each scenario's result), 1 a scenario file is invalid."})
public class ReplayCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "PATH", description = "A scenario .json file or a folder of them")
    Path path;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        List<ReplayScenario> scenarios;
        try {
            scenarios = new ScenarioLoader().load(path);
        } catch (IllegalArgumentException | java.io.IOException e) {
            System.err.println("Invalid scenario: " + e.getMessage());
            return 1;
        }
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        List<BenchmarkReport> reports = c.runReplay(scenarios);
        Map<String, Object> summary = summary(reports);
        if (json) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("scenarios", reports);
            root.put("summary", summary);
            System.out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root));
            return 0;
        }
        System.out.printf("%-44s %-8s %-8s %-7s %-10s %-4s %s%n", "scenario", "matched", "feasible", "delay",
                "violations", "llm", "ms");
        for (BenchmarkReport r : reports) {
            System.out.printf("%-44s %-8s %-8s %-7s %-10d %-4d %d%n", trim(r.scenario()), yes(r.disruptionMatched()),
                    yes(r.feasible()), r.arrivalDelayMinutes() + " min", r.groundingViolations(), r.llmCalls(),
                    r.latencyMs());
            if (r.error() != null) {
                System.out.println("    failed: " + r.error());
            }
        }
        System.out.printf("%n%s of %s scenarios matched the expected closures; %s grounding violations; %s LLM calls.%n",
                summary.get("matched"), summary.get("scenarios"), summary.get("groundingViolations"),
                summary.get("llmCalls"));
        return 0;
    }

    static Map<String, Object> summary(List<BenchmarkReport> reports) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarios", reports.size());
        m.put("matched", reports.stream().filter(BenchmarkReport::passed).count());
        m.put("feasible", reports.stream().filter(BenchmarkReport::feasible).count());
        m.put("failed", reports.stream().filter(r -> r.error() != null).count());
        m.put("groundingViolations", reports.stream().mapToInt(BenchmarkReport::groundingViolations).sum());
        m.put("llmCalls", reports.stream().mapToInt(BenchmarkReport::llmCalls).sum());
        m.put("meanDelayMinutes", reports.stream().mapToInt(BenchmarkReport::arrivalDelayMinutes).average().orElse(0));
        return m;
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static String trim(String s) {
        return s.length() <= 44 ? s : s.substring(0, 41) + "...";
    }
}
