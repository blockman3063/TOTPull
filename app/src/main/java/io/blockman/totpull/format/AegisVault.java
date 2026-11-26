package io.blockman.totpull.format;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts an encrypted Aegis backup (vault version 1).
 *
 * <p>Aegis wraps a 32-byte master key in one or more slots. Password slots (type 1)
 * scrypt the password into a wrapping key, then AES-256-GCM it into {@code slot.key}.
 * The master key in turn AES-256-GCM decrypts the base64 {@code db} field into the
 * same vault-content JSON a plaintext export carries.
 *
 * <p>This class does no UI work and throws a classified error so the caller can show
 * the right message. Biometric slots (type 2) are bound to Aegis' own AndroidKeyStore
 * entry and cannot be opened here — the user must re-export with a password.
 */
public final class AegisVault {

    public enum Reason {
        WRONG_PASSWORD,
        BIOMETRIC_ONLY,
        BAD_FORMAT,
        UNSUPPORTED
    }

    public static final class AegisException extends Exception {
        public final Reason reason;

        AegisException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    private AegisVault() {
    }

    /**
     * A plaintext Aegis export may also carry a {@code header}, so the discriminator
     * must be the type of {@code db}: an object means plaintext, a string means the
     * base64 ciphertext of an encrypted vault.
     */
    public static boolean isEncrypted(String text) {
        if (text == null) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(text.trim());
            return root.opt("db") instanceof String && root.optJSONObject("header") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return the decrypted vault content JSON, i.e. {@code {"version":3,"entries":[...]}}.
     *         Wrap it as {@code {"db": <returned text>}} and feed it to ImportEngine.
     */
    public static String decrypt(String text, char[] password) throws AegisException {
        try {
            JSONObject root = new JSONObject(text.trim());
            if (root.optInt("version", 1) != 1) {
                throw new AegisException(Reason.UNSUPPORTED,
                        "unsupported Aegis vault version", null);
            }
            JSONObject header = root.optJSONObject("header");
            JSONArray slots = header == null ? null : header.optJSONArray("slots");
            String dbBase64 = root.optString("db", "");
            if (slots == null || slots.length() == 0 || dbBase64.isEmpty()) {
                throw new AegisException(Reason.BAD_FORMAT,
                        "missing header slots or db", null);
            }

            byte[] master = null;
            boolean sawBiometric = false;
            int passwordSlots = 0;
            for (int i = 0; i < slots.length(); i++) {
                JSONObject slot = slots.optJSONObject(i);
                if (slot == null) {
                    continue;
                }
                int type = slot.optInt("type", -1);
                if (type == 2) {
                    sawBiometric = true;
                    continue;
                }
                if (type != 1) {
                    continue;
                }
                JSONObject params = slot.optJSONObject("key_params");
                if (params == null) {
                    continue;
                }
                passwordSlots++;
                try {
                    byte[] salt = fromHex(slot.optString("salt", ""));
                    int n = slot.optInt("n", 0);
                    int r = slot.optInt("r", 0);
                    int p = slot.optInt("p", 0);
                    if (n <= 1 || r <= 0 || p <= 0) {
                        continue;
                    }
                    // Java's GCM wants ciphertext||tag; Aegis stores them separately.
                    byte[] input = concat(fromHex(slot.optString("key", "")),
                            fromHex(params.optString("tag", "")));
                    byte[] nonce = fromHex(params.optString("nonce", ""));
                    try {
                        byte[] wrapping = Scrypt.derive(password, salt, n, r, p, 32);
                        try {
                            byte[] keyCandidate = gcmDecrypt(wrapping, nonce, input);
                            if (keyCandidate.length == 32) {
                                master = keyCandidate;
                                break;
                            }
                        } finally {
                            Arrays.fill(wrapping, (byte) 0);
                        }
                    } catch (AEADBadTagException e) {
                        // This slot uses a different password (or is corrupt). Try the next.
                    }
                    // Aegis issue #95: slots created by old builds were wrapped with a key
                    // derived from the legacy, misaligned encoder path. Short passwords
                    // encode identically either way, so only those slots (repaired == false)
                    // need the second attempt; Aegis itself keeps the same legacy function.
                    if (!slot.optBoolean("repaired", false)) {
                        try {
                            byte[] legacy = toBytesOld(password);
                            byte[] wrapping = Scrypt.derive(legacy, salt, n, r, p, 32);
                            Arrays.fill(legacy, (byte) 0);
                            try {
                                byte[] keyCandidate = gcmDecrypt(wrapping, nonce, input);
                                if (keyCandidate.length == 32) {
                                    master = keyCandidate;
                                    break;
                                }
                            } finally {
                                Arrays.fill(wrapping, (byte) 0);
                            }
                        } catch (AEADBadTagException e) {
                            // The legacy derivation does not match this slot either.
                        }
                    }
                } catch (IllegalArgumentException e) {
                    // Hostile scrypt parameters, bad hex/base64. Skip this slot.
                }
            }

            if (master == null) {
                Reason reason;
                if (passwordSlots > 0) {
                    // A password slot exists and refused our password — that is a wrong
                    // password even when a biometric slot is also present. Reporting
                    // biometric-only here used to mislabel perfectly valid backups.
                    reason = Reason.WRONG_PASSWORD;
                } else if (sawBiometric) {
                    reason = Reason.BIOMETRIC_ONLY;
                } else {
                    reason = Reason.BAD_FORMAT;
                }
                throw new AegisException(reason, "no password slot could be opened", null);
            }
            try {
                JSONObject params = header.optJSONObject("params");
                if (params == null) {
                    throw new AegisException(Reason.BAD_FORMAT, "missing header params", null);
                }
                byte[] plain = gcmDecrypt(master,
                        fromHex(params.optString("nonce", "")),
                        concat(fromBase64(dbBase64), fromHex(params.optString("tag", ""))));
                return new String(plain, StandardCharsets.UTF_8);
            } catch (AEADBadTagException e) {
                throw new AegisException(Reason.BAD_FORMAT,
                        "database tag did not verify", e);
            } finally {
                Arrays.fill(master, (byte) 0);
            }
        } catch (AegisException e) {
            throw e;
        } catch (Exception e) {
            throw new AegisException(Reason.BAD_FORMAT,
                    "not a valid encrypted Aegis backup", e);
        }
    }

    /** Java's GCM takes ciphertext||tag; Aegis stores the tag separately. */
    private static byte[] gcmDecrypt(byte[] key, byte[] nonce, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(input);
    }

    /**
     * The legacy byte conversion behind Aegis issue #95: instead of copying the encoded
     * bytes out, it returned the whole backing array of the encoder's buffer, which for
     * passwords whose UTF-8 form crosses a 64-byte block adds trailing bytes — a "misaligned"
     * password, so scrypt produces a different key than the correct conversion would. Kept
     * verbatim and only used for {@code repaired: false} slots, exactly like Aegis does.
     */
    private static byte[] toBytesOld(char[] chars) {
        java.nio.CharBuffer charBuf = java.nio.CharBuffer.wrap(chars);
        java.nio.ByteBuffer byteBuf = StandardCharsets.UTF_8.encode(charBuf);
        return byteBuf.array();
    }

    private static byte[] fromBase64(String value) {
        return Base64.decode(value, Base64.DEFAULT);
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("bad hex");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("bad hex");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
