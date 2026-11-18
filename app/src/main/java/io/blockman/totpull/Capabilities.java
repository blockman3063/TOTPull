package io.blockman.totpull;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * What this device lets us do.
 *
 * <p>Two independent capabilities:
 *
 * <ul>
 *   <li><b>Module</b> - the Xposed module runs inside the source app and can decrypt. Without it
 *       there is no way to read another app's codes at all: the stores are encrypted with keys
 *       bound to the owning uid, so no amount of privilege on our side helps.</li>
 *   <li><b>Root</b> - not a replacement, only a faster transport. The module drops a snapshot in
 *       the source app's private directory and we read it directly, instead of asking over a
 *       broadcast that Android may defer for minutes.</li>
 * </ul>
 *
 * <p>Root detection is deliberately careful. Asking a root manager for a shell can either answer
 * immediately (already granted), fail immediately (no root at all), or <i>hang</i> (the request is
 * queued in a manager that the ROM has frozen). Treating that hang as a denial is what makes naive
 * detectors report "no root" forever, so it is tracked as its own state and never cached as a
 * refusal.
 */
public final class Capabilities {
    /** Package we know how to extract from today. */
    public static final String RIOT_PACKAGE = "com.riotgames.mobile.leagueconnect";

    /** Snapshot the module leaves behind inside the source app's private directory. */
    public static final String SNAPSHOT_NAME = ".totp_snapshot";

    public static final int ROOT_UNKNOWN = -1;
    public static final int ROOT_DENIED = 0;
    public static final int ROOT_GRANTED = 1;
    /** {@code su} exists but never answered within the timeout - usually a frozen manager. */
    public static final int ROOT_TIMEOUT = 2;

    private static final String PREFS = "capabilities";
    private static final String KEY_ROOT = "root_state";
    private static final String KEY_MODULE = "module_ready";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_FAST_PATH = "fast_path";

    /**
     * Where a root shell may live. KernelSU ships /system/bin/su, Magisk usually has its own, and
     * a few ROMs put it elsewhere. Checked before falling back to a PATH lookup.
     */
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
    };

    /** How long we wait for a shell that is already authorised. Generous, but bounded. */
    private static final int ROOT_QUICK_TIMEOUT = 5000;

    private Capabilities() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSetupDone(Context context) {
        return prefs(context).getBoolean(KEY_SETUP_DONE, false);
    }

    public static void markSetupDone(Context context) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, true).apply();
    }

    public static boolean fastPathEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FAST_PATH, true);
    }

    public static void setFastPathEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FAST_PATH, enabled).apply();
    }

    public static int cachedRootState(Context context) {
        return prefs(context).getInt(KEY_ROOT, ROOT_UNKNOWN);
    }

    public static boolean isRootDecided(Context context) {
        int state = cachedRootState(context);
        return state == ROOT_GRANTED || state == ROOT_DENIED;
    }

    public static boolean hasRoot(Context context) {
        return cachedRootState(context) == ROOT_GRANTED;
    }

    public static void forgetRoot(Context context) {
        prefs(context).edit().remove(KEY_ROOT).apply();
    }

    /**
     * Whether the module was last seen to be live inside the target app.
     *
     * <p>Independent of root: LSPatch and other root-free loaders put the module in place just as
     * well. Cached so the info page keeps reporting it across restarts; a probe is the only way to
     * refresh it, and only root can read the snapshot to check passively.
     *
     * @return null when we have never had a usable answer
     */
    public static Boolean cachedModuleReady(Context context) {
        int value = prefs(context).getInt(KEY_MODULE, -1);
        return value < 0 ? null : value == 1;
    }

    public static void setModuleReady(Context context, Boolean ready) {
        // commit(): this is also written from ProbeReceiver, right before the process gets
        // frozen, where a queued apply() would never reach the disk.
        prefs(context).edit()
                .putInt(KEY_MODULE, ready == null ? -1 : (ready ? 1 : 0))
                .commit();
    }

    // ------------------------------------------------------------------ root

    /**
     * Re-checks root and records the result. Blocking; call it off the main thread.
     *
     * <p>A timeout is recorded but excluded from {@link #isRootDecided} so the UI keeps offering to
     * retry instead of locking into "no root".
     */
    public static int detectRoot(Context context) {
        String su = findSu();
        if (su == null) {
            prefs(context).edit().putInt(KEY_ROOT, ROOT_DENIED).apply();
            return ROOT_DENIED;
        }

        Shell result = exec(su, "id", ROOT_QUICK_TIMEOUT);
        int state;
        if (result == null) {
            // Never answered: the manager is unreachable, not necessarily unwilling.
            state = ROOT_TIMEOUT;
        } else if (result.exitCode == 0 && result.output.contains("uid=0")) {
            state = ROOT_GRANTED;
        } else {
            state = ROOT_DENIED;
        }
        prefs(context).edit().putInt(KEY_ROOT, state).apply();
        return state;
    }

    /** First executable {@code su} we can find, or null when the device has none. */
    public static String findSu() {
        for (String path : SU_PATHS) {
            try {
                File file = new File(path);
                if (file.exists() && file.canExecute()) {
                    return path;
                }
            } catch (Throwable ignored) {
            }
        }
        // Fall back to a PATH lookup through sh, which also covers unusual layouts.
        Shell probe = exec("/system/bin/sh", "command -v su", 2000);
        if (probe != null && probe.exitCode == 0) {
            String path = probe.output.trim();
            if (path.startsWith("/")) {
                return path;
            }
        }
        return null;
    }

    /**
     * Reads the module's snapshot straight out of the source app's private storage. Returns null
     * when root is unavailable, the file is missing, or the read failed.
     */
    /**
     * Prefixes under which the snapshot may be reachable.
     *
     * <p>{@code /data/data} is a symlink to {@code /data/user/0}. A root shell handed out to an
     * app can land in a mount namespace where that symlink is not present, which shows up as a
     * confusing "No such file or directory" even though the file is right there from adb.
     */
    private static final String[] DATA_PREFIXES = {
            "/data/user/0/", "/data/data/",
    };

    /**
     * Reads the module's snapshot straight out of the source app's private storage. Returns null
     * when root is unavailable, the file is missing, or the read failed.
     */
    public static String readSnapshot(Context context, String packageName) {
        StringBuilder attempts = new StringBuilder();
        for (String prefix : DATA_PREFIXES) {
            String path = prefix + packageName + "/files/" + SNAPSHOT_NAME;
            Shell result = su("cat " + path, 8000);
            if (result == null) {
                lastDiagnostic = "su 没有响应（超时）";
                return null;
            }
            attempts.append(path).append(": exit=").append(result.exitCode).append('\n');
            if (result.exitCode == 0) {
                String trimmed = result.output.trim();
                if (!trimmed.isEmpty()) {
                    lastDiagnostic = "";
                    return trimmed;
                }
            }
        }
        lastDiagnostic = attempts.toString().trim();
        return null;
    }

    /** Convenience for the passive "is the module alive" check, which only needs any snapshot. */
    public static String readSnapshot(Context context) {
        return readSnapshot(context, RIOT_PACKAGE);
    }

    /** Last reason a snapshot read failed, surfaced in the capability sheet for diagnosis. */
    public static volatile String lastDiagnostic = "";

    public static boolean snapshotExists(Context context) {
        return readSnapshot(context) != null;
    }

    // ------------------------------------------------------------------ process plumbing

    /** Result of one shell invocation. */
    public static final class Shell {
        public final int exitCode;
        public final String output;

        Shell(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * Runs through the first working {@code su}. Returns null when the call timed out, which is the
     * signal that the root manager never replied.
     */
    public static Shell su(String command, int timeoutMs) {
        String su = findSu();
        if (su == null) {
            return null;
        }
        return exec(su, command, timeoutMs);
    }

    /**
     * Starts {@code binary args...} and waits up to {@code timeoutMs}.
     *
     * <p>Returns null on timeout rather than a fabricated failure code: the caller has to be able
     * to tell "it said no" apart from "it never said anything".
     */
    public static Shell exec(String binary, String command, int timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary, "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process running = process;
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                    }
                } catch (Exception ignored) {
                }
            });
            pump.setDaemon(true);
            pump.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            Integer exitCode = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exitCode = process.exitValue();
                    break;
                } catch (IllegalThreadStateException notYet) {
                    Thread.sleep(40);
                }
            }
            if (exitCode == null) {
                process.destroy();
                return null;
            }
            pump.join(400);
            return new Shell(exitCode, buffer.toString("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
