package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.TransitMode;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.Trip;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GtfsStaticLoaderTest {

    static final Path SAMPLE = Path.of("data/sample/mini-gtfs");

    @TempDir
    Path dir;

    @Test
    void loadsTheSampleFeed() throws IOException {
        GtfsStaticLoader loader = new GtfsStaticLoader();
        TransitNetwork net = loader.load(SAMPLE);
        FeedValidationReport report = loader.lastReport();

        assertTrue(report.ok(), report.summary());
        assertTrue(report.warnings().isEmpty(), report.summary());
        assertEquals(72, net.stopCount());
        assertEquals(5, net.routes().size());
        assertEquals(2696, net.tripCount());
        assertEquals(29118, report.stopTimeCount());
        assertEquals(TransitMode.SUBWAY, net.findRoute("1").orElseThrow().mode());
        assertEquals(TransitMode.STREETCAR, net.findRoute("510").orElseThrow().mode());
    }

    @Test
    void tripStopTimesAreInOrderAndInSeconds() throws IOException {
        TransitNetwork net = new GtfsStaticLoader().load(SAMPLE);
        Trip trip = net.findTrip("1_WEEKDAY_0_0").orElseThrow();
        assertEquals("YU_L1", net.stopAt(trip.stopIndexAt(0)).id());
        assertEquals(6 * 3600, trip.arrivalAt(0));
        assertEquals(6 * 3600 + 20, trip.departureAt(0));
        for (int i = 1; i < trip.size(); i++) {
            assertTrue(trip.arrivalAt(i) >= trip.departureAt(i - 1));
        }
    }

    @Test
    void parsesTimesPastMidnight() {
        assertEquals(25 * 3600 + 5 * 60 + 9, GtfsStaticLoader.parseTime("25:05:09"));
        assertEquals(-1, GtfsStaticLoader.parseTime(""));
        assertEquals(-1, GtfsStaticLoader.parseTime("bad"));
    }

    @Test
    void readsAZipWithAQuotedFieldAndBom() throws IOException {
        Path zip = dir.resolve("feed.zip");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            put(z, "gtfs/stops.txt", "﻿stop_id,stop_name,stop_lat,stop_lon\n"
                    + "A,\"Queen St, West\",43.65,-79.40\nB,B stop,43.651,-79.401\n");
            put(z, "gtfs/routes.txt", "route_id,route_short_name,route_long_name,route_type\n"
                    + "501,501,Queen,0\n");
            put(z, "gtfs/trips.txt", "route_id,service_id,trip_id\n501,S,T1\n");
            put(z, "gtfs/stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                    + "T1,24:10:00,24:10:00,B,2\nT1,24:00:00,24:00:00,A,1\n");
            put(z, "gtfs/calendar_dates.txt", "service_id,date,exception_type\nS,20261102,1\n");
        }
        GtfsStaticLoader loader = new GtfsStaticLoader();
        TransitNetwork net = loader.load(zip);
        assertTrue(loader.lastReport().ok(), loader.lastReport().summary());
        assertEquals("Queen St, West", net.stopById("A").name());
        Trip t = net.findTrip("T1").orElseThrow();
        assertEquals("A", net.stopAt(t.stopIndexAt(0)).id()); // sorted by stop_sequence
        assertEquals(24 * 3600, t.departureAt(0));
    }

    @Test
    void badRowsBecomeWarningsNotFailures() throws IOException {
        Path feed = Files.createDirectory(dir.resolve("feed"));
        Files.writeString(feed.resolve("stops.txt"),
                "stop_id,stop_name,stop_lat,stop_lon\nA,A,43.6,-79.4\nB,B,43.61,-79.41\nC,C,x,y\n");
        Files.writeString(feed.resolve("routes.txt"),
                "route_id,route_short_name,route_long_name,route_type\nR,R,R,3\n");
        Files.writeString(feed.resolve("trips.txt"),
                "route_id,service_id,trip_id\nR,S,T1\nNOPE,S,T2\n");
        Files.writeString(feed.resolve("stop_times.txt"),
                "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
                        + "T1,08:00:00,08:00:00,A,1\nT1,08:05:00,08:05:00,B,2\nT1,08:06:00,08:06:00,ZZ,3\n");
        Files.writeString(feed.resolve("calendar.txt"),
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                        + "S,1,1,1,1,1,1,1,20260101,20261231\n");
        FeedValidationReport report = new GtfsStaticLoader().validate(feed);
        assertTrue(report.ok());
        assertEquals(2, report.stopCount());
        assertEquals(1, report.tripCount());
        assertEquals(3, report.warnings().size(), report.summary());
    }

    @Test
    void missingRequiredFileIsAnError() throws IOException {
        Path feed = Files.createDirectory(dir.resolve("empty"));
        assertThrows(IOException.class, () -> new GtfsStaticLoader().load(feed));
        assertThrows(IOException.class, () -> new GtfsStaticLoader().load(dir.resolve("nothing")));
    }

    @Test
    void csvReaderHandlesEscapedQuotesAndMultilineFields() throws IOException {
        try (CsvReader csv = new CsvReader(new StringReader(
                "a,b\n\"say \"\"hi\"\"\",\"two\nlines\"\n\nx,\n"))) {
            assertTrue(csv.next());
            assertEquals("say \"hi\"", csv.get("a"));
            assertEquals("two\nlines", csv.get("b"));
            assertTrue(csv.next());
            assertEquals("x", csv.get("a"));
            assertEquals("", csv.get("b"));
            assertEquals("", csv.get("missing"));
            assertFalse(csv.next());
        }
    }

    private static void put(ZipOutputStream z, String name, String body) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
