package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.presentation.gui.MainWindow;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** {@code detour gui}: opens the desktop window (same TripController as the CLI). */
@Command(name = "gui", mixinStandardHelpOptions = true, description = "Open the DetourTO desktop window.")
public class GuiCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        MainWindow.launchWith(() -> {
            try {
                return TripController.fromConfig(AppConfig.load());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not load transit data: " + e.getMessage(), e);
            }
        });
        return 0;
    }
}
