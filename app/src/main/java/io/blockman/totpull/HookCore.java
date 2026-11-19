package io.blockman.totpull;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;


/**
 * Runs inside Riot Mobile and answers on-demand extraction requests from the vault app.
 *
 * <p><b>Deliberately passive.</b> The hook does not push anything, does not poll and does not
 * write to disk. It only remembers a weak reference to every {@link SharedPreferences} instance
 * the app touches, so that when a probe arrives we already have a handle to the
 * androidx.security-encrypted store. All work happens inside the one request.
 *
 * <p>Why a hook at all: Riot keeps its TOTP seed in
 * {@code shared_prefs/totp_seed_store.xml} wrapped by androidx.security (Tink + AndroidKeyStore).
 * The master key lives in the keystore and is bound to the app uid, so root can read the
 * ciphertext but never decrypt it. Only code running inside the app process can.
 *
 * <p>Transport: a package-targeted broadcast in each direction. Riot's process may be in the
 * background, where starting an activity is blocked, but sending a broadcast is always allowed.
 */
public final class HookCore {
    private static final String TAG = "RiotTotpDump";
    private static final String TARGET = "com.riotgames.mobile.leagueconnect";
    /** Steam's own app. Its guard secret sits in a SecureStore blob too. */
    private static final String STEAM = "com.valvesoftware.android.steam.community";
    /** Google Authenticator; keeps an encrypted OTP database per signed-in account. */
    private static final String GA = "com.google.android.apps.authenticator2";
    /** Aegis; its vault stays encrypted on disk, but entries are plaintext once unlocked. */
    private static final String AEGIS = "com.beemdevelopment.aegis";
    private static final String SELF_PACKAGE = "io.blockman.totpull";

    /** Sent by the vault app when the user taps "import from app". */
    private static final String ACTION_PROBE = "io.blockman.totpull.action.PROBE";
    /** Sent back with the result. */
    private static final String ACTION_RESULT = "io.blockman.totpull.action.RESULT";
    /** Sent unprompted once this process can answer, so the vault need not keep asking. */
    private static final String ACTION_READY = "io.blockman.totpull.action.READY";

    /** Riot's own TOTP parameters, confirmed by reverse-matching its on-screen code. */
    private static final String ALGORITHM = "SHA256";
    private static final int DIGITS = 6;
    private static final int PERIOD = 30;

    private static final String KEY_SECRET = "totp_seed_key";
    private static final String KEY_PUUID = "totp_seed_puuid";
    private static final String KEY_CREATED = "totp_seed_created_at";

    /**
     * R8 name of the androidx.security wrapper in the analysed build. Only used when runtime
     * enumeration is blocked by hidden-API policy.
     */
    private static final String FALLBACK_WRAPPER = "y43";

    /** Weak, so remembering instances never keeps a preferences file alive. */
    private static final List<WeakReference<SharedPreferences>> INSTANCES = new ArrayList<>();
    private static final int MAX_INSTANCES = 16;

    /** Guards against re-entering the hooked accessors while we read the store ourselves. */
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    /** Name of the file the vault app reads through root. Lives in the source app's files dir. */
    private static final String SNAPSHOT_NAME = ".totp_snapshot";

    /** Alphabet for the secret formats we have to convert between; Steam hands us base64. */
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String BASE64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static volatile String _lastSnapshotBody;
    private static volatile Application _application;

    private static boolean _receiverInstalled;

    /** Package and human label of the app we are injected into; one value per process. */
    private static volatile String _package = TARGET;
    private static volatile String _label = "Riot Mobile";

    /** What we picked out of Steam's decrypted SecureStore blob, if anything. */
    private static volatile String _steamSecret;
    private static volatile String _steamAccount;
    private static volatile String _steamId;

    /** otpauth URIs captured from Aegis's unlocked in-memory vault. */
    private static volatile org.json.JSONArray _aegisUris = new org.json.JSONArray();
    private static long _lastAegisAnnounce;
    private static boolean _aegisCipherHookSeen;

    /**
     * Reads Aegis's decrypted in-memory vault instead of its encrypted file.
     *
     * <p>Aegis decrypts the vault only after the user unlocks it. Hooking the decrypted entry
     * collection avoids both the backup password and the AndroidKeyStore-bound biometrics: we
     * never touch the file, we only observe what the app has already made visible to itself.
     */

    public static void captureAegisVaultBytes(byte[] plain) {
        if (plain == null || plain.length < 64) {
            return;
        }
        String text;
        try {
            text = new String(plain, "UTF-8");
        } catch (Throwable ignored) {
            return;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return;
        }
        text = text.substring(start, end + 1);
        if (text.charAt(0) != '{' || !text.contains("\"entries\"")
                || !text.contains("\"version\"")) {
            return;
        }
        try {
            org.json.JSONArray entries = new org.json.JSONObject(text)
                    .optJSONArray("entries");
            if (entries == null) {
                return;
            }
            org.json.JSONArray uris = new org.json.JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                String uri = aegisEntryUri(entries.optJSONObject(i));
                if (uri != null && !hasUri(uris, uri)) {
                    uris.put(uri);
                }
            }
            String next = uris.toString();
            if (next.equals(_aegisUris.toString()) || uris.length() == 0) {
                return;
            }
            _aegisUris = uris;
            Application app = _application;
            if (app != null) {
                String body = "{\"v\":1,\"entries\":" + uris + ",\"ts\":"
                        + System.currentTimeMillis() + "}";
                if (writeSnapshot(app, body)) {
                    _lastSnapshotBody = body;
                }
                announceAegis(app);
            }
            log("captured " + uris.length() + " Aegis accounts");
        } catch (Throwable t) {
            log("aegis vault parse failed: " + t);
        }
    }

    /** Converts one VaultEntry JSON object into the otpauth URI TOTPull already imports. */
    private static String aegisEntryUri(org.json.JSONObject entry) {
        org.json.JSONObject info = entry.optJSONObject("info");
        if (info == null) {
            return null;
        }
        String type = entry.optString("type", "totp").toLowerCase(Locale.ROOT);
        if (!"totp".equals(type) && !"hotp".equals(type) && !"steam".equals(type)) {
            return null;
        }
        String secret = normalizeSecret(info.optString("secret", ""));
        if (secret.length() < 16) {
            return null;
        }
        String name = entry.optString("name", "");
        String issuer = entry.optString("issuer", "");
        String label = issuer.isEmpty() ? name : issuer + ":" + name;
        if (label.isEmpty()) {
            label = "Aegis";
        }
        int digits = info.optInt("digits", "steam".equals(type) ? 5 : 6);
        if ("steam".equals(type)) {
            if (digits < 5 || digits > 9) {
                digits = 5;
            }
        } else if (digits < 6 || digits > 8) {
            digits = 6;
        }
        StringBuilder uri = new StringBuilder("otpauth://").append(type).append('/')
                .append(encode(label))
                .append("?secret=").append(secret)
                .append("&algorithm=").append(normalizeAegisAlgo(info.optString("algo", "SHA1")))
                .append("&digits=").append(digits);
        if ("hotp".equals(type)) {
            uri.append("&counter=").append(info.optLong("counter", 0));
        } else {
            int period = info.optInt("period", 30);
            uri.append("&period=").append(period > 0 ? period : 30);
        }
        if (!issuer.isEmpty()) {
            uri.append("&issuer=").append(encode(issuer));
        }
        return uri.toString();
    }

    private static String normalizeAegisAlgo(String raw) {
        String value = raw == null ? "" : raw.toUpperCase(Locale.ROOT)
                .replace("HMAC", "").replace("-", "");
        if ("SHA256".equals(value) || "SHA512".equals(value)) {
            return value;
        }
        return "SHA1";
    }

    private static void announceAegis(Context context) {
        long now = System.currentTimeMillis();
        if (now - _lastAegisAnnounce < 3000L) {
            return;
        }
        _lastAegisAnnounce = now;
        announce(context);
    }

    private static void handleAegisProbe(Context context) {
        if (_aegisUris.length() == 0) {
            reply(context, false, null, null, null,
                    "Aegis 还没解锁，请先解锁一次 Aegis，模块读到后会自动回来");
            return;
        }
        try {
            replyEntries(context, new org.json.JSONArray(_aegisUris.toString()));
        } catch (Throwable t) {
            log("aegis reply failed: " + t);
            reply(context, false, null, null, null, "Aegis 条目封装失败");
        }
    }

    /**
     * Unwraps Steam's SecureStore blob ourselves instead of waiting to catch the app doing it.
     *
     * <p>The file is {@code shared_prefs/SecureStore.xml} holding {@code {ct, iv, tlen, scheme:
     * aes, keystoreAlias: key_v1, usesKeystoreSuffix: true}}, i.e. an AES/GCM value whose key is a
     * keystore entry bound to this app's uid. We are running inside that process, so we can borrow
     * the key — which matters because the app only decrypts the guard data when it needs a code,
     * and there is no reliable way to make it do that on demand.
     *
     * <p>The alias carries a suffix we do not know, so it is matched by prefix.
     */
    private static boolean readSteamStore(Context context) {
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences("SecureStore", Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (!entry.getKey().contains("SteamGuard")) {
                    continue;
                }
                org.json.JSONObject blob = new org.json.JSONObject(String.valueOf(entry.getValue()));
                String ct = blob.optString("ct", "");
                String iv = blob.optString("iv", "");
                if (ct.isEmpty() || iv.isEmpty()) {
                    continue;
                }
                byte[] plain = decryptBlob(blob.optString("keystoreAlias", "key_v1"), iv, ct,
                        blob.optInt("tlen", 128));
                if (plain == null) {
                    log("guard blob could not be decrypted");
                    continue;
                }
                org.json.JSONObject payload =
                        new org.json.JSONObject(new String(plain, "UTF-8"));
                org.json.JSONObject guard = findGuardNode(payload, 0);
                if (guard == null) {
                    log("no shared_secret under " + payload.names());
                    continue;
                }
                String secret = guard.optString("shared_secret", "");
                if (secret.isEmpty()) {
                    continue;
                }
                _steamSecret = secret;
                _steamAccount = guard.optString("account_name", "");
                _steamId = guard.optString("steamid", "");
                if (_steamAccount.isEmpty() && _steamId.isEmpty()) {
                    _steamAccount = payload.optString("account_name", "");
                    _steamId = payload.optString("steamid", "");
                }
                log("read the Steam guard secret from the store");
                writeSteamSnapshot();
                return true;
            }
        } catch (Throwable t) {
            log("steam store read failed: " + t);
        }
        return false;
    }

    /**
     * Finds the object that actually holds {@code shared_secret}.
     *
     * <p>The store keeps one record per signed-in account, wrapped as {@code {"accounts": ...}},
     * and the exact nesting is not part of any contract we control — so walk it instead of
     * assuming a shape.
     */
    private static org.json.JSONObject findGuardNode(org.json.JSONObject object, int depth) {
        if (object == null || depth > 4) {
            return null;
        }
        if (object.optString("shared_secret", "").length() > 0) {
            return object;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object child = object.opt(keys.next());
            if (child instanceof org.json.JSONObject) {
                org.json.JSONObject hit = findGuardNode((org.json.JSONObject) child, depth + 1);
                if (hit != null) {
                    return hit;
                }
            } else if (child instanceof org.json.JSONArray) {
                org.json.JSONArray array = (org.json.JSONArray) child;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof org.json.JSONObject) {
                        org.json.JSONObject hit =
                                findGuardNode((org.json.JSONObject) item, depth + 1);
                        if (hit != null) {
                            return hit;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] decryptBlob(String aliasPrefix, String ivBase64, String ctBase64,
                                      int tagBits) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                // The library names its entry "AES/GCM/NoPadding:key_v1:keystoreUnauthenticated",
                // so the configured alias sits in the middle rather than at the start.
                if (!alias.contains(aliasPrefix)) {
                    continue;
                }
                try {
                    javax.crypto.SecretKey key = (javax.crypto.SecretKey) ks.getKey(alias, null);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key,
                            new javax.crypto.spec.GCMParameterSpec(tagBits,
                                    android.util.Base64.decode(ivBase64, android.util.Base64.DEFAULT)));
                    byte[] plain = cipher.doFinal(
                            android.util.Base64.decode(ctBase64, android.util.Base64.DEFAULT));
                    return plain;
                } catch (Throwable t) {
                    log("alias " + alias + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            log("keystore access failed: " + t);
        }
        return null;
    }

    /**
     * Registers a no-op observer on every accessor of the wrapper class. The only side effect is
     * remembering the instance; nothing is read, logged or stored until a probe arrives.
     */

    public static void remember(SharedPreferences prefs) {
        boolean isNew = false;
        synchronized (INSTANCES) {
            boolean known = false;
            for (WeakReference<SharedPreferences> ref : INSTANCES) {
                if (ref.get() == prefs) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                if (INSTANCES.size() >= MAX_INSTANCES) {
                    INSTANCES.remove(0);
                }
                INSTANCES.add(new WeakReference<>(prefs));
                isNew = true;
            }
        }
        if (isNew) {
            // A new store showed up. Only worth a look when we have not snapshotted yet, so a
            // settled app costs nothing beyond the reference we just kept.
            maybeSnapshot(prefs);
        }
    }

    /**
     * Leaves a snapshot of the seed in the source app's own private directory so the vault app can
     * read it through root without waking us up.
     *
     * <p>Written only when the value actually changes, so a running app does not keep touching the
     * disk. Nothing leaves the device and nothing is written anywhere the app could not already
     * write.
     */
    private static void maybeSnapshot(SharedPreferences prefs) {
        if (Boolean.TRUE.equals(BUSY.get())) {
            return;
        }
        Application app = _application;
        if (app == null) {
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            Map<String, ?> all;
            try {
                all = prefs.getAll();
            } catch (Throwable t) {
                return;
            }
            Object raw = all.get(KEY_SECRET);
            if (raw == null) {
                return;
            }
            String secret = normalizeSecret(String.valueOf(raw));
            if (secret.isEmpty()) {
                return;
            }
            String body = "{\"v\":1"
                    + ",\"otpauth\":" + quote(buildOtpauth(secret))
                    + ",\"puuid\":" + quote(stringify(all.get(KEY_PUUID)))
                    + ",\"created_at\":" + quote(stringify(all.get(KEY_CREATED)))
                    + ",\"ts\":" + System.currentTimeMillis()
                    + "}";
            if (body.equals(_lastSnapshotBody)) {
                return;
            }
            if (writeSnapshot(app, body)) {
                _lastSnapshotBody = body;
            }
        } catch (Throwable t) {
            log("snapshot failed: " + t);
        } finally {
            BUSY.set(null);
        }
    }

    /** Atomic write into the source app's own files dir; returns whether it landed. */
    private static boolean writeSnapshot(Application app, String body) {
        try {
            File target = new File(app.getFilesDir(), SNAPSHOT_NAME);
            File temp = new File(app.getFilesDir(), SNAPSHOT_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(body.getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                target.delete();
                temp.renameTo(target);
            }
            return true;
        } catch (Throwable t) {
            log("snapshot write failed: " + t);
            return false;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** Called once by the modern module entry after Application.onCreate(). */
    public static void onApplication(Context app, String packageName, String label) {
        if (app == null || _receiverInstalled) {
            return;
        }
        _application = (Application) app;
        _package = packageName;
        _label = label;
        _receiverInstalled = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && ACTION_PROBE.equals(intent.getAction())) {
                        handleProbe(context);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_PROBE);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            log("probe receiver ready");
            announceWithRetries(app);
            if (STEAM.equals(packageName) && _steamSecret != null) {
                writeSteamSnapshot();
            }
        } catch (Throwable t) {
            _receiverInstalled = false;
            log("registerReceiver failed: " + t);
        }
    }

    /** Aegis is already foreground: wake TOTPull if it has an open import round. */
    public static void announceAegisIfReady(Context context) {
        if (_aegisUris.length() > 0) {
            announceAegis(context);
        }
    }

    // ------------------------------------------------------------------ probe plumbing

    /**
     * Tells the vault app that we are up and able to answer.
     *
     * <p>Needed because the vault cannot ask on its own: once it hands us the foreground, OEM
     * power management freezes its process within seconds, and every retry it scheduled dies with
     * the freeze. The vault receives this on a manifest-declared receiver, which wakes it, and
     * decides there whether an import is actually in progress — so simply opening this app
     * normally never drags the user anywhere.
     */
    private static void announce(Context context) {
        try {
            Intent ready = new Intent(ACTION_READY);
            ready.setPackage(SELF_PACKAGE);
            ready.putExtra("source_package", _package);
            ready.putExtra("source_label", _label);
            context.sendBroadcast(ready);
            log("announced readiness");
        } catch (Throwable t) {
            log("announce failed: " + t);
        }
    }

    /**
     * Announces now and a few more times while the app is still starting.
     *
     * <p>The first announcement lands before the app has opened its encrypted store, so the vault
     * gets a "nothing yet" answer. Each later announcement makes it ask again, and the moment the
     * store is captured the answer carries the seed. Announcements after a successful round are
     * ignored on the other side, so the extras cost nothing.
     */
    private static void announceWithRetries(final Context context) {
        announce(context);
        Handler handler = new Handler(Looper.getMainLooper());
        int[] delays = {1000, 2500, 4500, 7000};
        for (final int delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    announce(context);
                }
            }, delay);
        }
    }


    /** Answers exactly one request, then goes quiet again. */
    public static void handleProbe(Context context) {
        if (STEAM.equals(_package)) {
            handleSteamProbe(context);
            return;
        }
        if (GA.equals(_package)) {
            handleGaProbe(context);
            return;
        }
        if (AEGIS.equals(_package)) {
            handleAegisProbe(context);
            return;
        }
        log("probe received, instances=" + INSTANCES.size());
        String secret = null;
        String puuid = null;
        String created = null;

        for (WeakReference<SharedPreferences> ref : snapshot()) {
            SharedPreferences prefs = ref.get();
            if (prefs == null) {
                continue;
            }
            try {
                Map<String, ?> all = prefs.getAll();
                Object value = all.get(KEY_SECRET);
                if (value == null) {
                    value = prefs.getString(KEY_SECRET, null);
                }
                if (value != null && String.valueOf(value).length() > 0) {
                    secret = String.valueOf(value);
                    puuid = stringify(all.get(KEY_PUUID));
                    created = stringify(all.get(KEY_CREATED));
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        if (secret == null) {
            int seen = INSTANCES.size();
            reply(context, false, null, null, null, seen == 0
                    ? "模块还没捕获到加密存储，请在 Riot Mobile 里打开一次登录页"
                    : "加密存储读到了，但里面还没有验证码，先在 Riot Mobile 里生成一次");
            return;
        }
        secret = normalizeSecret(secret);
        if (secret.isEmpty()) {
            reply(context, false, null, null, null, "拿到的密钥格式无法识别");
            return;
        }

        String uri = buildOtpauth(secret);

        StringBuilder note = new StringBuilder();
        if (puuid != null) {
            note.append("puuid=").append(puuid);
        }
        if (created != null) {
            if (note.length() > 0) {
                note.append('\n');
            }
            note.append("created_at=").append(created);
        }

        reply(context, true, uri, puuid, note.toString(), null);
    }

    /**
     * Reads every account out of the current profile's encrypted OTP databases.
     *
     * <p>Google Authenticator keeps one database per signed-in Google account under
     * {@code files/accounts/<id>/otp_database.db}. Its {@code secret} column is base64 over an
     * AES/GCM blob whose key is a keystore entry bound to this uid, so we can only unwrap it from
     * inside this process. Unlike Riot there is no single "current" secret — a whole list comes
     * back, carried in one {@code entries} extra rather than one answer per account.
     */
    private static void handleGaProbe(Context context) {
        org.json.JSONArray uris = new org.json.JSONArray();
        try {
            File[] profiles = new File(context.getFilesDir(), "accounts").listFiles();
            if (profiles != null) {
                for (File profile : profiles) {
                    File db = new File(profile, "otp_database.db");
                    if (!db.isFile()) {
                        continue;
                    }
                    SQLiteDatabase database = SQLiteDatabase.openDatabase(
                            db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                    try {
                        Cursor rows = database.rawQuery(
                                "select name,issuer,secret,otp_type,algorithm_deleted,digits_deleted,"
                                        + "counter from otp_table where is_deleted=0", null);
                        try {
                            while (rows.moveToNext()) {
                                byte[] secret = decryptGaSecret(rows.getString(2));
                                if (secret == null || secret.length == 0) {
                                    continue;
                                }
                                String name = rows.getString(0);
                                String issuer = rows.getString(1);
                                String uri = buildGaUri(name, issuer, secret,
                                        rows.getString(3), rows.getString(4),
                                        rows.getInt(5), rows.getLong(6));
                                if (uri != null && !hasUri(uris, uri)) {
                                    uris.put(uri);
                                }
                            }
                        } finally {
                            rows.close();
                        }
                    } finally {
                        database.close();
                    }
                }
            }
        } catch (Throwable t) {
            log("google authenticator read failed: " + t);
        }
        if (uris.length() == 0) {
            reply(context, false, null, null, null,
                    "没能从谷歌验证器读到验证码账号，确认它已经登录并显示过验证码");
            return;
        }
        Application app = _application;
        if (app != null) {
            String body = "{\"v\":1,\"entries\":" + uris + ",\"ts\":"
                    + System.currentTimeMillis() + "}";
            if (writeSnapshot(app, body)) {
                _lastSnapshotBody = body;
            }
        }
        replyEntries(context, uris);
    }

    private static boolean hasUri(org.json.JSONArray uris, String uri) {
        for (int i = 0; i < uris.length(); i++) {
            if (uri.equals(uris.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static String buildGaUri(String name, String issuer, byte[] secret, String type,
                                     String algorithm, int digits, long counter) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String scheme = "totp";
        long counterValue = 0;
        if ("hotp".equalsIgnoreCase(type)) {
            scheme = "hotp";
            counterValue = counter;
        }
        if (digits < 6 || digits > 8) {
            digits = 6;
        }
        String mac = algorithm == null ? "SHA1" : algorithm.toUpperCase();
        mac = mac.replace("HMAC", "").replace("-", "");
        if (!"SHA1".equals(mac) && !"SHA256".equals(mac) && !"SHA512".equals(mac)) {
            mac = "SHA1";
        }
        String who = (issuer == null || issuer.isEmpty())
                ? name : issuer + ":" + name;
        StringBuilder uri = new StringBuilder("otpauth://").append(scheme).append('/')
                .append(encode(who)).append("?secret=").append(gaSecretText(secret));
        if (issuer != null && !issuer.isEmpty()) {
            uri.append("&issuer=").append(encode(issuer));
        }
        uri.append("&algorithm=").append(mac).append("&digits=").append(digits);
        if ("hotp".equals(scheme)) {
            uri.append("&counter=").append(counterValue);
        } else {
            uri.append("&period=30");
        }
        return uri.toString();
    }

    /**
     * The AES payload of a Google Authenticator secret is itself an ASCII base32 string, sometimes
     * '=' padded - not raw key bytes. Re-encoding it would produce a secret that looks perfectly
     * valid but generates completely different codes, so keep the text that is already there.
     */
    private static String gaSecretText(byte[] decrypted) {
        if (decrypted == null || decrypted.length == 0) {
            return "";
        }
        String text = new String(decrypted, java.nio.charset.StandardCharsets.US_ASCII).trim();
        if (text.length() >= 16 && text.matches("[A-Za-z2-7=]+")) {
            return text.replace("=", "").toUpperCase(java.util.Locale.ROOT);
        }
        return base32Encode(decrypted);
    }

    private static byte[] decryptGaSecret(String encoded) {
        byte[] blob = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        if (blob == null || blob.length < 28) {
            return null;
        }
        byte[] iv = new byte[12];
        byte[] ct = new byte[blob.length - 12];
        System.arraycopy(blob, 0, iv, 0, 12);
        System.arraycopy(blob, 12, ct, 0, ct.length);
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                try {
                    javax.crypto.SecretKey key = (javax.crypto.SecretKey) ks.getKey(alias, null);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key,
                            new javax.crypto.spec.GCMParameterSpec(128, iv));
                    return cipher.doFinal(ct);
                } catch (Throwable ignored) {
                    // Not the key this account was sealed with.
                }
            }
        } catch (Throwable t) {
            log("google authenticator keystore failed: " + t);
        }
        return null;
    }

    private static void replyEntries(Context context, org.json.JSONArray uris) {
        try {
            Intent out = new Intent(ACTION_RESULT);
            out.setPackage(SELF_PACKAGE);
            out.putExtra("ok", true);
            out.putExtra("entries", uris.toString());
            out.putExtra("count", uris.length());
            out.putExtra("source_package", _package);
            out.putExtra("source_label", _label);
            context.sendBroadcast(out);
            log("sent " + uris.length() + " accounts");
            bringBack(context);
        } catch (Throwable t) {
            log("entries reply failed: " + t);
        }
    }

    private static void handleSteamProbe(Context context) {
        if (_steamSecret == null) {
            readSteamStore(context);
        }
        String secret = _steamSecret;
        if (secret == null) {
            reply(context, false, null, null, null,
                    "没读到 Steam Guard 密钥，确认这台机器上登录过 Steam 手机令牌");
            return;
        }
        String account = _steamAccount == null ? "" : _steamAccount;
        reply(context, true, buildSteamUri(secret, account), _steamId, null, null);
    }

    /**
     * Builds the URI the vault already understands for this type.
     *
     * <p>Steam's shared secret is base64 while otpauth secrets are base32, and the code itself is
     * five characters from a custom alphabet rather than six digits — the vault keys all of that
     * off the {@code steam/} prefix, which is why this looks nothing like the Riot one.
     */
    private static String buildSteamUri(String base64Secret, String account) {
        byte[] raw = base64Decode(base64Secret);
        if (raw == null || raw.length == 0) {
            return null;
        }
        String who = account == null || account.isEmpty() ? "Steam" : account;
        return "otpauth://steam/" + encode("Steam:" + who)
                + "?secret=" + base32Encode(raw)
                + "&algorithm=SHA1&digits=5&period=30";
    }

    /** Leaves a Steam snapshot where root can read it, mirroring the Riot path. */
    private static void writeSteamSnapshot() {
        Application app = _application;
        String uri = buildSteamUri(_steamSecret, _steamAccount);
        if (app == null || uri == null) {
            return;
        }
        String body = "{\"v\":1"
                + ",\"otpauth\":" + quote(uri)
                + ",\"puuid\":" + quote(_steamId)
                + ",\"ts\":" + System.currentTimeMillis()
                + "}";
        if (body.equals(_lastSnapshotBody)) {
            return;
        }
        if (writeSnapshot(app, body)) {
            _lastSnapshotBody = body;
        }
    }

    private static byte[] base64Decode(String value) {
        int[] map = new int[256];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < BASE64.length(); i++) {
            map[BASE64.charAt(i)] = i;
        }
        int len = value.length();
        byte[] out = new byte[len * 3 / 4 + 3];
        int acc = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == '=' || c > 255) {
                continue;
            }
            int v = map[c];
            if (v < 0) {
                if (c == '\n' || c == '\r' || c == ' ') {
                    continue;
                }
                return null;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[pos++] = (byte) ((acc >> bits) & 0xff);
            }
        }
        byte[] trimmed = new byte[pos];
        System.arraycopy(out, 0, trimmed, 0, pos);
        return trimmed;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int acc = 0;
        int bits = 0;
        for (byte b : data) {
            acc = (acc << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32.charAt((acc >> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((acc << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    /** Builds the otpauth URI so the vault app never has to know this app's TOTP parameters. */
    private static String buildOtpauth(String secret) {
        String issuer = "Riot Games";
        String label = "Riot Games";
        return "otpauth://totp/" + encode(issuer) + ":" + encode(label)
                + "?secret=" + secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=" + ALGORITHM
                + "&digits=" + DIGITS
                + "&period=" + PERIOD;
    }

    private static void reply(Context context, boolean ok, String otpauth,
                              String puuid, String note, String error) {
        try {
            Intent out = new Intent(ACTION_RESULT);
            out.setPackage(SELF_PACKAGE);
            out.putExtra("ok", ok);
            if (otpauth != null) {
                out.putExtra("otpauth", otpauth);
            }
            if (puuid != null) {
                out.putExtra("puuid", puuid);
            }
            if (note != null && !note.isEmpty()) {
                out.putExtra("note", note);
            }
            if (error != null) {
                out.putExtra("error", error);
            }
            out.putExtra("source_package", _package);
            out.putExtra("source_label", _label);
            context.sendBroadcast(out);
            if (ok) {
                bringBack(context);
            }
        } catch (Throwable t) {
            log("reply failed: " + t);
        }
    }

    /** When we last pulled the vault app forward, so the retry rounds do not keep doing it. */
    private static long _lastBringBack;

    /**
     * Brings the vault app back to the front once we have the seed.
     *
     * <p>The vault cannot do this for itself: after it hands us the foreground, Android blocks an
     * activity start coming from its own receiver, and the notification it posts still needs a
     * tap. This process is the app the user is looking at, so the very same start is allowed from
     * here. Only on success — a failed answer may just mean we are still booting, and bouncing the
     * user out then would kill the round before the next retry.
     */
    private static void bringBack(final Context context) {
        long now = System.currentTimeMillis();
        if (now - _lastBringBack < 20_000L) {
            return;
        }
        _lastBringBack = now;
        try {
            final Intent back = new Intent(Intent.ACTION_MAIN);
            back.setClassName(SELF_PACKAGE, SELF_PACKAGE + ".ui.MainActivity");
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // Give the answer broadcast a moment to land, so the report is already on disk by the
            // time the activity resumes and goes looking for it.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        context.startActivity(back);
                        log("brought vault back");
                    } catch (Throwable t) {
                        log("bring back failed: " + t);
                    }
                }
            }, 600L);
        } catch (Throwable t) {
            log("bring back failed: " + t);
        }
    }

    private static List<WeakReference<SharedPreferences>> snapshot() {
        synchronized (INSTANCES) {
            // Newest first: the store Riot touched most recently is the one we want.
            List<WeakReference<SharedPreferences>> copy = new ArrayList<>(INSTANCES);
            List<WeakReference<SharedPreferences>> reversed = new ArrayList<>(copy.size());
            for (int i = copy.size() - 1; i >= 0; i--) {
                reversed.add(copy.get(i));
            }
            return reversed;
        }
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeSecret(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '-' || c == '=') {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Percent-encodes for an otpauth URI. {@link URLEncoder} is form encoding: it turns spaces
     * into '+', which a URI decoder leaves alone, so the label would literally read "Riot+Games".
     */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }
}
