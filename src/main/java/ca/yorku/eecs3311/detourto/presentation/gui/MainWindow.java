package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.EventType;
import ca.yorku.eecs3311.detourto.application.TransitEvent;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * The desktop window ({@code detour gui}): one tab per feature area, a status bar that shows
 * every agent step and trip event as it happens (Observer), and one shared TripController.
 */
public class MainWindow extends Application {

    /** Set by {@code detour gui} before launch; tests or demos can replace it. */
    static Supplier<TripController> controllerFactory = () -> {
        try {
            return TripController.fromConfig(AppConfig.load());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    };

    private final Label status = new Label("Ready");
    private final List<BasePanel> panels = new ArrayList<>();
    private TabPane tabs;
    private TripPlannerPanel planner;

    public static void launchWith(Supplier<TripController> factory) {
        controllerFactory = factory;
        Application.launch(MainWindow.class);
    }

    @Override
    public void start(Stage stage) {
        TripController controller = controllerFactory.get();
        planner = new TripPlannerPanel(controller, this);
        panels.add(planner);
        panels.addAll(extraPanels(controller));
        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        String[] names = {"Trip Planner", "Alerts", "Arrivals", "Commute & Profile", "Benchmark"};
        for (int i = 0; i < panels.size(); i++) {
            tabs.getTabs().add(new Tab(names[i], panels.get(i)));
            controller.eventBus().subscribeAll(panels.get(i));
        }
        controller.eventBus().subscribeAll(this::showEvent);

        HBox bar = new HBox(status);
        bar.getStyleClass().add("status-bar");
        BorderPane root = new BorderPane(tabs);
        root.setBottom(bar);
        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(MainWindow.class.getResource("detour.css").toExternalForm());
        stage.setTitle("DetourTO - disruption-aware TTC trip planner");
        stage.setScene(scene);
        stage.show();
        panels.forEach(BasePanel::render);
    }

    /** Panels added in later steps (alerts, arrivals, commute, benchmark). */
    protected List<BasePanel> extraPanels(TripController controller) {
        return List.of();
    }

    public void setStatus(String text) {
        BasePanel.runOnFxThread(() -> status.setText(text));
    }

    /** Switches to the Trip Planner tab (e.g. after "Re-route" on the Alerts tab). */
    public TripPlannerPanel showPlanner() {
        BasePanel.runOnFxThread(() -> tabs.getSelectionModel().select(0));
        return planner;
    }

    private void showEvent(TransitEvent e) {
        String text = switch (e.type()) {
            case AGENT_STEP -> e.payload().get("agent") + ": " + e.payload().get("kind") + " - " + e.payload().get("detail");
            case TRIP_AT_RISK -> "Trip at risk: " + e.payload().get("reason");
            case TRIP_STATE_CHANGED -> "Trip " + e.payload().get("from") + " -> " + e.payload().get("to");
            case LEAVE_BY_ALERT -> "Commute " + e.payload().get("commute") + ": " + e.payload().get("message");
            case ALERTS_UPDATED -> e.payload().get("count") + " active alerts";
            case ITINERARY_UPDATED -> "Options updated";
        };
        setStatus(text);
        if (e.type() == EventType.LEAVE_BY_ALERT || e.type() == EventType.TRIP_AT_RISK) {
            Platform.runLater(() -> Notifications.show(text));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
