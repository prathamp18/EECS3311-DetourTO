package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code detour prefs} shows the rider profile; options change it; {@code --learn "<text>"} lets
 * the PreferenceExtractorAgent (or built-in rules) pick preferences out of a sentence (F09).
 */
@Command(name = "prefs",
        mixinStandardHelpOptions = true,
        description = "Show or change your saved preferences.")
public class PrefsCommand implements Callable<Integer> {

    @Option(names = "--strategy", description = "Default strategy: fastest, fewest-transfers, least-walking, accessible")
    String strategy;

    @Option(names = "--avoid-route", split = ",", description = "Always avoid these routes")
    List<String> avoid = List.of();

    @Option(names = "--allow-route", split = ",", description = "Stop avoiding these routes")
    List<String> allow = List.of();

    @Option(names = "--step-free", arity = "1", description = "true or false: always plan step-free trips")
    Boolean stepFree;

    @Option(names = "--learn", paramLabel = "TEXT",
            description = "Learn preferences from a sentence, e.g. \"never put me on the 29\"")
    String learn;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        TripController c = controller != null ? controller : TripController.fromConfig(AppConfig.load());
        List<PreferenceUpdate> updates = new ArrayList<>();
        try {
            if (strategy != null) {
                updates.add(new PreferenceUpdate(PreferenceUpdate.DEFAULT_STRATEGY, StrategyKind.parse(strategy).cliName()));
            }
            for (String r : avoid) {
                if (c.network().findRoute(r).isEmpty()) {
                    System.err.println("There is no route " + r + ".");
                    return 1;
                }
                updates.add(new PreferenceUpdate(PreferenceUpdate.AVOID_ROUTE, r));
            }
            allow.forEach(r -> updates.add(new PreferenceUpdate(PreferenceUpdate.ALLOW_ROUTE, r)));
            if (stepFree != null) {
                updates.add(new PreferenceUpdate(PreferenceUpdate.STEP_FREE, stepFree.toString()));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid option: " + e.getMessage());
            return 2;
        }
        List<String> changed = new ArrayList<>(c.updateProfile(updates));
        if (learn != null) {
            List<String> learned = c.learnPreferences(learn, LocalDateTime.now(PlanCommand.TORONTO));
            System.out.println(learned.isEmpty() ? "No lasting preference found in that sentence."
                    : "Learned: " + String.join("; ", learned));
            changed.addAll(learned);
        }
        changed.stream().filter(x -> learn == null).forEach(x -> System.out.println("Updated: " + x));
        print(c);
        return 0;
    }

    private static void print(TripController c) {
        RiderProfile p = c.profile();
        System.out.println();
        System.out.println("Your profile");
        System.out.println("  default strategy : " + p.defaultStrategy().cliName());
        System.out.println("  step-free trips  : " + (p.needsStepFree() ? "always" : "no"));
        System.out.println("  avoided routes   : " + (p.avoidedRouteIds().isEmpty() ? "none"
                : String.join(", ", p.avoidedRouteIds().stream().sorted()
                        .map(r -> c.network().findRoute(r).map(x -> x.label()).orElse(r)).toList())));
        System.out.println("  saved places     : " + (p.savedPlaces().isEmpty() ? "none"
                : String.join(", ", p.savedPlacesInOrder().keySet())));
    }
}
