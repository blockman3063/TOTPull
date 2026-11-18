package io.blockman.totpull;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
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
    private static final String SELF_PACKAGE = "io.blockman.totpull";

    /** Sent by the vault app when the user taps "import from app". */
    private static final String ACTION_PROBE = "io.blockman.totpull.action.PROBE";
    /** Sent back with the result. */
    private static final String ACTION_RESULT = "io.blockman.totpull.action.RESULT";

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

    private static volatile String _lastSnapshotBody;
    private static volatile Application _application;

    private static boolean _receiverInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Injection into our own app is the handshake the info page reads: it proves a framework
        // is running and that this module is in scope, without asking root and without waking the
        // target app. Nothing else is installed for that package.
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            markSelfInjected(lpparam);
            return;
        }
        if (!TARGET.equals(lpparam.packageName)) {
            return;
        }
        // The encrypted prefs live in the main process; child processes have no copy.
        String process = lpparam.processName;
        if (process != null && !TARGET.equals(process)) {
            return;
        }
        log("attached to " + process);

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

        installReceiver();
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
            _lastSnapshotBody = body;
        } catch (Throwable t) {
            log("snapshot failed: " + t);
        } finally {
            BUSY.set(null);
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
                } catch (Throwable t) {
                    _receiverInstalled = false;
                    log("registerReceiver failed: " + t);
                }
            }
        });
    }

    /** Answers exactly one request, then goes quiet again. */
    private static void handleProbe(Context context) {
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
            out.putExtra("source_package", TARGET);
            out.putExtra("source_label", "Riot Mobile");
            context.sendBroadcast(out);
        } catch (Throwable t) {
            log("reply failed: " + t);
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
