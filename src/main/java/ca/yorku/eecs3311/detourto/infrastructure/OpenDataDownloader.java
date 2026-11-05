package ca.yorku.eecs3311.detourto.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads the TTC GTFS schedule (F13). The URL is either a direct .zip link, or a CKAN
 * {@code package_show} API URL (the City of Toronto Open Data portal), in which case the first
 * ZIP resource of the package is downloaded.
 */
public final class OpenDataDownloader {

    public static final String DEFAULT_URL =
            "https://ckan0.cf.opendata.inter.prod-toronto.ca/api/3/action/package_show?id=ttc-routes-and-schedules";

    private final HttpClient http;

    public OpenDataDownloader() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build());
    }

    public OpenDataDownloader(HttpClient http) {
        this.http = http;
    }

    /** Downloads to {@code target} (written only when the download completes). */
    public Path download(String url, Path target) throws IOException {
        String zipUrl = url.toLowerCase().endsWith(".zip") ? url : resolveZip(url);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.toAbsolutePath().getParent());
        HttpResponse<Path> res = send(HttpRequest.newBuilder(URI.create(zipUrl)).timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("download failed: HTTP " + res.statusCode() + " from " + zipUrl);
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Finds the GTFS zip link in a CKAN package_show response. */
    String resolveZip(String packageUrl) throws IOException {
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(packageUrl)).timeout(Duration.ofSeconds(30))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Open Data portal returned HTTP " + res.statusCode());
        }
        return zipFromCkan(res.body());
    }

    static String zipFromCkan(String json) throws IOException {
        JsonNode resources = new ObjectMapper().readTree(json).path("result").path("resources");
        String fallback = null;
        for (JsonNode r : resources) {
            String u = r.path("url").asText("");
            boolean zip = r.path("format").asText("").equalsIgnoreCase("zip") || u.toLowerCase().endsWith(".zip");
            if (zip && r.path("name").asText("").toLowerCase().contains("complete")) {
                return u;
            }
            if (zip && fallback == null) {
                fallback = u;
            }
        }
        if (fallback == null) {
            throw new IOException("no ZIP resource found in the Open Data package");
        }
        return fallback;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
