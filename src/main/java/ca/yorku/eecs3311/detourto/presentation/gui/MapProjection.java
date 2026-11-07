package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.domain.Stop;
import java.util.Collection;

/**
 * Turns latitude/longitude into pixel positions for the map. Longitude is scaled by
 * cos(latitude) so Toronto's streets are not stretched sideways, and the visible area is fitted
 * into the canvas with a margin while keeping its aspect ratio.
 */
public final class MapProjection {

    /** A latitude/longitude box. */
    public record Bounds(double minLat, double maxLat, double minLon, double maxLon) {

        public static Bounds of(Collection<Stop> stops) {
            if (stops.isEmpty()) {
                return new Bounds(43.6, 43.8, -79.5, -79.3);
            }
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (Stop s : stops) {
                minLat = Math.min(minLat, s.lat());
                maxLat = Math.max(maxLat, s.lat());
                minLon = Math.min(minLon, s.lon());
                maxLon = Math.max(maxLon, s.lon());
            }
            return new Bounds(minLat, maxLat, minLon, maxLon);
        }

        /** The same box grown by {@code fraction} on every side, and at least {@code minSpan} degrees tall. */
        public Bounds padded(double fraction, double minSpan) {
            double dLat = Math.max(maxLat - minLat, minSpan);
            double dLon = Math.max(maxLon - minLon, minSpan / Math.cos(Math.toRadians(centerLat())));
            double cLat = centerLat();
            double cLon = (minLon + maxLon) / 2;
            double hLat = dLat * (0.5 + fraction);
            double hLon = dLon * (0.5 + fraction);
            return new Bounds(cLat - hLat, cLat + hLat, cLon - hLon, cLon + hLon);
        }

        public double centerLat() {
            return (minLat + maxLat) / 2;
        }

        public double latSpan() {
            return maxLat - minLat;
        }
    }

    private final double minX;
    private final double maxY;
    private final double scale;
    private final double offsetX;
    private final double offsetY;
    private final double cosLat;

    public MapProjection(Collection<Stop> stops, double width, double height, double margin) {
        this(Bounds.of(stops), width, height, margin);
    }

    public MapProjection(Bounds b, double width, double height, double margin) {
        cosLat = Math.cos(Math.toRadians(b.centerLat()));
        minX = b.minLon() * cosLat;
        double spanX = Math.max(1e-6, (b.maxLon() - b.minLon()) * cosLat);
        double spanY = Math.max(1e-6, b.maxLat() - b.minLat());
        maxY = b.maxLat();
        double usableW = Math.max(1, width - 2 * margin);
        double usableH = Math.max(1, height - 2 * margin);
        scale = Math.min(usableW / spanX, usableH / spanY);
        offsetX = margin + (usableW - spanX * scale) / 2;
        offsetY = margin + (usableH - spanY * scale) / 2;
    }

    public double x(double lon) {
        return offsetX + (lon * cosLat - minX) * scale;
    }

    public double y(double lat) {
        return offsetY + (maxY - lat) * scale;
    }

    /** Inverse of {@link #x}. */
    public double lon(double x) {
        return ((x - offsetX) / scale + minX) / cosLat;
    }

    /** Inverse of {@link #y}. */
    public double lat(double y) {
        return maxY - (y - offsetY) / scale;
    }

    /** The latitude/longitude box that fills a canvas of this size. */
    public Bounds visible(double width, double height) {
        return new Bounds(lat(height), lat(0), lon(0), lon(width));
    }
}
