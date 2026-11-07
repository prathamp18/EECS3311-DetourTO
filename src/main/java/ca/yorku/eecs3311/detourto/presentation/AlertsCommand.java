package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code detour alerts [--at ...] [--json]}: current TTC alerts as structured disruptions (F04). */
@Command(name = "alerts",
        mixinStandardHelpOptions = true,
        description = "Show current service alerts and what they mean for trip planning.")
public class AlertsCommand implements Callable<Integer> {

    @Option(names = "--at", description = "Pretend it is this time (ISO, e.g. 2026-11-02T08:00); default now")
    LocalDateTime at;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        LocalDateTime now = at != null ? at : LocalDateTime.now(PlanCommand.TORONTO).withSecond(0).withNano(0);
        List<Disruption> ds = c.getAlerts(now);
        if (json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("at", now.toString());
            m.put("disruptions", ds.stream().map(JsonResultWriter::disruption).toList());
            System.out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(m));
            return 0;
        }
        if (ds.isEmpty()) {
            System.out.println("No active alerts.");
            return 0;
        }
        TransitNetwork net = c.network();
        System.out.println(ds.size() + " active alert" + (ds.size() == 1 ? "" : "s") + ":");
        for (Disruption d : ds) {
            System.out.println();
            System.out.printf("[%s] %s%n", d.id(), d.summary());
            String source = d.confidence() == 0 ? "not interpreted (shown as text only)"
                    : d.llmDerived() ? String.format("interpreted by AI (confidence %.0f%%)", d.confidence() * 100)
                    : "from the alert's structured fields";
            System.out.printf("  %s, %s%s%n", d.kind(), source,
                    d.affectsRouting() && !d.appliesByDefault() ? " - not applied to routing unless you ask" : "");
            if (!d.affectedRouteIds().isEmpty()) {
                System.out.println("  routes: " + d.affectedRouteIds().stream().sorted()
                        .map(r -> net.findRoute(r).map(x -> x.label()).orElse(r)).collect(Collectors.joining(", ")));
            }
            if (!d.closedStopIds().isEmpty()) {
                System.out.println("  stops: " + d.sortedStopIds().stream()
                        .map(id -> net.findStop(id).map(s -> s.name()).orElse(id)).collect(Collectors.joining(", ")));
            }
        }
        return 0;
    }
}
