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
}
