package io.blockman.totpull;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.List;

import io.blockman.totpull.ui.MainActivity;

/**
 * Receives the module's answer while this app is in the background.
 *
 * <p>Registered in the manifest rather than at runtime on purpose. The import flow now launches
 * the source app first, which puts us in the background where Android freezes the process within
 * about a second. A runtime receiver registered on a frozen process is not considered live, so the
 * system drops the broadcast ({@code Enqueued broadcast ... : 0}). A manifest receiver wakes the
 * process instead, which is the only way the answer survives.
 */
public class ProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "ProbeReceiver";

    public static final String ACTION_RESULT = "io.blockman.totpull.action.RESULT";
    /** Sent by the module the moment it can answer, because we cannot reliably ask it ourselves. */
    public static final String ACTION_READY = "io.blockman.totpull.action.READY";
    /** Sent to the module to ask for the seed. */
    public static final String ACTION_PROBE = "io.blockman.totpull.action.PROBE";

    /** How long an opened round may still react to the module waking up. */
    private static final long ROUND_TTL_MS = 120_000L;

    private static final String PREFS = "probe_state";
    /** JSON blob describing the last completed round; read by the UI on resume. */
    static final String KEY_REPORT = "report";
    /** Wall-clock millis when the user started a round, 0 when idle. */
    static final String KEY_PENDING_SINCE = "pending_since";

    private static final String CHANNEL = "probe";

    /**
     * The receiver is exported, so any app could post to this action. Only accept answers that
     * claim to come from a supported source; keep in sync with {@code MainActivity.EXTRACT_TARGETS}.
     */
    private static final String[] SOURCES = {
            "com.riotgames.mobile.leagueconnect",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (ACTION_READY.equals(intent.getAction())) {
            onModuleReady(context, intent);
            return;
        }
        if (!ACTION_RESULT.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "answer received, ok=" + intent.getBooleanExtra("ok", false)
                + " from=" + intent.getStringExtra("source_package"));
        String claimed = intent.getStringExtra("source_package");
        if (!isSupported(claimed)) {
            Log.w(TAG, "ignored answer claiming source " + claimed);
            return;
        }

        // A failed answer may come from a cloned copy that simply has no seed. Keep the round
        // open so a sibling process still has a chance to answer.
        if (!intent.getBooleanExtra("ok", false)) {
            String error = intent.getStringExtra("error");
            if (error != null && !error.isEmpty()) {
                writeReport(context, false, null, null, error, false);
            }
            return;
        }

        String uri = intent.getStringExtra("otpauth");
        TotpEntry entry = uri == null ? null : TotpEntry.fromOtpauth(uri);
        if (entry == null) {
            writeReport(context, false, null, null,
                    context.getString(R.string.probe_parse_failed), false);
            return;
        }

        String sourcePackage = intent.getStringExtra("source_package");
        String sourceLabel = intent.getStringExtra("source_label");
        if (sourcePackage != null) {
            entry.sourcePackage = sourcePackage;
        }
        if (sourceLabel != null) {
            entry.sourceLabel = sourceLabel;
        }
        String note = intent.getStringExtra("note");
        if (note != null) {
            entry.note = note;
        }

        boolean duplicate;
        try {
            TotpStore store = new TotpStore(context);
            List<TotpEntry> all = store.load();
            if (store.readFailed()) {
                // Never write over something we could not read: the import would look like it
                // worked and every existing entry would be gone.
                Log.w(TAG, "vault unreadable, leaving it alone");
                writeReport(context, false, null, null,
                        context.getString(R.string.probe_vault_unreadable), false);
                return;
            }
            duplicate = false;
            for (TotpEntry existing : all) {
                if (existing.key().equals(entry.key())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                all.add(entry);
                if (!store.save(all)) {
                    writeReport(context, false, null, null,
                            context.getString(R.string.probe_save_failed), false);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
            writeReport(context, false, null, null, String.valueOf(t), false);
            return;
        }

        // The module answered, so it is loaded and healthy in the target app.
        Capabilities.setModuleReady(context, Boolean.TRUE);
        clearPending(context);
        writeReport(context, true, entry.name, entry.issuer, null, duplicate);

        notifyAndReturn(context);
    }

    /**
     * The module has just come up inside the source app.
     *
     * <p>This is the reliable half of the handshake. Our side scheduled retries, but OEM power
     * management freezes this process a couple of seconds after it loses the foreground and those
     * retries never run — so the module speaks first instead. Its broadcast wakes us here, where
     * we are running again and can look at our own state.
     *
     * <p>Answers only while an import is actually open. Otherwise merely opening the source app
     * would pull the user into the vault.
     */
    private void onModuleReady(Context context, Intent intent) {
        String claimed = intent.getStringExtra("source_package");
        if (!isSupported(claimed)) {
            return;
        }
        long since = pendingSince(context);
        if (since <= 0 || System.currentTimeMillis() - since > ROUND_TTL_MS) {
            return;
        }
        try {
            Intent probe = new Intent(ACTION_PROBE);
            probe.setPackage(claimed);
            context.sendBroadcast(probe);
            Log.i(TAG, "module is up in " + claimed + ", asked for the seed");
        } catch (Throwable t) {
            Log.w(TAG, "probe failed", t);
        }
    }

    private static boolean isSupported(String claimed) {
        for (String source : SOURCES) {
            if (source.equals(claimed)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ state

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void markPending(Context context) {
        // commit() rather than apply(): the caller is about to send us into the background, and
        // ColorOS freezes the process within a second or so. An apply() queued on the background
        // writer thread dies with the freeze and the round would look like it never started.
        prefs(context).edit()
                .putLong(KEY_PENDING_SINCE, System.currentTimeMillis())
                .remove(KEY_REPORT)
                .commit();
    }

    public static void clearPending(Context context) {
        prefs(context).edit().remove(KEY_PENDING_SINCE).commit();
    }

    public static long pendingSince(Context context) {
        return prefs(context).getLong(KEY_PENDING_SINCE, 0L);
    }

    /** Returns the last report and consumes it, so a dialog is shown exactly once. */
    public static JSONObject takeReport(Context context) {
        SharedPreferences p = prefs(context);
        String raw = p.getString(KEY_REPORT, null);
        if (raw == null) {
            return null;
        }
        p.edit().remove(KEY_REPORT).commit();
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeReport(Context context, boolean ok, String name, String issuer,
                                    String error, boolean duplicate) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("ts", System.currentTimeMillis());
            if (name != null) json.put("name", name);
            if (issuer != null) json.put("issuer", issuer);
            if (error != null) json.put("error", error);
            json.put("duplicate", duplicate);
            boolean written = prefs(context).edit().putString(KEY_REPORT, json.toString()).commit();
            Log.i(TAG, "report written=" + written + " ok=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "report failed", t);
        }
    }

    // ------------------------------------------------------------------ notify / come back

    private void notifyAndReturn(Context context) {
        boolean shown = postNotification(context);
        if (shown) {
            // With a notification waiting, the user can always get back; do not fight the
            // background-activity-start restriction on top of it.
            return;
        }
        try {
            Intent back = new Intent(context, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(back);
        } catch (Throwable ignored) {
            // Blocked on Android 10+ without overlay permission; the report still shows the
            // next time the user opens the app.
        }
    }

    private boolean postNotification(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL, context.getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 0, open, flags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL)
                    : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_nav_codes)
                    .setContentTitle(context.getString(R.string.notif_title))
                    .setContentText(context.getString(R.string.notif_text))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(1, builder.build());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
            return false;
        }
    }
}
