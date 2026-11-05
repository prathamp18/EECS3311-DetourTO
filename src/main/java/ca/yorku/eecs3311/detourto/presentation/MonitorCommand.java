package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.ActiveTrip;
import ca.yorku.eecs3311.detourto.application.AtRiskState;
import ca.yorku.eecs3311.detourto.application.CompletedState;
import ca.yorku.eecs3311.detourto.application.EventType;
import ca.yorku.eecs3311.detourto.application.PlanResult;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code detour monitor --from "York U" --to Union --depart 08:00 --accept-reroute} (F07).
 *
 * <p>Plans the trip, starts monitoring one option, then replays the trip minute by minute
 * against the configured feed, printing every state change (Planned, On track, At risk,
 * Rerouted, Completed). By default the trip is planned as if the alerts were not known yet, so
 * the monitor has something to catch. The GUI monitors on the real clock instead.
 */
@Command(name = "monitor",
        mixinStandardHelpOptions = true,
        description = "Monitor a trip: replays it minute by minute and shows each state change.")
public class MonitorCommand implements Callable<Integer> {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @Option(names = "--from", required = true, description = "Origin")
    String from;

    @Option(names = "--to", required = true, description = "Destination")
    String to;

    @Option(names = "--depart", description = "Leave at HH:mm (default: 08:00)")
    String depart;

    @Option(names = "--arrive-by", description = "Arrive by HH:mm (warns when the trip will be late)")
    String arriveBy;

    @Option(names = "--date", description = "Service date YYYY-MM-DD (default: today)")
    LocalDate date;

    @Option(names = "--strategy", description = "fastest, fewest-transfers, least-walking or accessible")
    String strategy;

    @Option(names = "--option", defaultValue = "opt-1", description = "Which option to take (default: ${DEFAULT-VALUE})")
    String option;

    @Option(names = "--every", defaultValue = "2", description = "Minutes between checks (default: ${DEFAULT-VALUE})")
    int every;

    @Option(names = "--accept-reroute", description = "Take the best reroute when the trip is at risk")
    boolean acceptReroute;

    @Option(names = "--plan-with-alerts", description = "Plan with today's alerts already applied")
    boolean planWithAlerts;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        var o = c.resolvePlace(from);
        var d = c.resolvePlace(to);
        if (o.stop().isEmpty() || d.stop().isEmpty()) {
            var cl = o.stop().isEmpty() ? o.clarification() : d.clarification();
            System.out.println(cl.question());
            return PlanCommand.EXIT_CLARIFY;
        }
        LocalDate day = date != null ? date : LocalDate.now(PlanCommand.TORONTO);
        TripQuery q = arriveBy != null
                ? TripQuery.arriveBy(o.stop().get(), d.stop().get(), day.atTime(LocalTime.parse(pad(arriveBy))))
                : TripQuery.departAt(o.stop().get(), d.stop().get(), day.atTime(LocalTime.parse(pad(depart == null ? "08:00" : depart))));
        if (strategy != null) {
            q.setStrategyKind(StrategyKind.parse(strategy));
        }
        c.applyProfile(q, strategy != null);
        PlanResult plan = c.planWithDisruptions(q, java.util.Set.of(), !planWithAlerts);
        Optional<ActiveTrip> started = c.startTrip(option);
        if (started.isEmpty()) {
            System.out.println(plan.hasOptions() ? "There is no option " + option + "." : plan.message());
            return PlanCommand.EXIT_NO_ROUTE;
        }
        ActiveTrip trip = started.get();
        List<String> log = new ArrayList<>();
        c.eventBus().subscribe(EventType.TRIP_AT_RISK, e -> say(log, "  ! at risk: " + e.payload().get("reason")));

        say(log, "Monitoring " + trip.id() + ": " + describe(trip.itinerary()));
        LocalDateTime t = trip.itinerary().departure().minusMinutes(5).withSecond(0);
        LocalDateTime stopAt = trip.itinerary().arrival().plusHours(3);
        int seen = 0;
        while (!(trip.getState() instanceof CompletedState) && t.isBefore(stopAt)) {
            c.monitor().poll(t);
            List<ActiveTrip.Change> changes = trip.changes();
            for (; seen < changes.size(); seen++) {
                ActiveTrip.Change ch = changes.get(seen);
                say(log, String.format("%s  %s -> %s  (%s)", HM.format(ch.at()), ch.from(), ch.to(), ch.reason()));
            }
            if (trip.getState() instanceof AtRiskState && acceptReroute && !trip.rerouteOptions().isEmpty()) {
                Itinerary best = trip.rerouteOptions().get(0);
                c.acceptReroute(trip.id(), best.id());
                say(log, "  accepted reroute: " + describe(best));
                seen = trip.changes().size() - 1;
            } else if (trip.getState() instanceof AtRiskState && !trip.rerouteOptions().isEmpty() && !acceptReroute
                    && log.stream().noneMatch(x -> x.startsWith("  reroute available"))) {
                say(log, "  reroute available: " + describe(trip.rerouteOptions().get(0)) + " (use --accept-reroute)");
            }
            t = t.plusMinutes(Math.max(1, every));
        }
        say(log, "Final state: " + trip.getState().name() + ", arrival " + HM.format(trip.projectedArrival()));
        if (json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tripId", trip.id());
            m.put("finalState", trip.getState().name());
            m.put("projectedArrival", trip.projectedArrival().toString());
            m.put("changes", trip.changes().stream().map(ch -> Map.of("at", ch.at().toString(), "from", ch.from(),
                    "to", ch.to(), "reason", ch.reason())).toList());
            m.put("log", log);
            System.out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(m));
        }
        return 0;
    }

    private void say(List<String> log, String line) {
        log.add(line);
        if (!json) {
            System.out.println(line);
        }
    }

    private static String describe(Itinerary it) {
        return HM.format(it.departure()) + " -> " + HM.format(it.arrival()) + " via "
                + String.join(", ", it.rideLegs().stream().map(l -> l.route().label()).distinct().toList());
    }

    private static String pad(String t) {
        return t.length() == 4 ? "0" + t : t;
    }
}
