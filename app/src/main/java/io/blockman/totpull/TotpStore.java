package io.blockman.totpull;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persists entries as a single AES/GCM blob in the app's private dir. The key lives in the
 * Android keystore, so the file is useless when copied off the device.
 */
public class TotpStore {
    private static final String TAG = "TotpStore";
    private static final String KEY_ALIAS = "totpull_vault_key";
    private static final String FILE_NAME = "vault.bin";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final File _file;

    public TotpStore(Context context) {
        _file = new File(context.getFilesDir(), FILE_NAME);
    }

    public synchronized java.util.List<TotpEntry> load() {
        java.util.List<TotpEntry> result = new java.util.ArrayList<>();
        if (!_file.exists()) {
            return result;
        }
        try {
            byte[] raw = readAll(_file);
            byte[] plain = decrypt(raw);
            JSONArray array = new JSONArray(new String(plain, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                result.add(TotpEntry.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to load vault", e);
        }
        return result;
    }

    public synchronized void save(java.util.List<TotpEntry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (TotpEntry e : entries) {
                array.put(e.toJson());
            }
            byte[] enc = encrypt(array.toString().getBytes(StandardCharsets.UTF_8));
            try (FileOutputStream fos = new FileOutputStream(_file)) {
                fos.write(enc);
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to save vault", e);
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return buf;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] decrypt(byte[] data) throws Exception {
        SecretKey key = getOrCreateKey();
        if (data.length <= IV_LEN) {
            throw new IOException("vault file too short");
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(data, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[data.length - IV_LEN];
        System.arraycopy(data, IV_LEN, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(ct);
    }

    /** Convenience: base64 of the current blob, handy for backups. */
    public synchronized String exportBase64() {
        try {
            if (!_file.exists()) {
                return "";
            }
            return Base64.encodeToString(readAll(_file), Base64.NO_WRAP);
        } catch (IOException e) {
            return "";
        }
    }
}
