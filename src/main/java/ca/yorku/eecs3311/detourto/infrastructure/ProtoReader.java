package ca.yorku.eecs3311.detourto.infrastructure;

import java.nio.charset.StandardCharsets;

/**
 * Minimal reader for the protobuf wire format (varints, length-delimited fields, fixed 32/64-bit
 * values). It is enough for GTFS-Realtime, so the project does not need the protobuf library.
 */
final class ProtoReader {

    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private final byte[] buf;
    private int pos;
    private final int limit;
    private int lastTag;

    ProtoReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    private ProtoReader(byte[] buf, int offset, int limit) {
        this.buf = buf;
        this.pos = offset;
        this.limit = limit;
    }

    /** Next field tag, or 0 at the end of the message. */
    int nextTag() {
        if (pos >= limit) {
            return 0;
        }
        lastTag = (int) readVarint();
        if (lastTag == 0) {
            throw new IllegalArgumentException("invalid protobuf tag 0 at byte " + pos);
        }
        return lastTag;
    }

    int fieldNumber() {
        return lastTag >>> 3;
    }

    int wireType() {
        return lastTag & 7;
    }

    long readVarint() {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos >= limit) {
                throw new IllegalArgumentException("truncated varint");
            }
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }

    /** int32 fields are varints; negative values arrive as 10-byte two's complement. */
    int readInt32() {
        return (int) readVarint();
    }

    String readString() {
        int len = readLength();
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    /** Reader over an embedded message; this reader skips past it. */
    ProtoReader readMessage() {
        int len = readLength();
        ProtoReader sub = new ProtoReader(buf, pos, pos + len);
        pos += len;
        return sub;
    }

    /** Skips the value of the field just read (unknown fields are allowed by protobuf). */
    void skip() {
        switch (wireType()) {
            case VARINT -> readVarint();
            case FIXED64 -> advance(8);
            case LENGTH_DELIMITED -> advance(readLength());
            case FIXED32 -> advance(4);
            default -> throw new IllegalArgumentException("unsupported wire type " + wireType());
        }
    }

    private int readLength() {
        long len = readVarint();
        if (len < 0 || pos + len > limit) {
            throw new IllegalArgumentException("length " + len + " runs past the end of the message");
        }
        return (int) len;
    }

    private void advance(int n) {
        if (pos + n > limit) {
            throw new IllegalArgumentException("truncated field");
        }
        pos += n;
    }
}
