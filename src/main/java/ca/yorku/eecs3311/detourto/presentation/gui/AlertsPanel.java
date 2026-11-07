package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Features F04 and F05: current TTC alerts as structured disruptions (kind, routes, stops,
 * confidence, where the reading came from) and a Re-route button for the current trip.
 */
public final class AlertsPanel extends BasePanel {

    private final TableView<Disruption> alertTable = new TableView<>();
    private final Label info = new Label();

    public AlertsPanel(TripController controller, MainWindow window) {
        super(controller, window);
        setPadding(new Insets(10));
        alertTable.getColumns().add(column("Kind", d -> d.kind().name()));
        alertTable.getColumns().add(column("What riders need to know", Disruption::summary));
        alertTable.getColumns().add(column("Routes", d -> d.affectedRouteIds().stream().sorted()
                .map(r -> controller.network().findRoute(r).map(x -> x.label()).orElse(r)).collect(Collectors.joining(", "))));
        alertTable.getColumns().add(column("Closed stops", d -> d.sortedStopIds().stream()
                .map(id -> controller.network().findStop(id).map(s -> s.name().replace(" Station", "")).orElse(id))
                .collect(Collectors.joining(", "))));
        alertTable.getColumns().add(column("Confidence", d -> Math.round(d.confidence() * 100) + "%"));
        alertTable.getColumns().add(column("Read by", d -> d.confidence() == 0 ? "not interpreted (text only)"
                : d.llmDerived() ? "AI (validated)" : "structured fields"));
        alertTable.getColumns().add(column("Used for routing", d -> d.appliesByDefault() ? "yes"
                : d.affectsRouting() ? "only if you choose" : "no"));
        double[] widths = {110, 420, 150, 220, 80, 150, 120};
        for (int i = 0; i < widths.length; i++) {
            alertTable.getColumns().get(i).setPrefWidth(widths[i]);
        }
        alertTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        alertTable.setRowFactory(t -> {
            javafx.scene.control.TableRow<Disruption> row = new javafx.scene.control.TableRow<>();
            row.itemProperty().addListener((o, a, d) -> row.setTooltip(d == null ? null
                    : new javafx.scene.control.Tooltip(d.summary())));
            return row;
        });
        alertTable.setPlaceholder(new Label("No active alerts. Press Refresh."));

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> onRefreshClicked());
        Button reroute = new Button("Re-route my trip around this");
        reroute.setOnAction(e -> onRerouteClicked(alertTable.getSelectionModel().getSelectedItem()));
        setTop(new VBox(6, heading("Service alerts"), new HBox(8, refresh, reroute, info)));
        setCenter(alertTable);
        BorderPaneMargins.top(alertTable);
    }

    public void onRefreshClicked() {
        runInBackground("Reading TTC alerts...", () -> controller.getAlerts(LocalDateTime.now(TripPlannerPanel.TORONTO)),
                this::show);
    }

    public void onRerouteClicked(Disruption d) {
        if (d == null) {
            info.setText("Select an alert first.");
            return;
        }
        if (controller.currentQuery().isEmpty()) {
            info.setText("Plan a trip on the Trip Planner tab first.");
            return;
        }
        runInBackground("Re-routing around " + d.id() + "...", () -> controller.rerouteAround(d.id()),
                r -> window.showPlanner().showResult(r));
    }

    private void show(List<Disruption> ds) {
        alertTable.getItems().setAll(ds);
        info.setText(ds.size() + " active alert" + (ds.size() == 1 ? "" : "s") + " at "
                + LocalDateTime.now(TripPlannerPanel.TORONTO).toLocalTime().withNano(0).withSecond(0));
    }

    @Override
    public void render() {
        onRefreshClicked();
    }

    private static TableColumn<Disruption, String> column(String title, java.util.function.Function<Disruption, String> f) {
        TableColumn<Disruption, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(f.apply(cell.getValue())));
        return c;
    }
}
