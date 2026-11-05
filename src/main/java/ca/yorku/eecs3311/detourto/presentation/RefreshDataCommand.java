package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.application.TripController;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code detour refresh-data [--file PATH | --url URL]} (F13): downloads the latest TTC schedule
 * from Toronto Open Data (or reads a local zip), validates it, and swaps it in only if valid.
 */
@Command(name = "refresh-data",
        mixinStandardHelpOptions = true,
        description = "Download, validate and load the latest TTC schedule.",
        footer = {"", "Exit codes: 0 new data loaded, 1 kept the current data (download or validation failed)."})
public class RefreshDataCommand implements Callable<Integer> {

    @Option(names = "--file", description = "Use this GTFS zip or folder instead of downloading")
    Path file;

    @Option(names = "--url", description = "Download URL (default: TTC_GTFS_URL, the Toronto Open Data package)")
    String url;

    @Option(names = "--to", defaultValue = "data/local/ttc-gtfs.zip",
            description = "Where to save the download (default: ${DEFAULT-VALUE})")
    Path downloadTo;

    /** For tests. */
    TripController controller;

    @Override
    public Integer call() throws Exception {
        AppConfig cfg = AppConfig.load();
        TripController c = controller != null ? controller : TripController.fromConfig(cfg);
        int before = c.network().stopCount();
        if (file == null) {
            System.out.println("Downloading " + (url != null ? url : cfg.gtfsDownloadUrl()) + " ...");
        }
        TripController.RefreshResult r = c.refreshTransitData(file, url != null ? url : cfg.gtfsDownloadUrl(), downloadTo);
        if (r.report() != null) {
            System.out.print(r.report().summary());
        }
        System.out.println(r.message());
        if (r.swapped()) {
            System.out.println("Stops: " + before + " -> " + c.network().stopCount());
            if (file == null) {
                System.out.println("To use it every time, set GTFS_PATH=" + downloadTo + " in .env");
            }
            return 0;
        }
        return 1;
    }
}
