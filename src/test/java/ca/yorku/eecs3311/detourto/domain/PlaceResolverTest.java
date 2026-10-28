package ca.yorku.eecs3311.detourto.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlaceResolverTest {

    static PlaceResolver resolver;

    @BeforeAll
    static void load() throws IOException {
        resolver = new PlaceResolver(new GtfsStaticLoader().load(Path.of("data/sample/mini-gtfs")));
    }

    static String best(String text) {
        return resolver.resolve(text).get(0).id();
    }

    @Test
    void commonWaysOfNamingStations() {
        assertEquals("STN_YU", best("York U"));
        assertEquals("STN_YU", best("york university"));
        assertEquals("STN_SG", best("st george"));
        assertEquals("STN_SG", best("Saint George Station"));
        assertEquals("STN_UN", best("Union"));
        assertEquals("STN_FI", best("Finch"), "plain 'Finch' prefers Finch over Finch West");
        assertEquals("STN_FW", best("finch west"));
        assertEquals("STN_QP", best("Queens Park"));
    }

    @Test
    void platformsFoldIntoTheirStation() {
        assertEquals(List.of("STN_UN"), resolver.resolve("UN_L1").stream().map(Stop::id).toList());
        assertTrue(resolver.resolve("Union").stream().noneMatch(s -> s.id().startsWith("UN_")));
    }

    @Test
    void streetStopsAndCoordinates() {
        assertEquals("SPCO_510", best("Spadina & College"));
        assertEquals("STN_UN", best("43.6453,-79.3806"));
    }

    @Test
    void nothingForGibberish() {
        assertTrue(resolver.resolve("zzzz qqqq").isEmpty());
        assertTrue(resolver.resolve("   ").isEmpty());
    }

    @Test
    void ambiguityIsDetected() {
        assertTrue(resolver.isAmbiguous(resolver.match("Finch Ave")), "several stops along Finch Ave");
        assertEquals("STN_SP", best("Spadina"), "a full name match beats partial street matches");
        assertFalse(resolver.isAmbiguous(resolver.match("Spadina")));
        assertFalse(resolver.isAmbiguous(resolver.match("St George")));
    }

    @Test
    void similarityPrefersFullCoverage() {
        double exact = PlaceResolver.similarity(PlaceResolver.tokens("finch"), PlaceResolver.tokens("Finch Station"));
        double partial = PlaceResolver.similarity(PlaceResolver.tokens("finch"), PlaceResolver.tokens("Finch West Station"));
        assertEquals(1.0, exact, 1e-9);
        assertTrue(partial < exact);
    }
}
