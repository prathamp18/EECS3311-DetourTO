package ca.yorku.eecs3311.detourto.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GtfsRealtimeAdapterTest {

    static final String SAMPLE = "data/sample/realtime";

    @TempDir
    Path dir;

    @Test
    void readsTheSampleAlerts() {
        List<ServiceAlert> alerts = new GtfsRealtimeAdapter(SAMPLE).alerts();
        assertEquals(4, alerts.size());
        ServiceAlert closure = alerts.get(0);
        assertEquals("A-L1-CLOSURE", closure.id());
        assertEquals("NO_SERVICE", closure.effect());
        assertEquals("MAINTENANCE", closure.cause());
        assertEquals(List.of("1"), closure.informedRouteIds());
        assertEquals(List.of("STN_MU", "STN_QP", "STN_SPT", "STN_OS"), closure.informedStopIds());
        assertTrue(closure.header().startsWith("Line 1: No subway service"));
        assertTrue(closure.isActiveAt(Instant.parse("2026-11-02T12:00:00Z")));
        assertEquals("ACCESSIBILITY_ISSUE", alerts.get(1).effect());
        assertEquals("UNKNOWN_EFFECT", alerts.get(2).effect(), "the 510 diversion is described only in text");
        assertTrue(alerts.get(2).description().contains("Spadina Ave at Queen St West"));
    }

    @Test
    void readsTheSampleTripDelays() {
        RealtimeSnapshot snap = new GtfsRealtimeAdapter(SAMPLE).snapshot();
        assertFalse(snap.stale());
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), snap.fetchedAt());
        assertTrue(snap.tripCount() > 400);
        // n=0, direction 0: (0*97 + 0) % 480 - 60 = -60 (a minute early)
        assertEquals(-60, snap.delayAt("510_WEEKDAY_0_0", "SP_510", 1).orElseThrow());
        // the delay carries forward to later stops of the same trip
        assertEquals(-60, snap.delayAt("510_WEEKDAY_0_0", "SPKI_510", 4).orElseThrow());
        assertTrue(snap.delayAt("1_WEEKDAY_0_0", "YU_L1", 1).isEmpty(), "no subway real-time");
    }

    @Test
    void decodesHandBuiltMessagesAndSkipsUnknownFields() {
        byte[] feed = concat(
                bytesField(1, bytesField(1, str("2.0")), varintField(3, 1_800_000_000L)),
                bytesField(2, bytesField(1, str("e1")),
                        bytesField(3,
                                bytesField(1, bytesField(1, str("T1")), varintField(99, 7)),
                                bytesField(2, varintField(1, 3), bytesField(2, varintField(1, -120)), bytesField(4, str("S3"))),
                                bytesField(2, varintField(1, 4), varintField(5, 1)))),   // SKIPPED stop is ignored
                bytesField(2, bytesField(1, str("e2")), varintField(2, 1), bytesField(3, bytesField(1, bytesField(1, str("GONE"))))));
        GtfsRealtimeDecoder.Feed decoded = new GtfsRealtimeDecoder().decode(feed);
        assertEquals(Instant.ofEpochSecond(1_800_000_000L), decoded.timestamp());
        assertEquals(1, decoded.delaysByTrip().size(), "deleted entities are dropped");
        assertEquals(List.of(new RealtimeSnapshot.StopDelay(3, "S3", -120)), decoded.delaysByTrip().get("T1"));
        RealtimeSnapshot snap = new RealtimeSnapshot(Instant.EPOCH, decoded.delaysByTrip(), false);
        assertTrue(snap.delayAt("T1", "S1", 1).isEmpty(), "no delay is assumed before the first update");
        assertEquals(Map.of("T1", -120), snap.tripDelaySeconds());
    }

    @Test
    void missingOrCorruptFeedsThrowFeedException() throws IOException {
        assertThrows(FeedException.class, () -> new GtfsRealtimeAdapter(dir.toString()).alerts());
        Files.write(dir.resolve("trips.pb"), new byte[] {0x0A, (byte) 0xFF, 0x01});
        assertThrows(FeedException.class, () -> new GtfsRealtimeAdapter(dir.toString()).snapshot());
    }

    // ---- tiny protobuf writer for building test messages

    static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            if ((v & ~0x7FL) == 0) {
                out.write((int) v);
                return out.toByteArray();
            }
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
    }

    static byte[] varintField(int num, long v) {
        return concat(varint((long) num << 3), varint(v));
    }

    static byte[] bytesField(int num, byte[]... parts) {
        byte[] body = concat(parts);
        return concat(varint((long) num << 3 | 2), varint(body.length), body);
    }

    static byte[] str(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
