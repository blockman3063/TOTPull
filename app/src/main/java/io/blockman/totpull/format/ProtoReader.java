package io.blockman.totpull.format;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/** Minimal protobuf wire-format reader. Enough for Google's otpauth-migration payload. */
public class ProtoReader {
    private final byte[] _data;
    private int _pos;

    public ProtoReader(byte[] data) {
        _data = data == null ? new byte[0] : data;
        _pos = 0;
    }

    public boolean hasMore() {
        return _pos < _data.length;
    }

    public int readTag() {
        return (int) readVarint();
    }

    public long readVarint() {
        long result = 0;
        int shift = 0;
        while (_pos < _data.length && shift < 64) {
            int b = _data[_pos++] & 0xff;
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    public byte[] readBytes() {
        int length = (int) readVarint();
        if (length < 0 || _pos + length > _data.length) {
            _pos = _data.length;
            return new byte[0];
        }
        byte[] out = new byte[length];
        System.arraycopy(_data, _pos, out, 0, length);
        _pos += length;
        return out;
    }

    public String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    public void skip(int wireType) {
        switch (wireType) {
            case 0:
                readVarint();
                break;
            case 1:
                _pos += 8;
                break;
            case 2:
                readBytes();
                break;
            case 5:
                _pos += 4;
                break;
            default:
                _pos = _data.length;
                break;
        }
        if (_pos > _data.length) {
            _pos = _data.length;
        }
    }

    public static String utf8(byte[] data) {
        try {
            return new String(data, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
