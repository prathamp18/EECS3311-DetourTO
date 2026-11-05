package ca.yorku.eecs3311.detourto.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsRealtimeAdapter;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import ca.yorku.eecs3311.detourto.infrastructure.SqliteCommuteRepository;
import ca.yorku.eecs3311.detourto.infrastructure.SqliteDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommuteSchedulerTest {

    static TransitNetwork net;
    static final LocalDateTime MON = LocalDateTime.of(2026, 11, 2, 7, 0);

    @TempDir
    Path dir;

    @BeforeAll
    static void load() throws IOException {
        net = new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs"));
    }

    static TripController controller() {
        return new TripController(new JourneyPlanner(net), new PlaceResolver(net), null,
                new GtfsRealtimeAdapter("data/sample/realtime"));
    }

    @Test
    void disruptionMakesTheRiderLeaveEarlierAndExplainsWhy() {
        TripController c = controller();
        assertTrue(c.saveCommute("school", "York U", "Union", LocalTime.of(9, 0), Commute.parseDays("weekdays"), 20).isEmpty());
        CommuteScheduler.Status s = c.commutes().today(MON).get(0);
        assertTrue(s.option().isPresent());
        assertFalse(s.option().get().arrival().isAfter(MON.withHour(9)));
        assertTrue(s.extraMinutes() > 0, "the Line 1 closure means leaving earlier");
        assertTrue(s.message().contains("earlier than usual") && s.message().contains("Line 1"), s.message());
    }

    @Test
    void warnsOncePerDayInsideTheWarningWindow() {
        TripController c = controller();
        c.saveCommute("school", "York U", "Union", LocalTime.of(9, 0), Commute.parseDays("weekdays"), 20);
        List<TransitEvent> events = new ArrayList<>();
        c.eventBus().subscribe(EventType.LEAVE_BY_ALERT, events::add);
        LocalDateTime leaveBy = c.commutes().today(MON).get(0).leaveBy();

        assertTrue(c.commutes().checkDue(leaveBy.minusMinutes(30)).isEmpty(), "too early");
        assertEquals(1, c.commutes().checkDue(leaveBy.minusMinutes(15)).size());
        assertTrue(c.commutes().checkDue(leaveBy.minusMinutes(10)).isEmpty(), "already warned today");
        assertEquals(1, events.size());
        assertTrue(c.commutes().today(MON.withDayOfMonth(7)).isEmpty(), "Saturday: weekday commute does not run");
    }

    @Test
    void noRouteInTimeGivesTheEarliestArrival() {
        TripController c = controller();
        c.saveCommute("early", "York U", "Union", LocalTime.of(6, 10), EnumSet.of(DayOfWeek.MONDAY), 20);
        CommuteScheduler.Status s = c.commutes().today(MON).get(0);
        assertTrue(s.option().isEmpty());
        assertTrue(s.message().startsWith("No route arrives by 06:10; the earliest possible arrival is"), s.message());
    }

    @Test
    void commutesPersistInSqliteAndDaysParse() throws Exception {
        try (Connection db = SqliteDatabase.open(dir.resolve("c.db"))) {
            SqliteCommuteRepository repo = new SqliteCommuteRepository(db, net);
            repo.save(new Commute("Gym", net.stopById("STN_KE"), net.stopById("STN_SG"), LocalTime.of(18, 30),
                    Commute.parseDays("mon,wed,fri"), 15));
            Commute back = new SqliteCommuteRepository(db, net).findAll().get(0);
            assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), back.days());
            assertEquals(15, back.warnMinutesBefore());
            assertTrue(repo.delete("gym"));
            assertTrue(repo.findAll().isEmpty());
        }
        assertEquals(7, Commute.parseDays("daily").size());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Commute.parseDays("funday"));
    }

    @Test
    void refreshKeepsDataWhenInvalidAndSwapsWhenValid() throws IOException {
        TripController c = controller();
        c.planFromForm(ca.yorku.eecs3311.detourto.domain.TripQuery.departAt(net.stopById("STN_KE"), net.stopById("STN_UN"), MON));
        TripController.RefreshResult missing = c.refreshTransitData(dir.resolve("none"), null, null);
        assertFalse(missing.swapped());
        assertEquals(72, c.network().stopCount());

        java.nio.file.Files.createDirectory(dir.resolve("empty"));
        java.nio.file.Files.writeString(dir.resolve("empty/stops.txt"), "stop_id,stop_name,stop_lat,stop_lon\n");
        for (String f : List.of("routes.txt", "trips.txt", "stop_times.txt")) {
            java.nio.file.Files.writeString(dir.resolve("empty/" + f), "x\n");
        }
        TripController.RefreshResult invalid = c.refreshTransitData(dir.resolve("empty"), null, null);
        assertFalse(invalid.swapped());
        assertFalse(invalid.report().ok());
        assertEquals(72, c.network().stopCount(), "invalid data is not swapped in");

        TripController.RefreshResult ok = c.refreshTransitData(Path.of("data/sample/mini-gtfs"), null, null);
        assertTrue(ok.swapped());
        assertTrue(c.currentQuery().isEmpty(), "old trips are cleared after a swap");
        assertFalse(c.resolvePlace("York U").stop().isEmpty(), "the resolver sees the new data");
    }

    @Test
    void ckanResponseGivesTheZipLink() throws IOException {
        String json = """
                {"result":{"resources":[{"name":"readme","format":"PDF","url":"https://x/readme.pdf"},
                 {"name":"Completed GTFS","format":"ZIP","url":"https://x/TTC%20Routes%20and%20Schedules%20Data.zip"}]}}""";
        assertEquals("https://x/TTC%20Routes%20and%20Schedules%20Data.zip",
                ca.yorku.eecs3311.detourto.infrastructure.OpenDataDownloaderAccess.zipFromCkan(json));
    }
}
