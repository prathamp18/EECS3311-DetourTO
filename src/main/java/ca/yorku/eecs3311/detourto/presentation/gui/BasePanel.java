package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.EventListener;
import ca.yorku.eecs3311.detourto.application.TransitEvent;
import ca.yorku.eecs3311.detourto.application.TripController;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

/**
 * Base for every tab of the window. Panels only talk to the {@link TripController} facade and
 * observe the event bus (Observer pattern). Slow work (LLM calls, planning) runs on a background
 * thread and the result is applied on the JavaFX thread, so the window never freezes.
 */
public abstract class BasePanel extends BorderPane implements EventListener {

    protected final TripController controller;
    protected final MainWindow window;

    protected BasePanel(TripController controller, MainWindow window) {
        this.controller = controller;
        this.window = window;
    }

    /** Events arrive on whatever thread published them; override to react (default: ignore). */
    @Override
    public void onEvent(TransitEvent e) {
    }

    /** Redraws the panel from the controller's current state. */
    public abstract void render();

    protected static void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /** Runs {@code work} off the FX thread, then {@code done} on it; errors go to the status bar. */
    protected <T> void runInBackground(String busyText, Supplier<T> work, Consumer<T> done) {
        window.setStatus(busyText);
        Thread t = new Thread(() -> {
            try {
                T result = work.get();
                runOnFxThread(() -> {
                    done.accept(result);
                    window.setStatus("Ready");
                });
            } catch (RuntimeException ex) {
                runOnFxThread(() -> window.setStatus("Error: " + ex.getMessage()));
            }
        }, "detour-worker");
        t.setDaemon(true);
        t.start();
    }

    protected static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }
}
