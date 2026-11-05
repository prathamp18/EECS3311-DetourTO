package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.RouteScoringStrategy;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.CommuteRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Feature F11: works out when to leave for each saved commute, with today's disruptions, and
 * warns the rider {@code warnMinutesBefore} minutes ahead (at most once per commute per day).
 */
public final class CommuteScheduler {

    /** Today's plan for one commute. */
    public record Status(Commute commute, LocalDate date, Optional<Itinerary> option, LocalDateTime leaveBy,
                         LocalDateTime usualLeaveBy, List<Disruption> disruptions, String message) {

        /** Minutes earlier than usual the rider has to leave (0 when nothing changed). */
        public long extraMinutes() {
            return leaveBy == null || usualLeaveBy == null ? 0 : Duration.between(leaveBy, usualLeaveBy).toMinutes();
        }
    }

    private static final java.time.format.DateTimeFormatter HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private final CommuteRepository commutes;
    private final JourneyPlanner planner;
    private final AlertService alerts;
    private final EventBus eventBus;
    private final Consumer<TripQuery> profileDefaults;
    private final Set<String> warned = new HashSet<>();

    public CommuteScheduler(CommuteRepository commutes, JourneyPlanner planner, AlertService alerts, EventBus eventBus,
                            Consumer<TripQuery> profileDefaults) {
        this.commutes = commutes;
        this.planner = planner;
        this.alerts = alerts;
        this.eventBus = eventBus;
        this.profileDefaults = profileDefaults;
    }

    public void schedule(Commute c) {
        commutes.save(c);
    }

    public boolean remove(String name) {
        return commutes.delete(name);
    }

    public List<Commute> commutes() {
        return commutes.findAll();
    }

    /** Status of every commute that runs on {@code now}'s date. */
    public List<Status> today(LocalDateTime now) {
        List<Status> out = new ArrayList<>();
        for (Commute c : commutes.findAll()) {
            if (c.runsOn(now.toLocalDate())) {
                out.add(status(c, now));
            }
        }
        return out;
    }

    /** Leave-by for one commute on {@code now}'s date, with and without current disruptions. */
    public Status status(Commute c, LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        TripQuery q = TripQuery.arriveBy(c.origin(), c.destination(), date.atTime(c.arriveBy()));
        profileDefaults.accept(q);
        List<Disruption> applied = alerts.currentDisruptions(now).stream().filter(Disruption::appliesByDefault).toList();
        RouteScoringStrategy strategy = RouteScoringStrategy.of(q.strategyKind());
        Optional<Itinerary> best = latest(planner.plan(q.copy(), planner.network().withDisruptions(applied), strategy));
        Optional<Itinerary> usual = latest(planner.plan(q.copy(), planner.network().fullView(), strategy));
        LocalDateTime leaveBy = best.map(Itinerary::departure).orElse(null);
        LocalDateTime usualLeaveBy = usual.map(Itinerary::departure).orElse(null);
        List<Disruption> relevant = best.isPresent() && usual.isPresent()
                && !sameRides(best.get(), usual.get()) ? applied : List.of();
        String message;
        if (best.isEmpty()) {
            message = "No route arrives by " + c.arriveBy() + earliestText(c, now, applied);
        } else if (leaveBy != null && usualLeaveBy != null && leaveBy.isBefore(usualLeaveBy)) {
            long extra = Duration.between(leaveBy, usualLeaveBy).toMinutes();
            message = "Leave by " + HM.format(leaveBy) + ", " + extra + " min earlier than usual"
                    + (relevant.isEmpty() ? "." : " (" + relevant.get(0).summary() + ").");
        } else {
            message = "Leave by " + HM.format(leaveBy) + " (as usual).";
        }
        return new Status(c, date, best, leaveBy, usualLeaveBy, relevant, message);
    }

    /**
     * Called every minute by the GUI (and by {@code detour commute check}). Publishes
     * LEAVE_BY_ALERT when it is time to warn; at most once per commute per day.
     */
    public List<Status> checkDue(LocalDateTime now) {
        List<Status> due = new ArrayList<>();
        for (Status s : today(now)) {
            String key = s.commute().name().toLowerCase() + "|" + s.date();
            if (warned.contains(key)) {
                continue;
            }
            LocalDateTime warnAt = s.leaveBy() == null
                    ? s.date().atTime(s.commute().arriveBy()).minusHours(1)
                    : s.leaveBy().minusMinutes(s.commute().warnMinutesBefore());
            LocalDateTime lastUseful = s.leaveBy() == null ? s.date().atTime(s.commute().arriveBy()) : s.leaveBy();
            if (!now.isBefore(warnAt) && !now.isAfter(lastUseful)) {
                warned.add(key);
                due.add(s);
                eventBus.publish(TransitEvent.of(EventType.LEAVE_BY_ALERT, Map.of(
                        "commute", s.commute().name(),
                        "leaveBy", s.leaveBy() == null ? "" : s.leaveBy().toString(),
                        "message", s.message())));
            }
        }
        return due;
    }

    private static Optional<Itinerary> latest(List<Itinerary> options) {
        return options.stream().max(Comparator.comparing(Itinerary::departure));
    }

    private static boolean sameRides(Itinerary a, Itinerary b) {
        return a.routeIds().equals(b.routeIds()) && a.departure().equals(b.departure());
    }

    private String earliestText(Commute c, LocalDateTime now, List<Disruption> applied) {
        TripQuery early = TripQuery.departAt(c.origin(), c.destination(),
                now.toLocalDate().atTime(c.arriveBy()).minusHours(2));
        profileDefaults.accept(early);
        return planner.plan(early, planner.network().withDisruptions(applied), RouteScoringStrategy.of(early.strategyKind()))
                .stream().map(Itinerary::arrival).min(Comparator.naturalOrder())
                .map(a -> "; the earliest possible arrival is " + HM.format(a) + ".").orElse(".");
    }
}
