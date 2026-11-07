package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Arrival;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Feature F08: next departures at a stop, live for buses and streetcars, refreshed every 30 s. */
public final class ArrivalsPanel extends BasePanel {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final TextField stopField = new TextField();
    private final TextField timeField = new TextField();
    private final TableView<Arrival> table = new TableView<>();
    private final Label note = new Label("Type a stop and press Show.");
    private final Timeline refresher;
    private String lastQuery;

    public ArrivalsPanel(TripController controller, MainWindow window) {
        super(controller, window);
        setPadding(new Insets(10));
        stopField.setPromptText("Stop or station, e.g. Dufferin, Spadina & College, home");
        stopField.setPrefColumnCount(30);
        timeField.setPromptText("time (blank = now)");
        timeField.setPrefColumnCount(8);
        stopField.setOnAction(e -> onStopSearched(stopField.getText()));
        Button show = new Button("Show");
        show.setOnAction(e -> onStopSearched(stopField.getText()));
        table.getColumns().add(col("Time", a -> HM.format(a.predicted())));
        table.getColumns().add(col("Route", a -> a.route().label()));
        table.getColumns().add(col("Towards", Arrival::headsign));
        table.getColumns().add(col("Status", a -> !a.realtime() ? "scheduled"
                : a.delaySeconds() >= 60 ? "late " + Math.round(a.delaySeconds() / 60.0) + " min"
                : a.delaySeconds() <= -60 ? "early " + Math.round(-a.delaySeconds() / 60.0) + " min" : "on time"));
        table.getColumns().add(col("Scheduled", a -> HM.format(a.scheduled())));
        table.getColumns().add(col("Platform / bay", a -> a.stop().name()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No departures to show."));
        note.getStyleClass().add("message");
        setTop(new VBox(6, heading("Stop arrivals"), new HBox(8, new Label("Stop"), stopField, new Label("At"), timeField, show), note));
        setCenter(table);
        BorderPaneMargins.top(table);
        refresher = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            if (lastQuery != null) {
                onStopSearched(lastQuery);
            }
        }));
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();
    }

    public void onStopSearched(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        lastQuery = query;
        LocalDateTime at = time();
        runInBackground("Loading arrivals...", () -> controller.getArrivals(query, at, 12), r -> {
            if (r.board() == null) {
                table.getItems().clear();
                note.setText(r.clarification().question());
                return;
            }
            table.getItems().setAll(r.board().arrivals());
            note.setText(r.board().stop().name() + " at " + HM.format(at) + ". " + r.board().note());
        });
    }

    private LocalDateTime time() {
        String t = timeField.getText().strip();
        LocalDateTime now = LocalDateTime.now(TripPlannerPanel.TORONTO).withSecond(0).withNano(0);
        if (t.isEmpty()) {
            return now;
        }
        try {
            return now.toLocalDate().atTime(java.time.LocalTime.parse(t.length() == 4 ? "0" + t : t));
        } catch (RuntimeException e) {
            return now;
        }
    }

    @Override
    public void render() {
        // nothing until the rider searches
    }

    private static TableColumn<Arrival, String> col(String title, java.util.function.Function<Arrival, String> f) {
        TableColumn<Arrival, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(f.apply(cell.getValue())));
        return c;
    }
}
