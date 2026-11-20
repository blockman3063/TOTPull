package io.blockman.totpull.format;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal protobuf writer, the mirror of {@link ProtoReader}. Just enough for the
 * Google Authenticator migration payload: varints, length-delimited fields, nothing else.
 */
public final class ProtoWriter {

    private final ByteArrayOutputStream _out = new ByteArrayOutputStream();

    /** Field header (field number + wire type) as a varint. */
    public ProtoWriter tag(int field, int wire) {
        varint(((field & 0x7FFFFFFF) << 3) | wire);
        return this;
    }

    public ProtoWriter varintField(int field, long value) {
        tag(field, 0);
        varint(value);
        return this;
    }

    public ProtoWriter bytesField(int field, byte[] bytes) {
        tag(field, 2);
        varint(bytes.length);
        _out.write(bytes, 0, bytes.length);
        return this;
    }

    public ProtoWriter stringField(int field, String value) {
        return bytesField(field, value == null
                ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    public ProtoWriter varint(long value) {
        // Protobuf varints are two's-complement 64-bit; our values are small and positive.
        while ((value & ~0x7FL) != 0) {
            _out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        _out.write((int) value);
        return this;
    }

    public byte[] toBytes() {
        return _out.toByteArray();
    }
}
