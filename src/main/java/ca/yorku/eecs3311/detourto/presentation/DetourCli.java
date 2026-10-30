package ca.yorku.eecs3311.detourto.presentation;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root of the {@code detour} command line. Each feature adds a sub-command; every sub-command that
 * prints results supports {@code --json} so the KUMA tests in Stage 3 can read the output.
 */
@Command(name = "detour",
        mixinStandardHelpOptions = true,
        version = "DetourTO 0.1.0",
        description = "Disruption-aware TTC trip planning agent.",
        subcommands = {PlanCommand.class, ArrivalsCommand.class, LlmCheckCommand.class, ValidateFeedCommand.class})
public class DetourCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** Runs the CLI and returns the exit code (used by Main and by tests). */
    public static int execute(String... args) {
        return new CommandLine(new DetourCli()).execute(args);
    }
}
