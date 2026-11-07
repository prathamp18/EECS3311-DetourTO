package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A feed laid out like the TTC's: every subway platform is its own stop, names in capitals, no
 * parent stations, and many street stops that share words with station names.
 */
class StationGroupingTest {

    @TempDir
    static Path dir;
    static TransitNetwork net;

    @BeforeAll
    static void load() throws IOException {
        Path feed = Files.createDirectory(dir.resolve("ttc-like"));
        Files.writeString(feed.resolve("stops.txt"), """
                stop_id,stop_code,stop_name,stop_lat,stop_lon,location_type,parent_station,wheelchair_boarding
                14400,,DON MILLS STATION - WESTBOUND PLATFORM,43.7756,-79.3462,,,1
                14401,,DON MILLS STATION - EASTBOUND PLATFORM,43.7757,-79.3463,,,1
                14410,,SHEPPARD-YONGE STATION - LINE 4 WESTBOUND PLATFORM,43.7616,-79.4110,,,1
                14411,,SHEPPARD-YONGE STATION - LINE 4 EASTBOUND PLATFORM,43.7617,-79.4111,,,1
                14420,,SHEPPARD-YONGE STATION - LINE 1 NORTHBOUND PLATFORM,43.7615,-79.4108,,,1
                14421,,SHEPPARD-YONGE STATION - LINE 1 SOUTHBOUND PLATFORM,43.7614,-79.4107,,,1
                14430,,PIONEER VILLAGE STATION - NORTHBOUND PLATFORM,43.7766,-79.5091,,,1
                14431,,PIONEER VILLAGE STATION - SOUTHBOUND PLATFORM,43.7767,-79.5092,,,1
                900,1234,Don Mills Rd at Sheppard Ave East,43.7750,-79.3450,,,1
                901,1235,Don Mills Rd at Lawrence Ave East,43.7380,-79.3400,,,1
                902,1236,Don Mills Rd at York Mills Rd,43.7550,-79.3520,,,1
                """);
        Files.writeString(feed.resolve("routes.txt"), """
                route_id,route_short_name,route_long_name,route_type
                4,4,LINE 4 (SHEPPARD),1
                1,1,LINE 1 (YONGE-UNIVERSITY),1
                """);
        Files.writeString(feed.resolve("trips.txt"), """
                route_id,service_id,trip_id,trip_headsign
                4,W,T4,SHEPPARD-YONGE
                1,W,T1,VAUGHAN
                1,W,T1b,VAUGHAN
                """);
        Files.writeString(feed.resolve("stop_times.txt"), """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T4,08:00:00,08:00:00,14400,1
                T4,08:10:00,08:10:00,14410,2
                T1,08:15:00,08:15:00,14421,1
                T1,08:40:00,08:40:00,14430,2
                T1b,08:25:00,08:25:00,14421,1
                T1b,08:50:00,08:50:00,14430,2
                """);
        Files.writeString(feed.resolve("calendar.txt"), """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                W,1,1,1,1,1,1,1,20260101,20271231
                """);
        GtfsStaticLoader loader = new GtfsStaticLoader();
        net = loader.load(feed);
        assertTrue(loader.lastReport().ok());
    }

    @Test
    void platformsAreGroupedIntoReadableStations() {
        Stop donMills = net.stops().stream().filter(s -> s.station() && s.name().equals("Don Mills Station"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("14400", "14401"),
                Set.copyOf(net.boardingStopsFor(donMills).stream().map(Stop::id).toList()));
        Stop sy = net.stops().stream().filter(s -> s.station() && s.name().equals("Sheppard-Yonge Station"))
                .findFirst().orElseThrow();
        assertEquals(4, net.boardingStopsFor(sy).size(), "Line 1 and Line 4 platforms are one station");
        assertTrue(net.findStop("900").orElseThrow().parentStation() == null, "street stops are left alone");
    }

    @Test
    void placeNamesFindTheStationNotStreetStops() {
        PlaceResolver r = new PlaceResolver(net);
        assertEquals("Don Mills Station", r.resolve("Don Mills").get(0).name());
        assertFalse(r.isAmbiguous(r.match("don mills")));
        assertEquals("Pioneer Village Station", r.resolve("pioneer village station").get(0).name());
        assertEquals("Don Mills Rd at Lawrence Ave East", r.resolve("Don Mills and Lawrence").get(0).name());
    }

    @Test
    void tripBetweenGroupedStationsChangesAtSheppardYonge() {
        PlaceResolver r = new PlaceResolver(net);
        TripQuery q = TripQuery.departAt(r.resolve("Don Mills").get(0), r.resolve("Pioneer Village").get(0),
                LocalDateTime.of(2026, 11, 9, 7, 55));
        List<Itinerary> options = new JourneyPlanner(net).plan(q);
        assertFalse(options.isEmpty());
        Itinerary best = options.get(0);
        assertEquals(List.of("4", "1"), List.copyOf(best.routeIds()));
        assertEquals(LocalDateTime.of(2026, 11, 9, 8, 40), best.arrival());
    }

    @Test
    void titleCaseOnlyChangesAllCapsNames() {
        assertEquals("Sheppard-Yonge Station", GtfsStaticLoader.titleCase("SHEPPARD-YONGE STATION"));
        assertEquals("Line 4 (Sheppard)", GtfsStaticLoader.titleCase("LINE 4 (SHEPPARD)"));
        assertEquals("Union Station", GtfsStaticLoader.titleCase("Union Station"));
    }
}
