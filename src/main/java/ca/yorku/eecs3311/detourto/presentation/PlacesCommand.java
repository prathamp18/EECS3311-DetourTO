package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code detour places add school "York University" | list | remove school} (F09). Saved labels
 * work anywhere a place is expected: {@code detour plan --from home --to school}.
 */
@Command(name = "places",
        mixinStandardHelpOptions = true,
        description = "Save, list or remove named places (home, school, ...).",
        footer = {"", "Examples:", "  detour places add school \"York University\"", "  detour places list",
                "  detour places remove school"})
public class PlacesCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "ACTION", description = "add, list or remove")
    String action;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "ARGS", description = "LABEL [PLACE]")
    List<String> args = List.of();

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        switch (action.toLowerCase()) {
            case "add" -> {
                if (args.size() < 2) {
                    System.err.println("Usage: detour places add LABEL PLACE");
                    return 2;
                }
                String place = String.join(" ", args.subList(1, args.size()));
                TripController.Resolution r = c.savePlace(args.get(0), place);
                if (r.stop().isEmpty()) {
                    System.out.println(r.clarification().question());
                    r.clarification().candidates().forEach(s -> System.out.println("  - " + s.name() + "  [" + s.id() + "]"));
                    return PlanCommand.EXIT_CLARIFY;
                }
                System.out.println("Saved \"" + RiderProfile.normalize(args.get(0)) + "\" = " + r.stop().get().name());
                return 0;
            }
            case "list" -> {
                Map<String, String> places = c.profile().savedPlacesInOrder();
                if (places.isEmpty()) {
                    System.out.println("No saved places. Add one with: detour places add home \"Keele\"");
                }
                places.forEach((label, id) -> System.out.printf("  %-10s %s%n", label,
                        c.network().findStop(id).map(s -> s.name()).orElse(id + " (not in the current network)")));
                return 0;
            }
            case "remove" -> {
                if (args.isEmpty()) {
                    System.err.println("Usage: detour places remove LABEL");
                    return 2;
                }
                if (c.profile().placeStopId(args.get(0)).isEmpty()) {
                    System.out.println("No saved place called \"" + args.get(0) + "\".");
                    return 1;
                }
                c.updateProfile(List.of(new PreferenceUpdate(PreferenceUpdate.FORGET_PLACE, args.get(0))));
                System.out.println("Removed \"" + RiderProfile.normalize(args.get(0)) + "\".");
                return 0;
            }
            default -> {
                System.err.println("Unknown action '" + action + "'. Use add, list or remove.");
                return 2;
            }
        }
    }
}
