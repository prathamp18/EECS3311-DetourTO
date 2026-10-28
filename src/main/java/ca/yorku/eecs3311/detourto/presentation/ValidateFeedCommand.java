package ca.yorku.eecs3311.detourto.presentation;

import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.infrastructure.FeedValidationReport;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code detour validate-feed [--gtfs PATH]}: loads a GTFS feed and prints what was found. */
@Command(name = "validate-feed",
        mixinStandardHelpOptions = true,
        description = "Load a GTFS static feed (zip or folder) and report counts and problems.")
public class ValidateFeedCommand implements Callable<Integer> {

    @Option(names = "--gtfs", description = "GTFS zip or folder (default: GTFS_PATH from .env)")
    Path gtfs;

    @Override
    public Integer call() throws Exception {
        Path path = gtfs != null ? gtfs : AppConfig.load().gtfsPath();
        long start = System.nanoTime();
        FeedValidationReport report = new GtfsStaticLoader().validate(path);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("feed: " + path);
        System.out.print(report.summary());
        System.out.println("loaded in " + ms + " ms");
        return report.ok() ? 0 : 1;
    }
}
