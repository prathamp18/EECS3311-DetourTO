package ca.yorku.eecs3311.detourto.presentation.gui;

import ca.yorku.eecs3311.detourto.domain.Stop;
import java.util.Collection;

/**
 * Turns latitude/longitude into pixel positions for the map. Longitude is scaled by
 * cos(latitude) so Toronto's streets are not stretched sideways, and the network is fitted into
 * the canvas with a margin while keeping its aspect ratio.
 */
public final class MapProjection {

    private final double minX;
    private final double maxY;
    private final double scale;
    private final double offsetX;
    private final double offsetY;
    private final double cosLat;

    public MapProjection(Collection<Stop> stops, double width, double height, double margin) {
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
        if (stops.isEmpty()) {
            minLat = 43.6;
            maxLat = 43.8;
            minLon = -79.5;
            maxLon = -79.3;
        }
        cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        minX = minLon * cosLat;
        double spanX = Math.max(1e-6, (maxLon - minLon) * cosLat);
        double spanY = Math.max(1e-6, maxLat - minLat);
        maxY = maxLat;
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
}
