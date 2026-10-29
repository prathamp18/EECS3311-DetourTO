package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.PlanResult;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * {@code detour plan "York U to Union by 9:30, step-free"} (F01, uses the LLM agent) or
 * {@code detour plan --from "York U" --to Union --arrive-by 09:30 --strategy accessible}
 * (F02 and F03, no LLM). Both support {@code --json}.
 */
@Command(name = "plan",
        mixinStandardHelpOptions = true,
        description = "Plan a TTC trip, in plain English or with --from/--to.",
        footer = {"", "Exit codes: 0 options found, 3 no route, 4 a place needs clarifying,",
                "5 the request could not be understood (or no LLM key)."})
public class PlanCommand implements Callable<Integer> {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    static final int EXIT_NO_ROUTE = 3;
    static final int EXIT_CLARIFY = 4;
    static final int EXIT_NOT_UNDERSTOOD = 5;

    @Spec
    CommandSpec spec;

    @Parameters(arity = "0..1", paramLabel = "REQUEST",
            description = "Plain-English request, e.g. \"York U to Union by 10, no streetcars\"")
    String request;

    @Option(names = "--from", description = "Origin: station or street name, stop id, or lat,lon")
    String from;

    @Option(names = "--to", description = "Destination, same formats as --from")
    String to;

    static class When {
        @Option(names = "--depart", description = "Leave at HH:mm, an ISO date-time, or 'now' (default: now)")
        String depart;

        @Option(names = "--arrive-by", description = "Arrive by HH:mm or an ISO date-time")
        String arriveBy;
    }

    @ArgGroup(exclusive = true)
    When when = new When();

    @Option(names = "--date", description = "Service date YYYY-MM-DD for HH:mm times (default: today)")
    LocalDate date;

    @Option(names = "--strategy", defaultValue = "fastest",
            description = "fastest, fewest-transfers, least-walking or accessible (default: ${DEFAULT-VALUE})")
    String strategy;

    @Option(names = "--avoid-route", split = ",", description = "Route ids to avoid, e.g. 29,510")
    List<String> avoidRoutes = List.of();

    @Option(names = "--avoid-mode", split = ",", description = "Modes to avoid: subway, streetcar, bus")
    List<String> avoidModes = List.of();

    @Option(names = "--max-walk", description = "Maximum total walking in metres (default: 800)")
    Integer maxWalk;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    /** For tests: use this controller instead of loading the configured feed. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        PlanResult result = plan(c);
        if (json) {
            new JsonResultWriter().write(result, System.out);
        } else {
            new TextResultWriter().write(result, System.out);
        }
        if (result.needsClarification()) {
            return EXIT_CLARIFY;
        }
        if (result.query() == null) {
            return EXIT_NOT_UNDERSTOOD;
        }
        return result.hasOptions() ? 0 : EXIT_NO_ROUTE;
    }

    PlanResult plan(TripController c) {
        if (request != null && !request.isBlank()) {
            if (from != null || to != null) {
                throw new ParameterException(spec.commandLine(), "Give either a REQUEST or --from/--to, not both.");
            }
            LocalDateTime now = when.depart != null ? parseTime(when.depart) : parseTime("now");
            return c.planFromText(request, now);
        }
        if (from == null || to == null) {
            throw new ParameterException(spec.commandLine(), "Give a REQUEST in quotes, or both --from and --to.");
        }
        TripController.Resolution origin = c.resolvePlace(from);
        if (origin.stop().isEmpty()) {
            return PlanResult.needsClarification(origin.clarification());
        }
        TripController.Resolution destination = c.resolvePlace(to);
        if (destination.stop().isEmpty()) {
            return PlanResult.needsClarification(destination.clarification());
        }
        Stop o = origin.stop().get();
        Stop d = destination.stop().get();
        TripQuery q = when.arriveBy != null
                ? TripQuery.arriveBy(o, d, parseTime(when.arriveBy))
                : TripQuery.departAt(o, d, parseTime(when.depart == null ? "now" : when.depart));
        try {
            q.setStrategyKind(StrategyKind.parse(strategy));
            avoidRoutes.forEach(q::avoidRoute);
            avoidModes.forEach(m -> q.avoidMode(TransitMode.valueOf(m.strip().toUpperCase())));
            if (maxWalk != null) {
                q.setMaxWalkMeters(maxWalk);
            }
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), "Invalid option: " + e.getMessage());
        }
        return c.planFromForm(q);
    }

    LocalDateTime parseTime(String text) {
        String t = text.strip();
        LocalDate day = date != null ? date : LocalDate.now(TORONTO);
        if (t.equalsIgnoreCase("now")) {
            LocalDateTime now = LocalDateTime.now(TORONTO).withSecond(0).withNano(0);
            return date != null ? day.atTime(now.toLocalTime()) : now;
        }
        try {
            if (t.contains("T")) {
                return LocalDateTime.parse(t);
            }
            return day.atTime(LocalTime.parse(t.length() == 4 ? "0" + t : t));
        } catch (DateTimeParseException e) {
            throw new ParameterException(spec.commandLine(),
                    "Invalid time '" + text + "'. Use HH:mm (e.g. 08:30) or 2026-10-22T08:30.");
        }
    }
}
