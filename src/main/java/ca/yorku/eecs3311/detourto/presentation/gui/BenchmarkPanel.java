package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.domain.BenchmarkReport;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.infrastructure.ScenarioLoader;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Features F12 and F13: replay disruption scenarios with metrics, and refresh the TTC schedule. */
public final class BenchmarkPanel extends BasePanel {

    private final TextField scenarioPath = new TextField("scenarios");
    private final TableView<BenchmarkReport> resultsTable = new TableView<>();
    private final Label summary = new Label();
    private final TextField dataFile = new TextField();
    private final TextArea refreshReport = new TextArea();

    public BenchmarkPanel(TripController controller, MainWindow window) {
        super(controller, window);
        setPadding(new Insets(10));
        Button run = new Button("Run replay");
        run.setOnAction(e -> onRunReplayClicked(Path.of(scenarioPath.getText().strip())));
        HBox.setHgrow(scenarioPath, Priority.ALWAYS);
        resultsTable.getColumns().add(col("Scenario", BenchmarkReport::scenario));
        resultsTable.getColumns().add(col("Closures matched", r -> yes(r.disruptionMatched())));
        resultsTable.getColumns().add(col("Route found", r -> yes(r.feasible())));
        resultsTable.getColumns().add(col("Extra minutes", r -> String.valueOf(r.arrivalDelayMinutes())));
        resultsTable.getColumns().add(col("Grounding violations", r -> String.valueOf(r.groundingViolations())));
        resultsTable.getColumns().add(col("LLM calls", r -> String.valueOf(r.llmCalls())));
        resultsTable.getColumns().add(col("ms", r -> String.valueOf(r.latencyMs())));
        resultsTable.getColumns().add(col("Error", r -> r.error() == null ? "" : r.error()));
        resultsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        resultsTable.setPlaceholder(new Label("Run a replay to see results."));
        summary.getStyleClass().add("message");

        dataFile.setPromptText("optional: local GTFS zip or folder (blank = download from Toronto Open Data)");
        HBox.setHgrow(dataFile, Priority.ALWAYS);
        Button refresh = new Button("Refresh transit data");
        refresh.setOnAction(e -> onRefreshDataClicked());
        refreshReport.setEditable(false);
        refreshReport.setPrefRowCount(6);

        VBox top = new VBox(8, heading("Disruption replay"), new HBox(8, new Label("Scenario file or folder"), scenarioPath, run));
        VBox bottom = new VBox(8, summary, heading("Transit data"), new HBox(8, dataFile, refresh), refreshReport);
        bottom.setPadding(new Insets(8, 0, 0, 0));
        setTop(top);
        setCenter(resultsTable);
        setBottom(bottom);
        BorderPaneMargins.top(resultsTable);
    }

    public void onRunReplayClicked(Path file) {
        runInBackground("Replaying scenarios...", () -> {
            try {
                return controller.runReplay(new ScenarioLoader().load(file));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }, this::show);
    }

    private void show(List<BenchmarkReport> reports) {
        resultsTable.getItems().setAll(reports);
        long matched = reports.stream().filter(BenchmarkReport::passed).count();
        int violations = reports.stream().mapToInt(BenchmarkReport::groundingViolations).sum();
        int calls = reports.stream().mapToInt(BenchmarkReport::llmCalls).sum();
        summary.setText(matched + " of " + reports.size() + " scenarios matched the expected closures; "
                + violations + " grounding violations caught; " + calls + " LLM calls.");
    }

    private void onRefreshDataClicked() {
        String f = dataFile.getText().strip();
        AppConfig cfg = AppConfig.load();
        runInBackground(f.isEmpty() ? "Downloading TTC schedule..." : "Loading " + f + "...",
                () -> controller.refreshTransitData(f.isEmpty() ? null : Path.of(f), cfg.gtfsDownloadUrl(),
                        Path.of("data/local/ttc-gtfs.zip")),
                r -> refreshReport.setText((r.report() == null ? "" : r.report().summary()) + r.message()));
    }

    @Override
    public void render() {
        // results appear after a run
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static TableColumn<BenchmarkReport, String> col(String title,
                                                           java.util.function.Function<BenchmarkReport, String> f) {
        TableColumn<BenchmarkReport, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(f.apply(cell.getValue())));
        return c;
    }
}
