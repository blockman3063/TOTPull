package io.blockman.totpull;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 (TOTP), RFC 4226 (HOTP) and Steam's 5 character variant, plus RFC 4648 base32.
 * Written from the specs on purpose - no Aegis code is used, Aegis is GPLv3.
 */
public final class TotpGenerator {
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY";
    private static final int[] POWERS_OF_TEN = {
            1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000,
    };

    private TotpGenerator() {
    }

    /** Dispatches on entry type. Returns "" for types we cannot generate yet. */
    public static String generate(TotpEntry entry) {
        long now = System.currentTimeMillis();
        if (entry.isHotp()) {
            return generateHotp(entry, entry.counter);
        }
        if (entry.isSteam()) {
            return generateSteam(entry, now);
        }
        if (!entry.isSupported()) {
            return "";
        }
        return generateTotp(entry, now);
    }

    public static String generateTotp(TotpEntry entry, long timeMillis) {
        long counter = (timeMillis / 1000L) / Math.max(1, entry.period);
        return generateCode(base32Decode(entry.secret), counter, entry.getAlgorithmForMac(), entry.digits);
    }

    public static String generateHotp(TotpEntry entry, long counter) {
        return generateCode(base32Decode(entry.secret), counter, entry.getAlgorithmForMac(), entry.digits);
    }

    public static String generateSteam(TotpEntry entry, long timeMillis) {
        long counter = (timeMillis / 1000L) / 30L;
        byte[] hash = hmac(base32Decode(entry.secret), counter, "SHA1");
        if (hash == null || hash.length < 20) {
            return "";
        }
        int start = hash[19] & 0x0f;
        long code = ((hash[start] & 0x7f) << 24) | ((hash[start + 1] & 0xff) << 16)
                | ((hash[start + 2] & 0xff) << 8) | (hash[start + 3] & 0xff);

        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(STEAM_ALPHABET.charAt((int) (code % STEAM_ALPHABET.length())));
            code /= STEAM_ALPHABET.length();
        }
        return sb.toString();
    }

    public static String generateCode(byte[] key, long counter, String algorithm, int digits) {
        if (digits < 5 || digits > 8) {
            digits = 6;
        }
        byte[] hash = hmac(key, counter, algorithm);
        if (hash == null || hash.length < 20) {
            return "";
        }

        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % POWERS_OF_TEN[digits];

        StringBuilder sb = new StringBuilder(Integer.toString(otp));
        while (sb.length() < digits) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] key, long counter, String algorithm) {
        try {
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xff);
                value >>= 8;
            }
            String macAlgorithm = "Hmac" + (algorithm == null ? "SHA1" : algorithm.replace("-", ""));
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(key, "RAW"));
            return mac.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** Seconds left in the current period. */
    public static long secondsLeft(int period, long timeMillis) {
        long p = Math.max(1, period);
        return p - ((timeMillis / 1000L) % p);
    }

    /** 0..1000, how much of the current period is left. */
    public static int progressLeft(int period, long timeMillis) {
        long p = Math.max(1, period);
        long left = secondsLeft(period, timeMillis);
        return (int) Math.max(0, Math.min(1000, left * 1000 / p));
    }

    public static boolean isValidBase32(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String t = normalizeSecret(s);
        if (t.isEmpty() || t.length() % 8 != 0) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (BASE32_ALPHABET.indexOf(t.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    public static byte[] base32Decode(String s) {
        if (s == null) {
            return new byte[0];
        }
        String t = normalizeSecret(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < t.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(t.charAt(i));
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    public static String normalizeSecret(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
    }

    /** RFC 4648 base32 without padding. */
    public static String base32Encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return sb.toString();
    }

    /**
     * Accepts base32, hex or base64 and returns base32, so everything downstream only
     * has to deal with one shape.
     *
     * <p>Bitwarden / andOTP / Aegis all emit base32, but Steam desktop authenticator
     * {@code .maFile}s and a number of older tools emit base64, and some emit hex.
     */
    public static String normalizeAnySecret(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace(" ", "").replace("-", "").replace("=", "").trim();
        if (cleaned.isEmpty()) {
            return "";
        }

        boolean allHex = cleaned.length() % 2 == 0;
        for (int i = 0; i < cleaned.length() && allHex; i++) {
            char c = Character.toLowerCase(cleaned.charAt(i));
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                allHex = false;
            }
        }
        if (allHex && cleaned.length() >= 16) {
            byte[] bytes = new byte[cleaned.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
            }
            return base32Encode(bytes);
        }

        String base32 = normalizeSecret(cleaned);
        if (isValidBase32(base32)) {
            return base32;
        }

        // Not valid base32 -> try base64. '8' / '9' / '+' / '/' cannot appear in base32,
        // so anything containing them is a strong base64 signal.
        byte[] decoded = base64Decode(raw.trim());
        if (decoded.length >= 10) {
            return base32Encode(decoded);
        }
        return base32;
    }

    /** Lenient base64: standard and URL-safe alphabets, padding optional. */
    public static byte[] base64Decode(String s) {
        if (s == null) {
            return new byte[0];
        }
        String t = s.replace("-", "+").replace("_", "/").replaceAll("[^A-Za-z0-9+/]", "");
        if (t.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < t.length(); i++) {
            int v = BASE64_ALPHABET.indexOf(t.charAt(i));
            if (v < 0) {
                return new byte[0];
            }
            buffer = (buffer << 6) | v;
            bitsLeft += 6;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }


    /** Pretty prints a code: 123456 -> 123 456. Steam codes are left alone. */
    public static String format(String code, boolean isSteam) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        if (isSteam || code.length() < 6) {
            return code;
        }
        int mid = code.length() / 2;
        return code.substring(0, mid) + " " + code.substring(mid);
    }
}
