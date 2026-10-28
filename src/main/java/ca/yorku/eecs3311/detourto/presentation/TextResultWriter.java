package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.PlanResult;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.domain.WalkLeg;
import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable output for the terminal. */
public final class TextResultWriter {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.CANADA);

    public void write(PlanResult r, PrintStream out) {
        if (r.needsClarification()) {
            out.println(r.clarification().question());
            r.clarification().candidates().forEach(s -> out.println("  - " + s.name() + "  [" + s.id() + "]"));
            return;
        }
        TripQuery q = r.query();
        out.printf("%s -> %s, %s %s (%s)%n", q.origin().name(), q.destination().name(),
                q.isArriveBy() ? "arrive by" : "depart", DAY.format(q.time()), q.strategyKind().cliName());
        out.println(r.message());
        for (Itinerary i : r.options()) {
            out.println();
            out.printf("%-6s %s -> %s   %d min, %d transfer%s, %d m walking%n", i.id(),
                    HM.format(i.departure()), HM.format(i.arrival()), i.durationMinutes(),
                    i.transfers(), i.transfers() == 1 ? "" : "s", i.walkMeters());
            for (Leg leg : i.legs()) {
                out.println("         " + HM.format(leg.start()) + "  " + describe(leg));
            }
        }
    }

    private static String describe(Leg leg) {
        if (leg instanceof RideLeg r) {
            return String.format("%s %s towards %s: %s -> %s (%d stop%s, %d min)", r.mode().name().toLowerCase(),
                    r.route().label(), r.trip().headsign(), shortName(r.from()), shortName(r.to()),
                    r.stopCount(), r.stopCount() == 1 ? "" : "s", r.minutes());
        }
        WalkLeg w = (WalkLeg) leg;
        if (w.meters() == 0) {
            return "change to " + w.to().name() + " (" + Math.max(1, w.minutes()) + " min)";
        }
        return "walk " + w.meters() + " m to " + w.to().name() + " (" + Math.max(1, w.minutes()) + " min)";
    }

    private static String shortName(Stop s) {
        int dash = s.name().indexOf(" - ");
        return dash > 0 ? s.name().substring(0, dash) : s.name();
    }
}
