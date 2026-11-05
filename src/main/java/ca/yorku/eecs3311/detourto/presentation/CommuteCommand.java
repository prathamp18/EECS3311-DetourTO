package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.agent.ClarificationRequest;
import ca.yorku.eecs3311.detourto.application.CommuteScheduler;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code detour commute add school --from home --to school --arrive-by 09:00 [--days weekdays]},
 * {@code commute list}, {@code commute remove school}, {@code commute check [--at ...]} (F11).
 */
@Command(name = "commute",
        mixinStandardHelpOptions = true,
        description = "Save commutes and see when to leave today, with current disruptions.",
        footer = {"", "Examples:",
                "  detour commute add school --from home --to \"York U\" --arrive-by 09:00 --days weekdays",
                "  detour commute check --at 2026-11-02T07:45", "  detour commute list",
                "  detour commute remove school"})
public class CommuteCommand implements Callable<Integer> {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @Parameters(index = "0", paramLabel = "ACTION", description = "add, list, remove or check")
    String action;

    @Parameters(index = "1", arity = "0..1", paramLabel = "NAME", description = "Commute name")
    String name;

    @Option(names = "--from", description = "Origin (a saved place like 'home' works)")
    String from;

    @Option(names = "--to", description = "Destination")
    String to;

    @Option(names = "--arrive-by", description = "Arrive by HH:mm")
    String arriveBy;

    @Option(names = "--days", defaultValue = "weekdays", description = "weekdays, weekends, daily or mon,wed,fri")
    String days;

    @Option(names = "--warn", defaultValue = "20", description = "Minutes before leave-by to warn (default: ${DEFAULT-VALUE})")
    int warn;

    @Option(names = "--at", description = "Pretend it is this time (ISO); default now")
    LocalDateTime at;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        LocalDateTime now = at != null ? at : LocalDateTime.now(PlanCommand.TORONTO).withSecond(0).withNano(0);
        switch (action.toLowerCase()) {
            case "add" -> {
                if (name == null || from == null || to == null || arriveBy == null) {
                    System.err.println("Usage: detour commute add NAME --from PLACE --to PLACE --arrive-by HH:mm [--days ...]");
                    return 2;
                }
                Optional<ClarificationRequest> problem;
                try {
                    problem = c.saveCommute(name, from, to, LocalTime.parse(arriveBy.length() == 4 ? "0" + arriveBy : arriveBy),
                            Commute.parseDays(days), warn);
                } catch (RuntimeException e) {
                    System.err.println("Invalid commute: " + e.getMessage());
                    return 2;
                }
                if (problem.isPresent()) {
                    System.out.println(problem.get().question());
                    return PlanCommand.EXIT_CLARIFY;
                }
                System.out.println("Saved commute \"" + name + "\".");
                return 0;
            }
            case "list" -> {
                List<Commute> all = c.commutes().commutes();
                if (all.isEmpty()) {
                    System.out.println("No commutes saved yet.");
                }
                all.forEach(x -> System.out.printf("  %-10s %s -> %s, arrive by %s, %s, warn %d min before%n",
                        x.name(), x.origin().name(), x.destination().name(), x.arriveBy(), x.daysText(), x.warnMinutesBefore()));
                return 0;
            }
            case "remove" -> {
                if (name == null) {
                    System.err.println("Usage: detour commute remove NAME");
                    return 2;
                }
                boolean removed = c.commutes().remove(name);
                System.out.println(removed ? "Removed \"" + name + "\"." : "No commute called \"" + name + "\".");
                return removed ? 0 : 1;
            }
            case "check" -> {
                List<CommuteScheduler.Status> today = c.commutes().today(now);
                if (today.isEmpty()) {
                    System.out.println("No commutes today (" + now.getDayOfWeek().toString().toLowerCase() + ").");
                    return 0;
                }
                List<CommuteScheduler.Status> due = c.commutes().checkDue(now);
                for (CommuteScheduler.Status s : today) {
                    boolean warnNow = due.stream().anyMatch(d -> d.commute().name().equals(s.commute().name()));
                    System.out.printf("%s%s: %s%n", warnNow ? "[ALERT] " : "", s.commute().name(), s.message());
                    s.option().ifPresent(i -> System.out.println("    " + HM.format(i.departure()) + " -> "
                            + HM.format(i.arrival()) + " via " + String.join(", ",
                            i.rideLegs().stream().map(l -> l.route().label()).distinct().toList())));
                }
                return 0;
            }
            default -> {
                System.err.println("Unknown action '" + action + "'. Use add, list, remove or check.");
                return 2;
            }
        }
    }
}
