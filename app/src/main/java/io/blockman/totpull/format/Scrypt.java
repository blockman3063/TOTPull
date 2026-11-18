package io.blockman.totpull.format;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 7914 scrypt, implemented against the JDK/Android standard library only.
 *
 * <p>Deliberately dependency-free: Android ships a trimmed BouncyCastle on the boot
 * classpath, and adding a second one through Gradle tends to break in confusing ways.
 * scrypt is short enough to own. Verified against the RFC 7914 test vectors and
 * Python's {@code hashlib.scrypt}.
 */
final class Scrypt {
    private Scrypt() {
    }

    /** Derives {@code dkLen} bytes. {@code n} must be a power of two greater than 1. */
    static byte[] derive(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        if (n <= 1 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("scrypt N must be a power of two > 1");
        }
        if (r <= 0 || p <= 0 || dkLen <= 0) {
            throw new IllegalArgumentException("scrypt r, p and dkLen must be positive");
        }
        // ROMix indexes V with ints and reads 128*r-byte blocks. Keep hostile files
        // from asking for multi-gigabyte allocations.
        if (n > (1 << 21) || r > 64 || p > 64 || dkLen > 1024
                || (long) 128 * r * p > (1 << 24)) {
            throw new IllegalArgumentException("scrypt parameters out of range");
        }

        byte[] b = pbkdf2(password, salt, 1, p * 128 * r);
        int blockLen = 128 * r;
        for (int lane = 0; lane < p; lane++) {
            int off = lane * blockLen;
            byte[] block = Arrays.copyOfRange(b, off, off + blockLen);
            romix(block, n, r);
            System.arraycopy(block, 0, b, off, blockLen);
            Arrays.fill(block, (byte) 0);
        }
        byte[] out = pbkdf2(password, b, 1, dkLen);
        Arrays.fill(b, (byte) 0);
        return out;
    }

    static byte[] derive(char[] password, byte[] salt, int n, int r, int p, int dkLen) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        try {
            return derive(bytes, salt, n, r, p, dkLen);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- ROMix

    private static void romix(byte[] block, int n, int r) {
        int len = 128 * r;
        byte[][] v = new byte[n][len];
        byte[] x = block.clone();
        for (int i = 0; i < n; i++) {
            v[i] = x.clone();
            blockMix(x, r);
        }
        for (int i = 0; i < n; i++) {
            int j = integerify(x) & (n - 1);
            for (int k = 0; k < len; k++) {
                x[k] ^= v[j][k];
            }
            blockMix(x, r);
        }
        System.arraycopy(x, 0, block, 0, len);
        Arrays.fill(x, (byte) 0);
        for (byte[] row : v) {
            Arrays.fill(row, (byte) 0);
        }
    }

    /** BlockMix with the Salsa20/8 core, straight from the RFC reference. */
    private static void blockMix(byte[] b, int r) {
        byte[] x = new byte[64];
        System.arraycopy(b, b.length - 64, x, 0, 64);
        byte[] y = new byte[b.length];
        for (int i = 0; i < 2 * r; i++) {
            int off = i * 64;
            for (int k = 0; k < 64; k++) {
                x[k] ^= b[off + k];
            }
            salsa208(x);
            System.arraycopy(x, 0, y, i * 64, 64);
        }
        // Y = {Y0, Y2, ..., Y2r-2, Y1, Y3, ..., Y2r-1}
        for (int i = 0; i < r; i++) {
            System.arraycopy(y, i * 128, b, i * 64, 64);
        }
        for (int i = 0; i < r; i++) {
            System.arraycopy(y, i * 128 + 64, b, (i + r) * 64, 64);
        }
        Arrays.fill(x, (byte) 0);
        Arrays.fill(y, (byte) 0);
    }

    /** Last 64-byte block of X, read as a little-endian integer. */
    private static int integerify(byte[] x) {
        int off = x.length - 64;
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (x[off + i] & 0xffL);
        }
        return (int) value;
    }

    private static void salsa208(byte[] block) {
        int[] input = new int[16];
        for (int i = 0; i < 16; i++) {
            input[i] = le32(block, i * 4);
        }
        int[] x = input.clone();
        for (int round = 0; round < 8; round += 2) {
            x[4] ^= rotl(x[0] + x[12], 7);   x[8] ^= rotl(x[4] + x[0], 9);
            x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);    x[13] ^= rotl(x[9] + x[5], 9);
            x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7);  x[2] ^= rotl(x[14] + x[10], 9);
            x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7);  x[7] ^= rotl(x[3] + x[15], 9);
            x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);

            x[1] ^= rotl(x[0] + x[3], 7);    x[2] ^= rotl(x[1] + x[0], 9);
            x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);    x[7] ^= rotl(x[6] + x[5], 9);
            x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7);  x[8] ^= rotl(x[11] + x[10], 9);
            x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9);
            x[14] ^= rotl(x[13] + x[12], 13);x[15] ^= rotl(x[14] + x[13], 18);
        }
        for (int i = 0; i < 16; i++) {
            int value = x[i] + input[i];
            block[i * 4] = (byte) value;
            block[i * 4 + 1] = (byte) (value >>> 8);
            block[i * 4 + 2] = (byte) (value >>> 16);
            block[i * 4 + 3] = (byte) (value >>> 24);
        }
        Arrays.fill(x, 0);
        Arrays.fill(input, 0);
    }

    private static int rotl(int value, int bits) {
        return (value << bits) | (value >>> (32 - bits));
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    // ---------------------------------------------------------------- PBKDF2

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int dkLen) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // SecretKeySpec rejects a zero-length key. HMAC pads keys to the block size,
            // so an empty key and 32 zero bytes produce identical HMAC-SHA256 output.
            // (RFC 7914's first vector uses an empty password.)
            byte[] key = password.length == 0 ? new byte[32] : password;
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            final int hLen = 32;
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] out = new byte[blocks * hLen];
            byte[] input = new byte[salt.length + 4];
            System.arraycopy(salt, 0, input, 0, salt.length);
            for (int block = 1; block <= blocks; block++) {
                input[salt.length] = (byte) (block >>> 24);
                input[salt.length + 1] = (byte) (block >>> 16);
                input[salt.length + 2] = (byte) (block >>> 8);
                input[salt.length + 3] = (byte) block;
                mac.update(input);
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int c = 1; c < iterations; c++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < hLen; k++) {
                        t[k] ^= u[k];
                    }
                }
                System.arraycopy(t, 0, out, (block - 1) * hLen, hLen);
                Arrays.fill(t, (byte) 0);
                Arrays.fill(u, (byte) 0);
            }
            return Arrays.copyOf(out, dkLen);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
