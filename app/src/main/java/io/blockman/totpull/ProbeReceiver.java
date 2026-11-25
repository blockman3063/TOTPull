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

import java.util.ArrayList;
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
    /**
     * JSON array of entries pulled in the last round, waiting for the user to confirm them in
     * the import manager. Nothing touches the vault until the user ticks entries and confirms.
     */
    static final String KEY_INCOMING = "incoming_entries";
    /** Wall-clock millis when the user started a round, 0 when idle. */
    static final String KEY_PENDING_SINCE = "pending_since";

    private static final String CHANNEL = "probe";

    /**
     * The receiver is exported, so any app could post to this action. Only accept answers that
     * claim to come from a supported source; keep in sync with {@code MainActivity.EXTRACT_TARGETS}.
     */
    private static final String[] SOURCES = {
            "com.riotgames.mobile.leagueconnect",
            "com.valvesoftware.android.steam.community",
            "com.google.android.apps.authenticator2",
            "com.beemdevelopment.aegis",
            "com.azure.authenticator",
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

        // A failed answer may come from the module before the source app has unlocked its store.
        // Keep the round open so a later retry still has a chance to answer.
        if (!intent.getBooleanExtra("ok", false)) {
            String error = intent.getStringExtra("error");
            if (error != null && !error.isEmpty()) {
                writeReport(context, false, null, null, error, false);
            }
            return;
        }

        // The module announces readiness several times (startup retries plus Aegis onResume),
        // so one import can produce several successful answers that are already queued on the
        // main thread. The first one closes the round; every later answer must be dropped or it
        // would add another full copy of the same batch.
        if (pendingSince(context) <= 0L) {
            Log.i(TAG, "successful answer ignored, round already closed");
            return;
        }

        String bundled = intent.getStringExtra("entries");
        if (bundled != null) {
            handleEntries(context, intent, bundled);
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

        // Buffer instead of saving: the import manager in MainActivity shows the entry for
        // confirmation when the user comes back, and only then does the vault get written.
        List<TotpEntry> one = new ArrayList<>();
        one.add(entry);
        bufferIncoming(context, one);

        // The module answered, so it is loaded and healthy in the target app.
        Capabilities.setModuleReady(context, Boolean.TRUE);
        clearPending(context);

        notifyAndReturn(context);
    }

    /**
     * Stores a whole batch of accounts from one answer.
     *
     * <p>Google Authenticator can return dozens of entries; going through the single-entry path
     * once per account would write the vault dozens of times and show as many reports.
     */
    private void handleEntries(Context context, Intent intent, String bundled) {
        List<TotpEntry> incoming = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(bundled);
            String sourcePackage = intent.getStringExtra("source_package");
            String sourceLabel = intent.getStringExtra("source_label");
            for (int i = 0; i < array.length(); i++) {
                TotpEntry entry = TotpEntry.fromOtpauth(array.optString(i));
                if (entry == null) {
                    continue;
                }
                if (sourcePackage != null) {
                    entry.sourcePackage = sourcePackage;
                }
                if (sourceLabel != null) {
                    entry.sourceLabel = sourceLabel;
                }
                incoming.add(entry);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bad entries payload", t);
            writeReport(context, false, null, null,
                    context.getString(R.string.probe_parse_failed), false);
            return;
        }
        if (incoming.isEmpty()) {
            writeReport(context, false, null, null,
                    context.getString(R.string.probe_parse_failed), false);
            return;
        }

        try {
            // Keep every row from every answer, even identical secrets. The entries wait in
            // the incoming buffer; the vault is written only after the user confirms the
            // selection in the import manager.
            bufferIncoming(context, incoming);
            Capabilities.setModuleReady(context, Boolean.TRUE);
            clearPending(context);
            notifyAndReturn(context);
        } catch (Throwable t) {
            Log.w(TAG, "buffer failed", t);
            writeReport(context, false, null, null, String.valueOf(t), false);
        }
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
                .remove(KEY_INCOMING)
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

    // ------------------------------------------------------------------ incoming buffer

    /** Stashes the entries of a finished round until the user confirms them in the manager. */
    private static void bufferIncoming(Context context, List<TotpEntry> entries) {
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (TotpEntry entry : entries) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("otpauth", entry.toOtpauthUri());
                item.put("note", entry.note == null ? "" : entry.note);
                item.put("source_package", entry.sourcePackage == null
                        ? "" : entry.sourcePackage);
                item.put("source_label", entry.sourceLabel == null
                        ? "" : entry.sourceLabel);
                array.put(item);
            }
            boolean written = prefs(context).edit()
                    .putString(KEY_INCOMING, array.toString()).commit();
            Log.i(TAG, "incoming buffered=" + written + " count=" + entries.size());
        } catch (Throwable t) {
            Log.w(TAG, "buffer write failed", t);
        }
    }

    /**
     * Returns the buffered entries of the last round and consumes them. Null when nothing is
     * waiting; an empty list means the buffer existed but nothing in it survived parsing.
     */
    public static List<TotpEntry> takeIncoming(Context context) {
        SharedPreferences p = prefs(context);
        String raw = p.getString(KEY_INCOMING, null);
        if (raw == null) {
            return null;
        }
        p.edit().remove(KEY_INCOMING).commit();
        List<TotpEntry> out = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                TotpEntry entry = TotpEntry.fromOtpauth(item.optString("otpauth", ""));
                if (entry == null) {
                    continue;
                }
                entry.note = item.optString("note", "");
                entry.sourcePackage = item.optString("source_package", "");
                entry.sourceLabel = item.optString("source_label", "");
                out.add(entry);
            }
        } catch (Throwable t) {
            Log.w(TAG, "buffer read failed", t);
        }
        return out;
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
