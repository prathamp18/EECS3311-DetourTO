package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.CommuteScheduler;
import ca.yorku.eecs3311.detourto.application.EventType;
import ca.yorku.eecs3311.detourto.application.TransitEvent;
import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Features F09 and F11: saved places, preferences (set directly or learned from a sentence), and
 * commutes with today's leave-by times. Checks commutes every minute and shows leave-by alerts.
 */
public final class CommutePanel extends BasePanel {

    private final ListView<String> placesList = new ListView<>();
    private final TextField placeLabel = new TextField();
    private final TextField placeText = new TextField();
    private final ComboBox<String> strategyBox = new ComboBox<>();
    private final CheckBox stepFree = new CheckBox("Always plan step-free trips");
    private final TextField avoidedRoutes = new TextField();
    private final TextField learnText = new TextField();
    private final Label profileMessage = new Label();

    private final GridPane commuteForm = new GridPane();
    private final TextField name = new TextField();
    private final TextField from = new TextField();
    private final TextField to = new TextField();
    private final TextField arriveBy = new TextField("09:00");
    private final TextField days = new TextField("weekdays");
    private final TextField warn = new TextField("20");
    private final ListView<String> commuteList = new ListView<>();
    private final ListView<String> alertsList = new ListView<>();
    private final Label commuteMessage = new Label();

    public CommutePanel(TripController controller, MainWindow window) {
        super(controller, window);
        setPadding(new Insets(10));

        // --- profile (left)
        placeLabel.setPromptText("label, e.g. home");
        placeText.setPromptText("place, e.g. Keele");
        Button savePlace = new Button("Save place");
        savePlace.setOnAction(e -> onSavePlaceClicked());
        Button removePlace = new Button("Remove selected");
        removePlace.setOnAction(e -> onRemovePlaceClicked());
        strategyBox.getItems().addAll("fastest", "fewest-transfers", "least-walking", "accessible");
        avoidedRoutes.setPromptText("routes to avoid, e.g. 29, 510");
        Button savePrefs = new Button("Save preferences");
        savePrefs.setOnAction(e -> onSavePreferencesClicked());
        learnText.setPromptText("Tell me a preference, e.g. \"never put me on the 29\"");
        Button learn = new Button("Learn");
        learn.setOnAction(e -> onLearnClicked());
        HBox.setHgrow(learnText, Priority.ALWAYS);
        profileMessage.getStyleClass().add("message");
        VBox profile = new VBox(8, heading("Saved places"), placesList, new HBox(6, placeLabel, placeText, savePlace),
                removePlace, heading("Preferences"), new HBox(6, new Label("Default ranking"), strategyBox), stepFree,
                new HBox(6, new Label("Avoid routes"), avoidedRoutes), savePrefs, new HBox(6, learnText, learn), profileMessage);
        placesList.setPrefHeight(140);
        profile.getStyleClass().add("card");

        // --- commutes (right)
        commuteForm.setHgap(6);
        commuteForm.setVgap(6);
        String[] labels = {"Name", "From", "To", "Arrive by", "Days", "Warn (min before)"};
        TextField[] fields = {name, from, to, arriveBy, days, warn};
        for (int i = 0; i < labels.length; i++) {
            commuteForm.add(new Label(labels[i]), 0, i);
            commuteForm.add(fields[i], 1, i);
        }
        from.setPromptText("home");
        to.setPromptText("school");
        days.setPromptText("weekdays, weekends, daily, mon,wed,fri");
        Button saveCommute = new Button("Save commute");
        saveCommute.setOnAction(e -> onSaveCommuteClicked());
        Button removeCommute = new Button("Remove selected");
        removeCommute.setOnAction(e -> onRemoveCommuteClicked());
        Button check = new Button("Check now");
        check.setOnAction(e -> render());
        commuteMessage.getStyleClass().add("message");
        VBox commutes = new VBox(8, heading("Commutes"), commuteForm, new HBox(6, saveCommute, removeCommute, check),
                commuteMessage, heading("Today"), commuteList, heading("Leave-by alerts"), alertsList);
        commuteList.setPrefHeight(160);
        alertsList.setPrefHeight(100);
        commutes.getStyleClass().add("card");
        HBox.setHgrow(commutes, Priority.ALWAYS);
        HBox.setHgrow(profile, Priority.ALWAYS);
        setCenter(new HBox(12, profile, commutes));

        Timeline everyMinute = new Timeline(new KeyFrame(Duration.minutes(1), e -> checkDue()));
        everyMinute.setCycleCount(Timeline.INDEFINITE);
        everyMinute.play();
    }

    // ------------------------------------------------------------------ profile

    public void onSavePlaceClicked() {
        if (placeLabel.getText().isBlank() || placeText.getText().isBlank()) {
            profileMessage.setText("Enter a label and a place.");
            return;
        }
        var r = controller.savePlace(placeLabel.getText(), placeText.getText());
        profileMessage.setText(r.stop().map(s -> "Saved \"" + RiderProfile.normalize(placeLabel.getText()) + "\" = " + s.name())
                .orElseGet(() -> r.clarification().question()));
        placeLabel.clear();
        placeText.clear();
        render();
    }

    private void onRemovePlaceClicked() {
        String sel = placesList.getSelectionModel().getSelectedItem();
        if (sel != null) {
            controller.updateProfile(List.of(new PreferenceUpdate(PreferenceUpdate.FORGET_PLACE, sel.split(" ")[0])));
            render();
        }
    }

    private void onSavePreferencesClicked() {
        RiderProfile p = controller.profile();
        List<PreferenceUpdate> updates = new ArrayList<>();
        updates.add(new PreferenceUpdate(PreferenceUpdate.DEFAULT_STRATEGY, strategyBox.getValue()));
        updates.add(new PreferenceUpdate(PreferenceUpdate.STEP_FREE, String.valueOf(stepFree.isSelected())));
        List<String> wanted = new ArrayList<>();
        for (String r : avoidedRoutes.getText().split("[,\\s]+")) {
            if (!r.isBlank()) {
                if (controller.network().findRoute(r).isEmpty()) {
                    profileMessage.setText("There is no route " + r + ".");
                    return;
                }
                wanted.add(r);
            }
        }
        p.avoidedRouteIds().stream().filter(r -> !wanted.contains(r))
                .forEach(r -> updates.add(new PreferenceUpdate(PreferenceUpdate.ALLOW_ROUTE, r)));
        wanted.forEach(r -> updates.add(new PreferenceUpdate(PreferenceUpdate.AVOID_ROUTE, r)));
        controller.updateProfile(updates);
        profileMessage.setText("Preferences saved.");
        render();
    }

    private void onLearnClicked() {
        String text = learnText.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        runInBackground("Learning preferences...", () -> controller.learnPreferences(text,
                LocalDateTime.now(TripPlannerPanel.TORONTO)), learned -> {
                    profileMessage.setText(learned.isEmpty() ? "No lasting preference found in that sentence."
                            : "Learned: " + String.join("; ", learned));
                    learnText.clear();
                    render();
                });
    }

    // ------------------------------------------------------------------ commutes

    public void onSaveCommuteClicked() {
        try {
            var problem = controller.saveCommute(name.getText().strip(), from.getText(), to.getText(),
                    LocalTime.parse(arriveBy.getText().strip().length() == 4 ? "0" + arriveBy.getText().strip()
                            : arriveBy.getText().strip()), Commute.parseDays(days.getText()),
                    Integer.parseInt(warn.getText().strip()));
            commuteMessage.setText(problem.map(c -> c.question()).orElse("Saved commute \"" + name.getText().strip() + "\"."));
        } catch (RuntimeException e) {
            commuteMessage.setText("Check the form: " + e.getMessage());
        }
        render();
    }

    private void onRemoveCommuteClicked() {
        String sel = commuteList.getSelectionModel().getSelectedItem();
        if (sel != null) {
            controller.commutes().remove(sel.substring(0, sel.indexOf(':')));
            render();
        }
    }

    private void checkDue() {
        runInBackground("Checking commutes...", () -> controller.commutes().checkDue(
                LocalDateTime.now(TripPlannerPanel.TORONTO)), due -> render());
    }

    @Override
    public void onEvent(TransitEvent e) {
        if (e.type() == EventType.LEAVE_BY_ALERT) {
            runOnFxThread(() -> alertsList.getItems().add(0, e.timestamp().atZone(TripPlannerPanel.TORONTO)
                    .toLocalTime().withNano(0) + "  " + e.payload().get("commute") + ": " + e.payload().get("message")));
        }
    }

    @Override
    public void render() {
        RiderProfile p = controller.profile();
        List<String> places = new ArrayList<>();
        for (Map.Entry<String, String> e : p.savedPlacesInOrder().entrySet()) {
            places.add(e.getKey() + "  =  " + controller.network().findStop(e.getValue()).map(s -> s.name()).orElse(e.getValue()));
        }
        placesList.getItems().setAll(places);
        strategyBox.setValue(p.defaultStrategy().cliName());
        stepFree.setSelected(p.needsStepFree());
        avoidedRoutes.setText(String.join(", ", p.avoidedRouteIds().stream().sorted().toList()));
        LocalDateTime now = LocalDateTime.now(TripPlannerPanel.TORONTO);
        runInBackground("Working out today's commutes...", () -> controller.commutes().today(now), statuses -> {
            List<String> rows = new ArrayList<>();
            for (Commute c : controller.commutes().commutes()) {
                String today = statuses.stream().filter(s -> s.commute().name().equals(c.name()))
                        .map(CommuteScheduler.Status::message).findFirst().orElse("not today");
                rows.add(c.name() + ": " + c.origin().name() + " -> " + c.destination().name() + ", arrive by " + c.arriveBy()
                        + " (" + c.daysText() + ")\n      " + today);
            }
            commuteList.getItems().setAll(rows);
        });
    }
}
