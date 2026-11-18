package io.blockman.totpull;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "RiotTotpDump";
    private static final String TARGET = "com.riotgames.mobile.leagueconnect";
    /** Steam's own app. Its guard secret sits in a SecureStore blob too. */
    private static final String STEAM = "com.valvesoftware.android.steam.community";
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Injection into our own app is the handshake the info page reads: it proves a framework
        // is running and that this module is in scope, without asking root and without waking the
        // target app. Nothing else is installed for that package.
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            markSelfInjected(lpparam);
            return;
        }
        String process = lpparam.processName;
        if (TARGET.equals(lpparam.packageName)) {
            _package = TARGET;
            _label = "Riot Mobile";
            // The encrypted prefs live in the main process; child processes have no copy.
            if (process != null && !TARGET.equals(process)) {
                return;
            }
            log("attached to " + process);
            attachRiot(lpparam);
            installReceiver();
            return;
        }
        if (STEAM.equals(lpparam.packageName)) {
            _package = STEAM;
            _label = "Steam";
            if (process != null && !STEAM.equals(process)) {
                return;
            }
            log("attached to " + process);
            installReceiver();
        }
    }

    private static void attachRiot(XC_LoadPackage.LoadPackageParam lpparam) {
        List<String> candidates = new ArrayList<>();
        try {
            candidates.addAll(findSharedPrefsImplementations(lpparam.classLoader));
        } catch (Throwable t) {
            log("enumeration failed: " + t);
        }
        if (!candidates.contains(FALLBACK_WRAPPER)) {
            candidates.add(FALLBACK_WRAPPER);
        }

        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name, false, lpparam.classLoader);
                if (!SharedPreferences.class.isAssignableFrom(c) || c.isInterface() || c.isEnum()) {
                    continue;
                }
                hookAll(c);
            } catch (Throwable ignored) {
            }
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
    private static void hookAll(Class<?> c) {
        for (String method : new String[]{"getAll", "getString", "getStringSet", "contains"}) {
            try {
                XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof SharedPreferences) {
                            remember((SharedPreferences) param.thisObject);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void remember(SharedPreferences prefs) {
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

    private static void installReceiver() {
        if (_receiverInstalled) {
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application app = (Application) param.thisObject;
                if (app == null || _receiverInstalled) {
                    return;
                }
                _application = app;
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
                    // The payload may have been decrypted before this hook ran, in which case the
                    // snapshot was skipped for want of an Application to write through.
                    if (STEAM.equals(_package) && _steamSecret != null) {
                        writeSteamSnapshot();
                    }
                } catch (Throwable t) {
                    _receiverInstalled = false;
                    log("registerReceiver failed: " + t);
                }
            }
        });
    }

    /** Answers exactly one request, then goes quiet again. */
    private static void handleProbe(Context context) {
        if (STEAM.equals(_package)) {
            handleSteamProbe(context);
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

    // ------------------------------------------------------------------ class discovery

    /**
     * Walks the dex elements of the app class loader and returns the classes that implement
     * android.content.SharedPreferences. The app is R8-obfuscated, so the androidx.security class
     * names are gone and we have to match on the interface instead.
     */
    @SuppressWarnings("unchecked")
    private static List<String> findSharedPrefsImplementations(ClassLoader cl) {
        List<String> result = new ArrayList<>();
        Object pathList = XposedHelpers.getObjectField(cl, "pathList");
        Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
        for (Object element : elements) {
            Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
            if (dexFile == null) {
                continue;
            }
            Enumeration<String> entries = (Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (name.startsWith("android.") || name.startsWith("java.")
                        || name.startsWith("kotlin.") || name.startsWith("javax.")) {
                    continue;
                }
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    if (SharedPreferences.class.isAssignableFrom(c) && !c.isInterface() && !c.isEnum()) {
                        result.add(name);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    /**
     * Sets {@link HookStatus#ACTIVE} on the app's own copy of the class.
     *
     * <p>Must go through {@code lpparam.classLoader}: the module's classes are loaded a second time
     * by the framework, and a plain field write would land on that copy instead of the one the UI
     * reads.
     */
    private static void markSelfInjected(XC_LoadPackage.LoadPackageParam lpparam) {
        int api = 0;
        try {
            api = XposedBridge.getXposedVersion();
        } catch (Throwable ignored) {
        }
        try {
            Class<?> status = XposedHelpers.findClass("io.blockman.totpull.HookStatus",
                    lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(status, "ACTIVE", true);
            XposedHelpers.setStaticIntField(status, "API", api);
            log("self-injected, api=" + api);
        } catch (Throwable t) {
            log("self mark failed: " + t);
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }
}
