package ca.yorku.eecs3311.detourto.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Stop;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapProjectionTest {

    static Stop stop(double lat, double lon) {
        return new Stop("s" + lat + lon, "", "s", lat, lon, true, null, false);
    }

    @Test
    void networkFitsInsideTheMarginsWithNorthUp() {
        List<Stop> stops = List.of(stop(43.64, -79.46), stop(43.78, -79.38), stop(43.70, -79.40));
        MapProjection p = new MapProjection(stops, 800, 600, 40);
        for (Stop s : stops) {
            assertTrue(p.x(s.lon()) >= 39.9 && p.x(s.lon()) <= 760.1, "x in bounds");
            assertTrue(p.y(s.lat()) >= 39.9 && p.y(s.lat()) <= 560.1, "y in bounds");
        }
        assertTrue(p.y(43.78) < p.y(43.64), "north is up");
        assertTrue(p.x(-79.38) > p.x(-79.46), "east is right");
    }

    @Test
    void keepsTheAspectRatio() {
        List<Stop> square = List.of(stop(43.70, -79.40), stop(43.71, -79.40 + 0.01 / Math.cos(Math.toRadians(43.705))));
        MapProjection p = new MapProjection(square, 1000, 500, 0);
        double dx = p.x(square.get(1).lon()) - p.x(square.get(0).lon());
        double dy = p.y(43.70) - p.y(43.71);
        assertEquals(dx, dy, 1e-6, "one km east is as long as one km north");
    }

    @Test
    void pixelsTurnBackIntoTheSamePlace() {
        MapProjection p = new MapProjection(new MapProjection.Bounds(43.65, 43.75, -79.50, -79.35), 900, 600, 0);
        assertEquals(43.7123, p.lat(p.y(43.7123)), 1e-9);
        assertEquals(-79.4011, p.lon(p.x(-79.4011)), 1e-9);
        MapProjection.Bounds v = p.visible(900, 600);
        assertTrue(v.minLat() <= 43.65 + 1e-9 && v.maxLat() >= 43.75 - 1e-9, "the whole box is visible");
    }

    @Test
    void fittingAShortTripZoomsInButNotTooFar() {
        List<Stop> trip = List.of(stop(43.6700, -79.3900), stop(43.6705, -79.3895));
        MapProjection.Bounds b = MapProjection.Bounds.of(trip).padded(0.12, 0.012);
        assertTrue(b.latSpan() >= 0.012, "at least about 1.3 km tall");
        assertTrue(b.minLat() < 43.6700 && b.maxLat() > 43.6705, "the trip is inside");
        MapProjection.Bounds whole = MapProjection.Bounds.of(List.of(stop(43.59, -79.62), stop(43.85, -79.15)));
        assertTrue(b.latSpan() < whole.latSpan() / 5, "much closer than the whole city");
    }
}
