package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.ArrivalBoard;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Arrival;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code detour arrivals "Spadina & College" [-n 8] [--at 2026-10-28T08:00] [--json]} (F08). */
@Command(name = "arrivals",
        mixinStandardHelpOptions = true,
        description = "Show the next departures at a stop, with live delays for buses and streetcars.",
        footer = {"", "Exit codes: 0 ok, 4 the stop name needs clarifying."})
public class ArrivalsCommand implements Callable<Integer> {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @Parameters(index = "0", paramLabel = "STOP", description = "Stop or station name, stop id, or lat,lon")
    String stop;

    @Option(names = "-n", defaultValue = "8", description = "How many departures (default: ${DEFAULT-VALUE})")
    int count;

    @Option(names = "--at", description = "Pretend it is this time (ISO, e.g. 2026-10-28T08:00); default now")
    LocalDateTime at;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        LocalDateTime now = at != null ? at : LocalDateTime.now(PlanCommand.TORONTO).withSecond(0).withNano(0);
        TripController.ArrivalsResult result = c.getArrivals(stop, now, count);
        if (result.board() == null) {
            if (json) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "clarification_needed");
                m.put("question", result.clarification().question());
                m.put("candidates", result.clarification().candidates().stream()
                        .map(s -> Map.of("id", s.id(), "name", s.name())).toList());
                print(m);
            } else {
                System.out.println(result.clarification().question());
                result.clarification().candidates().forEach(s -> System.out.println("  - " + s.name() + "  [" + s.id() + "]"));
            }
            return PlanCommand.EXIT_CLARIFY;
        }
        ArrivalBoard.Board board = result.board();
        if (json) {
            print(toMap(board));
            return 0;
        }
        System.out.println(board.stop().name() + "  (" + HM.format(now) + ")");
        System.out.println(board.note());
        if (board.arrivals().isEmpty()) {
            System.out.println("No departures in the next 3 hours.");
        }
        for (Arrival a : board.arrivals()) {
            String status = !a.realtime() ? "scheduled"
                    : a.delaySeconds() >= 60 ? "late " + Math.round(a.delaySeconds() / 60.0) + " min"
                    : a.delaySeconds() <= -60 ? "early " + Math.round(-a.delaySeconds() / 60.0) + " min" : "on time";
            System.out.printf("  %s  %-28s towards %-26s %s%n", HM.format(a.predicted()), a.route().label(),
                    a.headsign(), status);
        }
        return 0;
    }

    private Map<String, Object> toMap(ArrivalBoard.Board b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("stop", Map.of("id", b.stop().id(), "name", b.stop().name()));
        m.put("liveDataAvailable", b.liveDataAvailable());
        m.put("note", b.note());
        List<Object> rows = new ArrayList<>();
        for (Arrival a : b.arrivals()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("routeId", a.route().id());
            r.put("routeName", a.route().label());
            r.put("mode", a.route().mode().name());
            r.put("headsign", a.headsign());
            r.put("tripId", a.tripId());
            r.put("platform", a.stop().id());
            r.put("scheduled", a.scheduled().toString());
            r.put("predicted", a.predicted().toString());
            r.put("delaySeconds", a.delaySeconds());
            r.put("realtime", a.realtime());
            rows.add(r);
        }
        m.put("arrivals", rows);
        return m;
    }

    private static void print(Object o) throws Exception {
        System.out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o));
    }
}
