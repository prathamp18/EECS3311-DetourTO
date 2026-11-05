package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteProfileRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void profileSurvivesReopeningTheDatabase() throws Exception {
        Path file = dir.resolve("local/detourto.db");
        try (Connection db = SqliteDatabase.open(file)) {
            SqliteProfileRepository repo = new SqliteProfileRepository(db);
            assertTrue(repo.load().savedPlaces().isEmpty());
            RiderProfile p = repo.load();
            p.apply(new PreferenceUpdate("save_place", "Home=STN_KE"));
            p.apply(new PreferenceUpdate("save_place", "school=STN_YU"));
            p.apply(new PreferenceUpdate("avoid_route", "29"));
            p.apply(new PreferenceUpdate("default_strategy", "fewest-transfers"));
            p.apply(new PreferenceUpdate("step_free", "true"));
            repo.save(p);
        }
        try (Connection db = SqliteDatabase.open(file)) {
            RiderProfile again = new SqliteProfileRepository(db).load();
            assertEquals(List.of("home", "school"), List.copyOf(again.savedPlacesInOrder().keySet()));
            assertEquals("STN_KE", again.placeStopId("my home").orElseThrow());
            assertEquals(Set.of("29"), again.avoidedRouteIds());
            assertEquals(StrategyKind.FEWEST_TRANSFERS, again.defaultStrategy());
            assertTrue(again.needsStepFree());
        }
    }

    @Test
    void forgettingAndBadValues() {
        RiderProfile p = new RiderProfile();
        p.apply(new PreferenceUpdate("save_place", "gym=STN_SG"));
        p.apply(new PreferenceUpdate("forget_place", "GYM"));
        assertEquals(Map.of(), p.savedPlaces());
        assertThrows(IllegalArgumentException.class, () -> p.apply(new PreferenceUpdate("save_place", "nolabel")));
        assertThrows(IllegalArgumentException.class, () -> p.apply(new PreferenceUpdate("step_free", "maybe")));
        assertThrows(IllegalArgumentException.class, () -> p.apply(new PreferenceUpdate("favourite_colour", "blue")));
    }
}
