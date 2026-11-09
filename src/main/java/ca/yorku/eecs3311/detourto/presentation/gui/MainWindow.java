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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.Set;
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

    /** Buttons that start the main action of a tab get the accent colour. */
    private static final Set<String> PRIMARY = Set.of("Plan", "Send", "Show", "Run replay", "Save commute",
            "Check now", "Refresh transit data", "Re-route my trip around this", "Start trip", "Learn", "Save place",
            "Save preferences", "Accept reroute", "Refresh");

    private final Label status = new Label("Ready");
    private final Label alertsChip = new Label("Alerts: -");
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
        root.setTop(header(controller));
        root.setBottom(bar);
        markPrimary(root);
        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(MainWindow.class.getResource("detour.css").toExternalForm());
        stage.setTitle("DetourTO - disruption-aware TTC trip planner");
        stage.setScene(scene);
        stage.show();
        panels.forEach(BasePanel::render);
    }

    /** App header: logo, name, and chips showing the AI mode, the network and live alerts. */
    private Node header(TripController controller) {
        Label logo = new Label("TO");
        logo.getStyleClass().add("logo-text");
        StackPane badge = new StackPane(logo);
        badge.getStyleClass().add("logo");
        Label title = new Label("DetourTO");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Disruption-aware TTC trip planner  ·  AI agent + RAPTOR planner");
        subtitle.getStyleClass().add("app-subtitle");
        VBox names = new VBox(0, title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label ai = chip(controller.hasAgent() ? "AI agent: Gemini" : "AI: offline rules",
                controller.hasAgent() ? "chip-ai" : "chip-off");
        Label net = chip(controller.network().routes().size() + " routes  ·  "
                + controller.network().stops().size() + " stops", "chip-net");
        alertsChip.getStyleClass().addAll("chip", "chip-alert");
        HBox top = new HBox(12, badge, names, spacer, ai, net, alertsChip);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("app-header");
        HBox stripe = new HBox();
        for (String c : new String[] {"line1", "line2", "line3", "line4", "line5"}) {
            Region r = new Region();
            r.getStyleClass().addAll("stripe", c);
            HBox.setHgrow(r, Priority.ALWAYS);
            stripe.getChildren().add(r);
        }
        return new VBox(top, stripe);
    }

    private static Label chip(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", style);
        return l;
    }

    private static void markPrimary(Node n) {
        if (n instanceof Button b && PRIMARY.contains(b.getText())) {
            b.getStyleClass().add("primary");
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                markPrimary(c);
            }
        }
        if (n instanceof TabPane t) {
            t.getTabs().forEach(tab -> markPrimary(tab.getContent()));
        }
    }

    private List<BasePanel> extraPanels(TripController controller) {
        return List.of(new AlertsPanel(controller, this), new ArrivalsPanel(controller, this),
                new CommutePanel(controller, this), new BenchmarkPanel(controller, this));
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
            case ALERTS_UPDATED -> {
                Object n = e.payload().get("count");
                BasePanel.runOnFxThread(() -> alertsChip.setText("Alerts: " + n + " active"));
                yield n + " active alerts";
            }
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
