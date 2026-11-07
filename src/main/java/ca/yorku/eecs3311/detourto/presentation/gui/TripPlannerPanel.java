package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.agent.Explanation;
import ca.yorku.eecs3311.detourto.agent.FollowUpRules;
import ca.yorku.eecs3311.detourto.agent.IntentKind;
import ca.yorku.eecs3311.detourto.application.ActiveTrip;
import ca.yorku.eecs3311.detourto.application.AtRiskState;
import ca.yorku.eecs3311.detourto.application.CompletedState;
import ca.yorku.eecs3311.detourto.application.PlanResult;
import ca.yorku.eecs3311.detourto.application.TransitEvent;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.Leg;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.domain.WalkLeg;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Features F01-F03, F05-F07 and F10 in one tab: ask in plain English or fill the form, see the
 * ranked options on a list and a map, change the strategy, ask follow-ups with undo/redo, get an
 * explanation, and start monitoring a trip.
 */
public final class TripPlannerPanel extends BasePanel {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final TextField chatInput = new TextField();
    private final TextField fromField = new TextField();
    private final TextField toField = new TextField();
    private final DatePicker datePicker = new DatePicker(LocalDate.now(TORONTO));
    private final TextField timeField = new TextField(LocalTime.now(TORONTO).withSecond(0).withNano(0).format(HM));
    private final ComboBox<String> timeType = new ComboBox<>();
    private final ComboBox<StrategyKind> strategyBox = new ComboBox<>();
    private final ListView<Itinerary> optionsList = new ListView<>();
    private final TextArea legsArea = new TextArea();
    private final TextArea explanationArea = new TextArea();
    private final Label message = new Label("Ask for a trip, or fill in From / To and press Plan.");
    private final Label historyLabel = new Label();
    private final Label tripState = new Label("No active trip");
    private final Button undoButton = new Button("Undo");
    private final Button redoButton = new Button("Redo");
    private final Button rerouteButton = new Button("Accept reroute");
    private final CheckBox fastReplay = new CheckBox("Replay trip fast (1 min per second)");
    private final MapPanel map;
    private final Timeline monitorTimer;
    private ActiveTrip activeTrip;
    private LocalDateTime monitorClock;
    private boolean updatingStrategy;

    public TripPlannerPanel(TripController controller, MainWindow window) {
        super(controller, window);
        map = new MapPanel(controller.network());
        setPadding(new Insets(10));

        chatInput.setPromptText("e.g. York U to Union by 10, no streetcars   |   then: leave 15 min later, avoid line 2, undo");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        Button send = new Button("Send");
        send.setDefaultButton(true);
        send.setOnAction(e -> onSendClicked());
        chatInput.setOnAction(e -> onSendClicked());
        HBox chat = new HBox(8, new Label("Ask:"), chatInput, send);

        fromField.setPromptText("From (stop, station, 'home', lat,lon)");
        toField.setPromptText("To");
        timeType.getItems().addAll("Depart at", "Arrive by");
        timeType.setValue("Depart at");
        timeField.setPrefColumnCount(5);
        strategyBox.getItems().addAll(StrategyKind.values());
        strategyBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(StrategyKind k) {
                return k == null ? "" : k.cliName().replace('-', ' ');
            }

            @Override
            public StrategyKind fromString(String text) {
                return StrategyKind.parse(text.replace(' ', '-'));
            }
        });
        strategyBox.setValue(controller.profile().needsStepFree() ? StrategyKind.ACCESSIBLE : controller.profile().defaultStrategy());
        strategyBox.setOnAction(e -> {
            if (!updatingStrategy) {
                onStrategyChanged(strategyBox.getValue());
            }
        });
        Button plan = new Button("Plan");
        plan.setOnAction(e -> onPlanFormSubmitted());
        HBox form = new HBox(8, new Label("From"), fromField, new Label("To"), toField, timeType, datePicker, timeField,
                new Label("Rank by"), strategyBox, plan);

        optionsList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Itinerary it, boolean empty) {
                super.updateItem(it, empty);
                setText(empty || it == null ? null : summary(it));
            }
        });
        optionsList.getSelectionModel().selectedItemProperty().addListener((o, a, it) -> showOption(it));
        legsArea.setEditable(false);
        legsArea.setWrapText(true);
        explanationArea.setEditable(false);
        explanationArea.setWrapText(true);
        explanationArea.getStyleClass().add("explanation");
        explanationArea.setPromptText("Press Explain to compare the options in plain language.");
        VBox left = new VBox(8, heading("Options"), optionsList, heading("Selected option"), legsArea,
                heading("Explanation"), explanationArea);
        VBox.setVgrow(optionsList, Priority.ALWAYS);
        legsArea.setPrefRowCount(7);
        explanationArea.setPrefRowCount(6);
        SplitPane split = new SplitPane(left, map);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);

        undoButton.setOnAction(e -> onUndoClicked());
        redoButton.setOnAction(e -> onRedoClicked());
        Button explain = new Button("Explain");
        explain.setOnAction(e -> onExplainClicked());
        Button start = new Button("Start trip");
        start.setOnAction(e -> onStartTripClicked());
        rerouteButton.setOnAction(e -> onAcceptRerouteClicked());
        rerouteButton.setDisable(true);
        tripState.getStyleClass().add("state");
        HBox actions = new HBox(8, undoButton, redoButton, explain, start, fastReplay, rerouteButton, tripState);
        message.getStyleClass().add("message");
        VBox bottom = new VBox(6, actions, message, historyLabel);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        VBox top = new VBox(8, chat, form);
        top.setPadding(new Insets(0, 0, 8, 0));
        setTop(top);
        setCenter(split);
        setBottom(bottom);

        monitorTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickMonitor()));
        monitorTimer.setCycleCount(Timeline.INDEFINITE);
    }

    // ------------------------------------------------------------------ actions

    /** Chat box: a follow-up when a trip exists and it reads like one, otherwise a new request. */
    public void onSendClicked() {
        String text = chatInput.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        chatInput.clear();
        LocalDateTime now = formTime();
        boolean followUp = controller.currentQuery().isPresent() && FollowUpRules.parse(text).kind() != IntentKind.UNKNOWN;
        runInBackground(followUp ? "Applying: " + text : "Planning: " + text,
                () -> followUp ? controller.followUp(text, now) : controller.planFromText(text, now), this::showResult);
    }

    public void onPlanFormSubmitted() {
        if (fromField.getText().isBlank() || toField.getText().isBlank()) {
            message.setText("Enter both From and To.");
            return;
        }
        LocalDateTime when = formTime();
        runInBackground("Planning...", () -> {
            var o = controller.resolvePlace(fromField.getText());
            var d = controller.resolvePlace(toField.getText());
            if (o.stop().isEmpty()) {
                return PlanResult.needsClarification(o.clarification());
            }
            if (d.stop().isEmpty()) {
                return PlanResult.needsClarification(d.clarification());
            }
            TripQuery q = timeType.getValue().startsWith("Arrive")
                    ? TripQuery.arriveBy(o.stop().get(), d.stop().get(), when)
                    : TripQuery.departAt(o.stop().get(), d.stop().get(), when);
            q.setStrategyKind(strategyBox.getValue());
            controller.applyProfile(q, true);
            return controller.planWithDisruptions(q, Set.of(), false);
        }, this::showResult);
    }

    public void onStrategyChanged(StrategyKind kind) {
        if (controller.currentQuery().isEmpty()) {
            return;
        }
        runInBackground("Re-ranking...", () -> controller.setStrategy(kind), this::showResult);
    }

    public void onUndoClicked() {
        runInBackground("Undo...", controller::undo, this::showResult);
    }

    public void onRedoClicked() {
        runInBackground("Redo...", controller::redo, this::showResult);
    }

    public void onExplainClicked() {
        if (controller.lastOptions().isEmpty()) {
            explanationArea.setText("Plan a trip first.");
            return;
        }
        runInBackground("Explaining options...", () -> controller.explain(LocalDateTime.now(TORONTO)), this::showExplanation);
    }

    public void onStartTripClicked() {
        Itinerary chosen = optionsList.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            message.setText("Select an option to start.");
            return;
        }
        controller.startTrip(chosen.id()).ifPresent(t -> {
            activeTrip = t;
            monitorClock = fastReplay.isSelected() ? t.itinerary().departure().minusMinutes(5)
                    : LocalDateTime.now(TORONTO);
            monitorTimer.playFromStart();
            renderTrip();
        });
    }

    public void onAcceptRerouteClicked() {
        if (activeTrip == null || activeTrip.rerouteOptions().isEmpty()) {
            return;
        }
        Itinerary best = activeTrip.rerouteOptions().get(0);
        controller.acceptReroute(activeTrip.id(), best.id());
        map.drawItinerary(activeTrip.itinerary());
        renderTrip();
    }

    // ------------------------------------------------------------------ results

    /** Shows a plan result (also used by the Alerts tab after a reroute). */
    public void showResult(PlanResult r) {
        if (r.needsClarification()) {
            message.setText(r.clarification().question());
            return;
        }
        message.setText(r.message());
        if (r.query() != null) {
            TripQuery q = r.query();
            fromField.setText(q.origin().name());
            toField.setText(q.destination().name());
            timeType.setValue(q.isArriveBy() ? "Arrive by" : "Depart at");
            datePicker.setValue(q.time().toLocalDate());
            timeField.setText(HM.format(q.time()));
            updatingStrategy = true;
            strategyBox.setValue(q.strategyKind());
            updatingStrategy = false;
        }
        explanationArea.clear();
        optionsList.getItems().setAll(r.options());
        if (!r.options().isEmpty()) {
            optionsList.getSelectionModel().select(0);
        } else {
            showOption(null);
        }
        map.highlightDisruptions(r.disruptionsApplied());
        render();
    }

    private void showExplanation(Explanation e) {
        StringBuilder sb = new StringBuilder(e.llmGenerated() ? "" : "(computed comparison, no AI)\n");
        sb.append(e.summary()).append("\n");
        e.optionNotes().forEach((id, note) -> sb.append("\n").append(id).append(": ").append(note));
        explanationArea.setText(sb.toString());
    }

    private void showOption(Itinerary it) {
        map.drawItinerary(it);
        if (it == null) {
            legsArea.clear();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Leg leg : it.legs()) {
            sb.append(HM.format(leg.start())).append("  ");
            if (leg instanceof RideLeg r) {
                sb.append(r.route().label()).append(" towards ").append(r.trip().headsign()).append(": ")
                        .append(r.from().name()).append(" -> ").append(r.to().name())
                        .append(" (").append(r.stopCount()).append(" stops)");
            } else {
                WalkLeg w = (WalkLeg) leg;
                sb.append(w.meters() == 0 ? "Change to " + w.to().name() : "Walk " + w.meters() + " m to " + w.to().name());
            }
            sb.append("\n");
        }
        sb.append(HM.format(it.arrival())).append("  Arrive");
        legsArea.setText(sb.toString());
    }

    @Override
    public void render() {
        undoButton.setDisable(!controller.history().canUndo());
        redoButton.setDisable(!controller.history().canRedo());
        List<String> applied = controller.history().applied();
        historyLabel.setText(applied.isEmpty() ? "" : "Changes: " + String.join("  ->  ", applied));
    }

    @Override
    public void onEvent(TransitEvent e) {
        // the status bar shows events; this panel only needs to refresh its buttons
        runOnFxThread(this::render);
    }

    // ------------------------------------------------------------------ monitoring

    private void tickMonitor() {
        if (activeTrip == null) {
            return;
        }
        monitorClock = fastReplay.isSelected() ? monitorClock.plusMinutes(1) : LocalDateTime.now(TORONTO);
        LocalDateTime at = monitorClock;
        runInBackground("Checking trip at " + HM.format(at) + "...", () -> {
            controller.monitor().poll(at);
            return activeTrip;
        }, t -> renderTrip());
    }

    private void renderTrip() {
        if (activeTrip == null) {
            return;
        }
        String state = activeTrip.getState().name();
        tripState.setText(activeTrip.id() + ": " + state.replace('_', ' ') + "   arrive "
                + HM.format(activeTrip.projectedArrival()) + (monitorClock == null ? "" : "   (" + HM.format(monitorClock) + ")"));
        tripState.getStyleClass().removeAll("state-at-risk", "state-completed", "state-rerouted");
        if (activeTrip.getState() instanceof AtRiskState) {
            tripState.getStyleClass().add("state-at-risk");
            if (activeTrip.lastRisk() != null) {
                message.setText("At risk: " + activeTrip.lastRisk().reason()
                        + (activeTrip.rerouteOptions().isEmpty() ? "" : "   Reroute: " + summary(activeTrip.rerouteOptions().get(0))));
            }
        } else if (activeTrip.getState() instanceof CompletedState) {
            tripState.getStyleClass().add("state-completed");
            monitorTimer.stop();
        } else if (state.equals("REROUTED")) {
            tripState.getStyleClass().add("state-rerouted");
        }
        rerouteButton.setDisable(!(activeTrip.getState() instanceof AtRiskState) || activeTrip.rerouteOptions().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private LocalDateTime formTime() {
        LocalDate d = datePicker.getValue() == null ? LocalDate.now(TORONTO) : datePicker.getValue();
        try {
            String t = timeField.getText().strip();
            return d.atTime(LocalTime.parse(t.length() == 4 ? "0" + t : t));
        } catch (RuntimeException e) {
            return LocalDateTime.now(TORONTO).withSecond(0).withNano(0);
        }
    }

    static String summary(Itinerary it) {
        String routes = String.join(", ", it.rideLegs().stream().map(l -> l.route().label()).distinct().toList());
        return String.format("%s   %s -> %s   %d min   %d transfer%s   %d m walk   %s", it.id(), HM.format(it.departure()),
                HM.format(it.arrival()), it.durationMinutes(), it.transfers(), it.transfers() == 1 ? "" : "s",
                it.walkMeters(), routes.isEmpty() ? "walk" : routes);
    }
}
