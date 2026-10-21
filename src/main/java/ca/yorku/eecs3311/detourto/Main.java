package ca.yorku.eecs3311.detourto;

import ca.yorku.eecs3311.detourto.presentation.ValidateFeedCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the command-line interface ({@code java -jar target/detourto.jar ...}).
 * Sub-commands are added as features are implemented.
 */
@Command(name = "detour",
        mixinStandardHelpOptions = true,
        version = "DetourTO 0.1.0",
        description = "Disruption-aware TTC trip planning agent.",
        subcommands = {ValidateFeedCommand.class})
public class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}
