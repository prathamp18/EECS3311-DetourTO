package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Adapter pattern: reads TTC GTFS-Realtime protobuf feeds and exposes them as domain objects
 * ({@link ServiceAlert}, {@link RealtimeSnapshot}).
 *
 * <p>The base location is either an https URL ({@code https://bustime.ttc.ca/gtfsrt}, which has
 * {@code /alerts} and {@code /trips}) or a local folder with {@code alerts.pb} and
 * {@code trips.pb} (the recorded sample feed used for tests, demos and replays).
 */
public final class GtfsRealtimeAdapter implements RealtimeFeed {

    private final String base;
    private final HttpClient http;
    private final Clock clock;
    private final GtfsRealtimeDecoder decoder = new GtfsRealtimeDecoder();

    public GtfsRealtimeAdapter(String base) {
        this(base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Clock.systemUTC());
    }

    public GtfsRealtimeAdapter(String base, HttpClient http, Clock clock) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public List<ServiceAlert> alerts() {
        return decode("alerts").alerts();
    }

    @Override
    public RealtimeSnapshot snapshot() {
        GtfsRealtimeDecoder.Feed feed = decode("trips");
        return new RealtimeSnapshot(feed.timestamp() != null ? feed.timestamp() : clock.instant(),
                feed.delaysByTrip(), false);
    }

    private GtfsRealtimeDecoder.Feed decode(String name) {
        byte[] bytes = fetch(name);
        try {
            return decoder.decode(bytes);
        } catch (IllegalArgumentException e) {
            throw new FeedException("GTFS-RT " + name + " is not valid protobuf: " + e.getMessage(), e);
        }
    }

    byte[] fetch(String name) {
        try {
            if (base.startsWith("http://") || base.startsWith("https://")) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + name))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) {
                    throw new FeedException("GTFS-RT " + name + " returned HTTP " + res.statusCode());
                }
                return res.body();
            }
            return Files.readAllBytes(Path.of(base, name + ".pb"));
        } catch (IOException e) {
            throw new FeedException("could not read GTFS-RT " + name + " from " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedException("interrupted while reading GTFS-RT " + name, e);
        }
    }

    String base() {
        return base;
    }
}
