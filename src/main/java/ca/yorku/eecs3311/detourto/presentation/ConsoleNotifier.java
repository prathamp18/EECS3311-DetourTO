package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.EventListener;
import ca.yorku.eecs3311.detourto.application.TransitEvent;
import java.io.PrintStream;

/** Observer: prints events to the terminal (used by {@code --verbose} to show agent steps live). */
public final class ConsoleNotifier implements EventListener {

    private final PrintStream out;

    public ConsoleNotifier(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onEvent(TransitEvent e) {
        switch (e.type()) {
            case AGENT_STEP -> out.printf("  [%s] %s: %s%n", e.payload().get("agent"), e.payload().get("kind"),
                    e.payload().get("detail"));
            case ALERTS_UPDATED -> out.println("  [alerts] " + e.payload().get("count") + " active");
            case ITINERARY_UPDATED -> out.println("  [planner] options " + e.payload().get("options")
                    + ", avoiding " + e.payload().get("disruptions"));
            default -> out.println("  [" + e.type() + "] " + e.payload());
        }
    }
}
