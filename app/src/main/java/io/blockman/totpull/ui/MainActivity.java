package io.blockman.totpull.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.blockman.totpull.Capabilities;
import io.blockman.totpull.ProbeReceiver;
import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;
import io.blockman.totpull.TotpStore;
import io.blockman.totpull.format.AegisVault;
import io.blockman.totpull.format.ImportEngine;

/**
 * All business logic lives here; every piece of UI is Compose (see CodesScreen.kt) and talks
 * back through {@link UiBridge.Host}. The activity no longer owns any views of its own.
 */
public class MainActivity extends AppCompatActivity implements UiBridge.Host {
    /** Optional extras so an extractor can stamp the originating app onto the entry. */
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_SOURCE_LABEL = "source_label";

    private static final int REQ_OPEN_FILE = 1001;
    /** Asked right before we leave for the source app, so the answer can call us back. */
    private static final int REQ_NOTIFY = 1002;
    /** EditGroupActivity answered: re-import that source group. */
    private static final int REQ_EDIT_GROUP = 1003;

    private static final int REQ_SCAN = 1004;

    /** Broadcast exchanged with the Xposed module running inside the source app. */
    static final String ACTION_PROBE = "io.blockman.totpull.action.PROBE";
    static final String ACTION_RESULT = "io.blockman.totpull.action.RESULT";

    /** Retry schedule while the source app boots up; all of them fire only if still waiting. */
    private static final int[] PROBE_RETRY_MS = {1200, 3000, 5500, 9000};

    /** How long a round stays open before the UI calls it a timeout. */
    private static final long PROBE_TIMEOUT_MS = 60_000L;

    /**
     * Apps we know how to pull codes out of. Each entry needs a matching extractor on the module
     * side; the module answers with a ready-made otpauth URI so this side stays format-agnostic.
     */
    private static final String AEGIS_PACKAGE = "com.beemdevelopment.aegis";
    private static final String AZURE_PACKAGE = "com.azure.authenticator";

    static final ExtractTarget[] EXTRACT_TARGETS = {
            new ExtractTarget("com.riotgames.mobile.leagueconnect", "Riot Mobile"),
            new ExtractTarget("com.valvesoftware.android.steam.community", "Steam"),
            new ExtractTarget("com.google.android.apps.authenticator2", "谷歌验证器"),
            new ExtractTarget(AEGIS_PACKAGE, "Aegis"),
            new ExtractTarget(AZURE_PACKAGE, "Microsoft Authenticator"),
    };

    static final class ExtractTarget {
        final String packageName;
        final String label;

        ExtractTarget(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private TotpStore _store;
    private final List<TotpEntry> _entries = new ArrayList<>();

    /** Mirrors UiBridge.tab; only read here to know whether the info page is in front. */
    private int _tab;

    /** Non-null while we are waiting for the module to answer a probe. */
    private ExtractTarget _pendingProbe;
    /** null = not yet determined, TRUE = the module has usable data, FALSE = it does not. */
    private Boolean _moduleReady = null;

    /** Set while we are waiting for the user to grant root in the manager's own UI. */
    private boolean _awaitingRootGrant;
    private long _lastRootAttempt;

    /** Keeps the "vault is unreadable" warning to once per process. */
    private boolean _vaultWarnShown;

    private final Handler _handler = new Handler(Looper.getMainLooper());

    /** scrypt can take seconds; never run it on the main thread. */
    private final ExecutorService _importIo = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _store = new TotpStore(this);
        // One-off repair of legacy Google Authenticator secrets, before the list is built.
        _store.migrateCanonicalSecrets();

        // Swaps the View layout for Compose and binds this activity as the UiBridge.Host.
        AppUiKt.installUi(this, this);

        // Module readiness survives restarts; only a probe can change it.
        _moduleReady = Capabilities.cachedModuleReady(this);
        UiBridge.INSTANCE.setModuleReady(_moduleReady);
        UiBridge.INSTANCE.setSetupDone(Capabilities.isSetupDone(this));
        reload();

        handleIntent(getIntent());

        if (!Capabilities.isSetupDone(this)) {
            _handler.postDelayed(this::startFirstRun, 350);
        }
    }

    // ------------------------------------------------------------------ UiBridge.Host

    @Override
    public List<TotpEntry> entries() {
        return _entries;
    }

    @Override
    public void onEntryTap(TotpEntry entry) {
        onEntryClicked(entry);
    }

    @Override
    public void onEntryEdit(TotpEntry entry) {
        // Straight into the editor - no intermediate menu. Delete lives in the editor.
        openEditor(entry.id);
    }

    @Override
    public void onEntryShareMenu(TotpEntry entry) {
        showEntryMenu(entry);
    }

    @Override
    public void onGroupEdit(String packageName) {
        Intent intent = new Intent(this, EditGroupActivity.class);
        intent.putExtra(EditGroupActivity.EXTRA_PACKAGE, packageName == null ? "" : packageName);
        startActivityForResult(intent, REQ_EDIT_GROUP);
    }

    @Override
    public void onAdd() {
        showAddMenu();
    }

    @Override
    public void onTabChanged(int tab) {
        _tab = tab;
    }

    @Override
    public void showFilterDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_filter, null);
        LinearLayout sourceGroup = view.findViewById(R.id.filter_source_group);
        LinearLayout typeGroup = view.findViewById(R.id.filter_type_group);
        RadioGroup sortGroup = view.findViewById(R.id.filter_sort_group);

        // Multi-select checkboxes: sources and types combine freely, which keeps the dialog
        // usable once many source apps have been imported. An empty selection means the
        // dimension is unrestricted ("不勾选 = 全部"), so no separate "all" row is needed
        // and checkbox state always mirrors the live filter set.
        Set<String> selectedSources = UiBridge.INSTANCE.readFilterSources();
        Set<String> selectedTypes = UiBridge.INSTANCE.readFilterTypes();
        String selectedSort = UiBridge.INSTANCE.readSortMode();

        Set<String> sources = new LinkedHashSet<>();
        for (TotpEntry e : _entries) {
            sources.add(e.sourcePackage == null ? "" : e.sourcePackage);
        }
        // Resolve each package to its display label first, then offer them alphabetically
        // (hand-added entries last), instead of whatever order the vault happens to hold.
        List<String[]> sourceOptions = new ArrayList<>();
        for (String pkg : sources) {
            String label;
            if (pkg.isEmpty()) {
                label = getString(R.string.local_source);
            } else {
                String found = "";
                for (TotpEntry e : _entries) {
                    if (pkg.equals(e.sourcePackage == null ? "" : e.sourcePackage)
                            && e.sourceLabel != null && !e.sourceLabel.isEmpty()) {
                        found = e.sourceLabel;
                        break;
                    }
                }
                label = found.isEmpty() ? pkg : found;
            }
            sourceOptions.add(new String[]{pkg, label});
        }
        sourceOptions.sort((a, b) -> {
            boolean aLocal = a[0].isEmpty();
            boolean bLocal = b[0].isEmpty();
            if (aLocal != bLocal) {
                return aLocal ? 1 : -1;
            }
            return a[1].compareToIgnoreCase(b[1]);
        });
        for (String[] option : sourceOptions) {
            addCheck(sourceGroup, option[1], option[0], selectedSources.contains(option[0]));
        }

        addCheck(typeGroup, getString(R.string.filter_type_totp), TotpEntry.TYPE_TOTP,
                selectedTypes.contains(TotpEntry.TYPE_TOTP));
        addCheck(typeGroup, getString(R.string.filter_type_hotp), TotpEntry.TYPE_HOTP,
                selectedTypes.contains(TotpEntry.TYPE_HOTP));
        addCheck(typeGroup, getString(R.string.filter_type_steam), TotpEntry.TYPE_STEAM,
                selectedTypes.contains(TotpEntry.TYPE_STEAM));
        addCheck(typeGroup, getString(R.string.filter_type_other), "other",
                selectedTypes.contains("other"));

        // Sort order shared with the transfer manager (UiBridge.sortMode).
        addRadio(sortGroup, getString(R.string.sort_custom), UiBridge.SORT_CUSTOM,
                UiBridge.SORT_CUSTOM.equals(selectedSort));
        addRadio(sortGroup, getString(R.string.sort_name), UiBridge.SORT_NAME,
                UiBridge.SORT_NAME.equals(selectedSort));
        addRadio(sortGroup, getString(R.string.sort_recent), UiBridge.SORT_RECENT,
                UiBridge.SORT_RECENT.equals(selectedSort));

        // Toggling a box writes straight into the live UiBridge set, so the list behind
        // the dialog updates as choices are made.
        hookMultiSelect(sourceGroup, value ->
                UiBridge.INSTANCE.setFilterSources(toggle(UiBridge.INSTANCE.readFilterSources(), value)));
        hookMultiSelect(typeGroup, value ->
                UiBridge.INSTANCE.setFilterTypes(toggle(UiBridge.INSTANCE.readFilterTypes(), value)));
        sortGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String value = tagOf(group, checkedId);
            if (value != null) {
                UiBridge.INSTANCE.setSortMode(value);
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.filter_title)
                .setView(view)
                .setNeutralButton(R.string.filter_reset, (d, w) -> {
                    UiBridge.INSTANCE.setFilterSources(java.util.Collections.emptySet());
                    UiBridge.INSTANCE.setFilterTypes(java.util.Collections.emptySet());
                    UiBridge.INSTANCE.setSortMode(UiBridge.SORT_CUSTOM);
                })
                .setPositiveButton(R.string.filter_done, null)
                .show();
    }

    /** Adds or removes {@code value} from a live filter set. */
    private static Set<String> toggle(Set<String> current, String value) {
        Set<String> next = new LinkedHashSet<>(current);
        if (!next.remove(value)) {
            next.add(value);
        }
        return next;
    }

    /** Wires a multi-select checkbox group: each tap toggles its tag in the live set. */
    private void hookMultiSelect(LinearLayout group, java.util.function.Consumer<String> onChange) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof CheckBox) {
                CheckBox box = (CheckBox) child;
                box.setOnCheckedChangeListener((button, isChecked) ->
                        onChange.accept((String) button.getTag()));
            }
        }
    }

    @Override
    public void moveEntry(TotpEntry entry, TotpEntry target) {
        int from = _entries.indexOf(entry);
        int to = _entries.indexOf(target);
        if (from < 0 || to < 0 || from == to) {
            return;
        }
        boolean down = to > from;
        _entries.remove(from);
        int insertAt = _entries.indexOf(target);
        if (insertAt < 0) {
            // Target vanished mid-drag; put the entry back where it was.
            _entries.add(Math.min(from, _entries.size()), entry);
            UiBridge.INSTANCE.notifyDataChanged();
            return;
        }
        // Moving down: land right after the target (its index shifted up by the removal).
        // Moving up: land right on it.
        int landedAt = down ? insertAt + 1 : insertAt;
        _entries.add(landedAt, entry);
        Log.i("UITouch", "move " + from + " -> " + landedAt);
        UiBridge.INSTANCE.notifyDataChanged();
    }

    /** The vault stores entries as one list; the display order follows it. */
    @Override
    public void persistOrder() {
        _store.save(_entries);
    }

    @Override
    public void onRedetect() {
        detectRoot(false);
    }

    @Override
    public void onOpenRootManager() {
        launchRootManager();
    }

    @Override
    public void onFastPathChanged(boolean enabled) {
        Capabilities.setFastPathEnabled(this, enabled);
    }

    @Override
    public void onSetupStart() {
        markSetup();
        UiBridge.INSTANCE.showTab(0);
    }

    @Override
    public void onExportAll() {
        exportAll();
    }

    /** A radio's tag is its filter value; the "all" option carries no tag. */
    private static void addRadio(RadioGroup group, String label, String value, boolean checked) {
        RadioButton radio = new RadioButton(group.getContext());
        // RadioGroup.addView records a pre-checked child's id BEFORE its hierarchy listener
        // generates one, so a radio that is checked prior to addView leaves the group's
        // bookkeeping pointing at NO_ID: the next tap then clears nothing and two radios
        // end up checked at once. Assign the id up front and route the initial selection
        // through group.check(), which maintains the exclusivity properly.
        radio.setId(View.generateViewId());
        radio.setText(label);
        radio.setTag(value); // null is fine: it marks "all"
        group.addView(radio);
        if (checked) {
            group.check(radio.getId());
        }
    }

    private static void addCheck(LinearLayout group, String label, String value, boolean checked) {
        CheckBox box = new CheckBox(group.getContext());
        box.setText(label);
        box.setTag(value);
        box.setChecked(checked);
        group.addView(box);
    }

    private static String tagOf(RadioGroup group, int checkedId) {
        if (checkedId == View.NO_ID) {
            return null;
        }
        View checked = group.findViewById(checkedId);
        return checked == null ? null : (String) checked.getTag();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        _pendingProbe = null;
        _handler.removeCallbacks(_probeTimeout);
        _importIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Sub-activities (group editor, entry editor) may have changed the vault; the
        // in-memory list must be re-read or the list keeps showing stale rows.
        reload();

        // The answer is delivered to ProbeReceiver, possibly while this activity was not running.
        // Show whatever came back as soon as we are back in front of the user.
        showProbeReport();

        // Back from the deeplink chooser: mark the batch green and re-offer the menu, so a
        // multi-batch export keeps going without rebuilding the whole flow. Fires only when
        // a send was actually handed off (_deeplinkPending is set right before the chooser).
        if (_deeplinkPending >= 0 && _deeplinkBatches != null && _deeplinkSent != null) {
            if (_deeplinkPending < _deeplinkSent.length) {
                _deeplinkSent[_deeplinkPending] = true;
            }
            _deeplinkPending = -1;
            boolean allSent = true;
            for (boolean b : _deeplinkSent) {
                allSent = allSent && b;
            }
            if (allSent) {
                clearDeeplinkState();
                toast(getString(R.string.deeplink_all_sent));
            } else if (!isFinishing()) {
                showDeeplinkBatchMenu();
            }
        }

        if (_moduleReady == null && Capabilities.hasRoot(this)) {
            new Thread(() -> {
                Boolean ready = Capabilities.snapshotExists(this);
                runOnUiThread(() -> {
                    setModuleReady(ready);
                    refreshInfo();
                });
            }).start();
        }

        // Back from the root manager (or the user granted it some other way): check again. The
        // throttle keeps us from poking su on every resume on devices that have no root at all.
        boolean fromManager = _awaitingRootGrant;
        _awaitingRootGrant = false;
        boolean stale = System.currentTimeMillis() - _lastRootAttempt > 60000;
        if (!Capabilities.hasRoot(this) && (fromManager || (_tab == 1 && stale))) {
            _lastRootAttempt = System.currentTimeMillis();
            new Thread(() -> {
                Capabilities.forgetRoot(this);
                Capabilities.detectRoot(this);
                refreshModuleStateBlocking();
                runOnUiThread(() -> {
                    if (Capabilities.hasRoot(this)) {
                        toast(getString(R.string.cap_root_ok));
                    }
                    refreshInfo();
                });
            }).start();
        }

        refreshInfo();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        reload();
    }

    /** Single place that records module readiness, in memory and across restarts. */
    private void setModuleReady(Boolean ready) {
        _moduleReady = ready;
        Capabilities.setModuleReady(this, ready);
        UiBridge.INSTANCE.setModuleReady(ready);
    }

    /** Single place that records first-run completion. */
    private void markSetup() {
        Capabilities.markSetupDone(this);
        UiBridge.INSTANCE.setSetupDone(true);
    }

    /** Asks the Compose side to repaint the info page (root/module state may have changed). */
    private void refreshInfo() {
        UiBridge.INSTANCE.refreshInfo();
    }

    private void reload() {
        if (_store == null) {
            return;
        }
        _entries.clear();
        _entries.addAll(_store.load());
        if (_store.readFailed() && !_vaultWarnShown) {
            _vaultWarnShown = true;
            toast(getString(R.string.probe_vault_unreadable));
        }
        UiBridge.INSTANCE.notifyDataChanged();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        Uri data = intent.getData();

        // 1) otpauth:// 深链
        if (data != null && "otpauth".equals(data.getScheme())) {
            String uri = data.toString();
            intent.setData(null);

            if (uri.startsWith("otpauth-migration://")) {
                applyImport(uri);
                return;
            }

            TotpEntry entry = TotpEntry.fromOtpauth(uri);
            if (entry == null) {
                toast(getString(R.string.bad_uri));
                return;
            }

            String sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE);
            String sourceLabel = intent.getStringExtra(EXTRA_SOURCE_LABEL);
            if (sourcePackage != null) {
                entry.sourcePackage = sourcePackage;
            }
            if (sourceLabel != null) {
                entry.sourceLabel = sourceLabel;
            }
            addEntry(entry);
            return;
        }

        // 2) 从别处分享进来
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                importFromUri(stream);
                return;
            }
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.length() > 0) {
                applyImport(shared.toString());
            }
            return;
        }

        // 3) 用本应用打开备份文件
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String scheme = data.getScheme();
            if ("file".equals(scheme) || "content".equals(scheme)) {
                importFromUri(data);
            }
        }
    }

    /** Single-entry imports (deeplinks, module snapshots) also confirm through the manager. */
    private void addEntry(TotpEntry entry) {
        String source = entry.displaySource();
        if (source.isEmpty()) {
            source = getString(R.string.local_source);
        }
        List<TotpEntry> single = new ArrayList<>();
        single.add(entry);
        TransferManagerDialog.show(this, getString(R.string.transfer_import_title),
                getString(R.string.transfer_summary_import, 1, source),
                single, false, null, selected -> {
                    if (!selected.isEmpty()) {
                        insertEntry(entry);
                    }
                });
    }

    /** Previous addEntry body: adopt matching hand-added rows, then append and save. */
    private void insertEntry(TotpEntry entry) {
        List<TotpEntry> current = _store.load();

        // An entry the user added by hand earlier can be the same code we just pulled from an
        // app. Adopt the source metadata onto the existing row instead of creating a twin: their
        // custom name/group survives, and the row simply moves into the app's group.
        String incomingSource = entry.sourcePackage == null ? "" : entry.sourcePackage;
        if (!incomingSource.isEmpty()) {
            for (TotpEntry existing : current) {
                String existingSource =
                        existing.sourcePackage == null ? "" : existing.sourcePackage;
                if (!existingSource.isEmpty() || !existing.secret.equals(entry.secret)) {
                    continue;
                }
                existing.sourcePackage = entry.sourcePackage;
                existing.sourceLabel = entry.sourceLabel;
                existing.algorithm = entry.algorithm;
                existing.digits = entry.digits;
                existing.period = entry.period;
                if (existing.note == null || existing.note.isEmpty()) {
                    existing.note = entry.note;
                }
                _store.save(current);
                reload();
                toast(getString(R.string.imported, 1));
                return;
            }
        }

        current.add(entry);
        _store.save(current);
        reload();
        toast(getString(R.string.imported, 1));
    }

    /** Groups the entries' sources into one short line for the manager header. */
    private String summarizeSources(List<TotpEntry> entries) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (TotpEntry e : entries) {
            String source = e.displaySource();
            set.add(source.isEmpty() ? getString(R.string.local_source) : source);
        }
        return set.isEmpty() ? getString(R.string.local_source)
                : android.text.TextUtils.join("、", set);
    }

    /**
     * The three-bar handle, tapped: offers the share options. Copy and edit live on the card
     * body - tap copies, long-press opens the editor.
     */
    private void showEntryMenu(TotpEntry entry) {
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add(getString(R.string.entry_menu_share_uri));
        actions.add(1);
        labels.add(getString(R.string.entry_menu_share_qr));
        actions.add(4);
        labels.add(getString(R.string.entry_menu_deeplink));
        actions.add(2);
        if (entry.isSteam()) {
            // Bitwarden cannot import Steam through any otpauth deeplink: its parser
            // ignores the otpauth://steam host, and otpauth://totp with issuer=Steam
            // yields WRONG numeric codes. Its only Steam entry point is a hand-typed
            // steam://SECRET in the key field, so offer that exact text for pasting.
            labels.add(getString(R.string.entry_menu_bw_secret));
            actions.add(3);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.displayName())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int action = actions.get(which);
                    if (action == 1) {
                        shareText(entry.displayName(), entry.toOtpauthUri());
                    } else if (action == 4) {
                        shareQrImage(entry);
                    } else if (action == 2) {
                        // Hand the single otpauth URI straight to another validator app.
                        sendDeeplink(entry.toOtpauthUri());
                    } else {
                        copySteamSecret(entry);
                    }
                })
                .show();
    }

    /** Renders the entry's otpauth URI as a QR PNG and hands it to the share sheet. */
    private void shareQrImage(TotpEntry entry) {
        try {
            android.graphics.Bitmap bmp = renderQr(entry.toOtpauthUri(), 720);
            if (bmp == null) {
                toast(getString(R.string.export_failed));
                return;
            }
            java.io.File dir = new java.io.File(getCacheDir(), "captured");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File file = new java.io.File(dir, "share_" + entry.id + ".png");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            android.content.Intent share = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            share.putExtra(android.content.Intent.EXTRA_TEXT, entry.toOtpauthUri());
            share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(
                    share, entry.displayName()));
        } catch (Exception e) {
            android.util.Log.e("TOTPull", "qr share failed", e);
            toast(getString(R.string.export_failed) + "：" + e.getMessage());
        }
    }

    /** Puts the Bitwarden-style steam:// key on the clipboard for manual pasting. */
    private void copySteamSecret(TotpEntry entry) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("TOTP",
                    "steam://" + TotpGenerator.canonicalSecret(entry.secret)));
        }
        toast(getString(R.string.steam_secret_copied));
    }

    private void shareText(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        if (subject != null && !subject.isEmpty()) {
            send.putExtra(Intent.EXTRA_SUBJECT, subject);
        }
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.entry_menu_share)));
    }

    // ------------------------------------------------------------------ export all

    /**
     * Whole-vault export, offered from the info page. The format (mode) is picked first because
     * it decides which entries can leave — the Google migration modes cannot carry Steam tokens;
     * the manager then greys out whatever the chosen mode does not support.
     */
    private void exportAll() {
        if (_entries.isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        showExportFormatDialog(new ArrayList<>(_entries));
    }

    private void showExportFormatDialog(List<TotpEntry> candidates) {
        String[] formats = {
                getString(R.string.export_otpauth),
                getString(R.string.export_aegis),
                getString(R.string.export_deeplink),
                getString(R.string.export_qr),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_title)
                .setItems(formats, (d, which) -> {
                    // Google migration (deep link / QR) has no Steam representation; otpauth
                    // text and Aegis JSON carry everything we can generate a code for.
                    boolean migration = which == 2 || which == 3;
                    java.util.Set<TotpEntry> unsupported = new LinkedHashSet<>();
                    int supported = 0;
                    for (TotpEntry e : candidates) {
                        boolean ok = e.isSupported() && (!migration || !e.isSteam());
                        if (ok) {
                            supported++;
                        } else {
                            unsupported.add(e);
                        }
                    }
                    if (supported == 0) {
                        toast(getString(R.string.export_empty));
                        return;
                    }
                    String summary = getString(R.string.transfer_summary_export_full,
                            candidates.size(), supported);
                    final int mode = which;
                    TransferManagerDialog.show(this, getString(R.string.transfer_export_title),
                            summary, candidates, true, unsupported,
                            selected -> dispatchExport(mode, selected));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Runs the export the user confirmed in the manager, with the mode picked earlier. */
    private void dispatchExport(int which, List<TotpEntry> selected) {
        if (selected.isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        try {
            if (which == 0) {
                shareExport("text/plain",
                        getString(R.string.export_subject, selected.size()),
                        io.blockman.totpull.format.ExportEngine.toOtpauthText(selected));
            } else if (which == 1) {
                shareExport("application/json",
                        getString(R.string.export_subject, selected.size()),
                        io.blockman.totpull.format.ExportEngine.toAegisJson(selected));
            } else if (which == 2) {
                shareDeeplink(selected);
            } else {
                showMigrationQrDialog(selected);
            }
        } catch (Exception e) {
            android.util.Log.e("TOTPull", "export failed", e);
            toast(getString(R.string.export_failed) + "：" + e.getMessage());
        }
    }

    private void shareExport(String mime, String subject, String body) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_SUBJECT, subject);
        share.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(share, getString(R.string.export_title)));
    }

    // ------------------------------------------------------------------ migration deeplink / QR

    /**
     * In-progress deeplink batch export; null when idle. Kept across the chooser hand-off so
     * that onResume can re-offer the batch menu — the same flow the scan screen uses for
     * multi-page Google migration QRs: send one batch, come back, keep going.
     */
    private List<String> _deeplinkBatches;
    private boolean[] _deeplinkSent;
    private int _deeplinkPending = -1;
    private int _deeplinkTotalEntries;
    private androidx.appcompat.app.AlertDialog _deeplinkDialog;

    /**
     * Sends the whole vault as otpauth-migration deeplink INTENTS. Unlike sharing the link as
     * text, an ACTION_VIEW hand-off puts the URI straight into another validator's import flow.
     * The migration format carries at most ten entries per link; more than that asks which batch
     * to send first, then returns to the same menu after every send (sent batches turn green).
     */
    private void shareDeeplink(List<TotpEntry> entries) {
        List<String> uris = io.blockman.totpull.format.MigrationExporter.toMigrationUris(entries);
        if (uris.isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        if (uris.size() == 1) {
            sendDeeplink(uris.get(0));
            return;
        }
        _deeplinkBatches = uris;
        _deeplinkSent = new boolean[uris.size()];
        _deeplinkPending = -1;
        _deeplinkTotalEntries = entries.size();
        showDeeplinkBatchMenu();
    }

    /**
     * The batch picker. Rebuilt (with fresh green marks) on every re-show, so the state in
     * {@code _deeplinkSent} is the single source of truth. Sent batches stay clickable: that
     * is the "re-send" path in case the target app dropped the import.
     */
    private void showDeeplinkBatchMenu() {
        final List<String> batches = _deeplinkBatches;
        int total = batches.size();
        String[] items = new String[total];
        for (int i = 0; i < total; i++) {
            int from = i * 10 + 1;
            int to = Math.min((i + 1) * 10, _deeplinkTotalEntries);
            items[i] = getString(R.string.deeplink_batch_item, i + 1, total, from, to);
        }
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(this,
                        android.R.layout.simple_list_item_1, items) {
                    @Override
                    public View getView(int position, View convertView,
                            android.view.ViewGroup parent) {
                        View row = super.getView(position, convertView, parent);
                        TextView text = row.findViewById(android.R.id.text1);
                        if (_deeplinkSent != null && _deeplinkSent[position]) {
                            text.setTextColor(0xFF1E8E3E);
                            text.append(getString(R.string.deeplink_batch_sent_mark));
                        }
                        return row;
                    }
                };
        _deeplinkDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.deeplink_batch_title)
                .setAdapter(adapter, (d, which) -> {
                    _deeplinkPending = which;
                    if (_deeplinkDialog != null) {
                        _deeplinkDialog.dismiss();
                    }
                    sendDeeplink(batches.get(which));
                })
                .setNegativeButton(R.string.cancel, (d, w) -> clearDeeplinkState())
                .show();
    }

    private void clearDeeplinkState() {
        _deeplinkBatches = null;
        _deeplinkSent = null;
        _deeplinkPending = -1;
        if (_deeplinkDialog != null && _deeplinkDialog.isShowing()) {
            _deeplinkDialog.dismiss();
        }
        _deeplinkDialog = null;
    }

    private void sendDeeplink(String uri) {
        // Native chooser, nothing excluded: TOTPull itself is always an option (it imports the
        // link back into the vault), and whatever validators the device has appear alongside.
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        startActivity(Intent.createChooser(view, getString(R.string.deeplink_pick_title)));
    }

    /** Shows the migration export as scannable QR code(s), one page per ten entries. */
    private void showMigrationQrDialog(List<TotpEntry> entries) {
        List<String> uris = io.blockman.totpull.format.MigrationExporter.toMigrationUris(entries);
        StringBuilder sizes = new StringBuilder();
        for (String u : uris) {
            sizes.append(u.length()).append(" ");
        }
        android.util.Log.i("TOTPull", "migration uris: entries=" + entries.size()
                + " pages=" + uris.size() + " lens=[" + sizes + "]");
        if (uris.isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_qr, null);
        ImageView image = view.findViewById(R.id.qr_image);
        TextView page = view.findViewById(R.id.qr_page);
        TextView hint = view.findViewById(R.id.qr_hint);
        View pager = view.findViewById(R.id.qr_pager);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.export_qr_title)
                                + (uris.size() > 1 ? "（共 " + uris.size() + " 页）" : ""))
                        .setView(view)
                        .setPositiveButton(R.string.close, null);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        final int[] index = {0};
        Runnable render = () -> {
            android.graphics.Bitmap bmp = renderQr(uris.get(index[0]), 720);
            if (bmp == null) {
                dialog.dismiss();
                toast(getString(R.string.export_failed));
                return;
            }
            image.setImageBitmap(bmp);
            page.setText(getString(R.string.qr_page, index[0] + 1, uris.size()));
            hint.setText(uris.size() > 1 ? R.string.qr_hint_multi : R.string.qr_hint);
        };
        view.findViewById(R.id.btn_qr_prev).setOnClickListener(v -> {
            if (index[0] > 0) {
                index[0]--;
                render.run();
            }
        });
        view.findViewById(R.id.btn_qr_next).setOnClickListener(v -> {
            if (index[0] < uris.size() - 1) {
                index[0]++;
                render.run();
            }
        });
        pager.setVisibility(uris.size() > 1 ? View.VISIBLE : View.GONE);
        render.run();
    }

    /** Renders one QR code; null when the payload is too big for the format. */
    private android.graphics.Bitmap renderQr(String content, int size) {
        try {
            java.util.EnumMap<com.google.zxing.EncodeHintType, Object> hints =
                    new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
            int width = matrix.getWidth();
            int[] pixels = new int[width * width];
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y)
                            ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, width, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, width, 0, 0, width, width);
            return bmp;
        } catch (Exception e) {
            android.util.Log.e("TOTPull", "qr render failed for "
                    + content.length() + " chars", e);
            return null;
        }
    }

    private void onEntryClicked(TotpEntry entry) {
        if (!entry.isSupported()) {
            toast(getString(R.string.unsupported_type));
            return;
        }
        if (entry.isHotp()) {
            entry.counter = entry.counter + 1;
        }
        entry.usageCount = entry.usageCount + 1;
        entry.lastUsedAt = System.currentTimeMillis();

        List<TotpEntry> current = _store.load();
        for (TotpEntry e : current) {
            if (e.id.equals(entry.id)) {
                e.counter = entry.counter;
                e.usageCount = entry.usageCount;
                e.lastUsedAt = entry.lastUsedAt;
                break;
            }
        }
        _store.save(current);

        String code = TotpGenerator.generate(entry);
        if (code.isEmpty()) {
            toast(getString(R.string.unsupported_type));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("TOTP", code));
        }
        UiBridge.INSTANCE.notifyDataChanged();
        toast(getString(R.string.copied, TotpGenerator.format(code, entry.isSteam())));
    }

    /** Looks up the known extract sources; shared with EditGroupActivity. */
    static ExtractTarget findTarget(String packageName) {
        for (ExtractTarget target : EXTRACT_TARGETS) {
            if (target.packageName.equals(packageName)) {
                return target;
            }
        }
        return null;
    }

    private void showAddMenu() {
        // "Import from an app" is always offered. Only a probe can tell whether the module is
        // there, and it degrades to a broadcast when the snapshot itself is unreadable, so hiding
        // the entry based on the snapshot check would remove a path that still works.
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add(getString(R.string.action_from_app));
        actions.add(0);
        labels.add(getString(R.string.action_scan));
        actions.add(4);
        labels.add(getString(R.string.action_paste));
        actions.add(1);
        labels.add(getString(R.string.action_file));
        actions.add(2);
        labels.add(getString(R.string.edit_title_new));
        actions.add(3);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_import)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (actions.get(which)) {
                        case 0:
                            showExtractTargets();
                            break;
                        case 4:
                            scanQr();
                            break;
                        case 1:
                            importFromClipboard();
                            break;
                        case 2:
                            openFilePicker();
                            break;
                        default:
                            openEditor(null);
                            break;
                    }
                })
                .show();
    }

    private void showExtractTargets() {
        ArrayAdapter<ExtractTarget> adapter = new ArrayAdapter<ExtractTarget>(
                this, R.layout.item_extract_target, EXTRACT_TARGETS) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    row = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_extract_target, parent, false);
                }
                ExtractTarget target = getItem(position);
                if (target != null) {
                    ((TextView) row.findViewById(R.id.target_label)).setText(target.label);
                    // Every target answers the module broadcast; Aegis and Microsoft
                    // Authenticator also expose an independent root route.
                    boolean rootCapable = AEGIS_PACKAGE.equals(target.packageName)
                            || AZURE_PACKAGE.equals(target.packageName);
                    row.findViewById(R.id.target_lsposed).setVisibility(View.VISIBLE);
                    row.findViewById(R.id.target_root).setVisibility(
                            rootCapable ? View.VISIBLE : View.GONE);
                }
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.probe_title)
                .setAdapter(adapter, (d, which) -> startProbe(EXTRACT_TARGETS[which]))
                .show();
    }

    // ------------------------------------------------------------------ capabilities

    private static final String KERNELSU_PACKAGE = "me.weishu.kernelsu";
    private static final String MAGISK_PACKAGE = "com.topjohnwu.magisk";

    /**
     * Re-runs detection and repaints the info page.
     *
     * <p>Root managers grant access from their own list; there is no consent prompt to wait for,
     * so a "no answer" is reported as such instead of being cached as a refusal.
     */
    private void detectRoot(boolean firstRun) {
        toast(getString(R.string.cap_detecting));
        _lastRootAttempt = System.currentTimeMillis();
        if (firstRun) {
            UiBridge.INSTANCE.showTab(1);
        }
        new Thread(() -> {
            Capabilities.forgetRoot(this);
            Capabilities.detectRoot(this);
            refreshModuleStateBlocking();
            runOnUiThread(this::refreshInfo);
        }).start();
    }

    /**
     * Opens the root manager so the user can tick us in its superuser list.
     *
     * <p>Built from an explicit component rather than {@code getLaunchIntentForPackage}: package
     * visibility rules make that return null for apps we merely declared in {@code <queries>},
     * while an explicit component always resolves.
     *
     * @return true when a manager was actually launched, so we know to re-check on return
     */
    private boolean launchRootManager() {
        String[][] candidates = {
                {KERNELSU_PACKAGE, KERNELSU_PACKAGE + ".ui.MainActivity"},
                {MAGISK_PACKAGE, MAGISK_PACKAGE + ".ui.MainActivity"},
        };
        for (String[] candidate : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(candidate[0], candidate[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                _awaitingRootGrant = true;
                return true;
            } catch (Throwable ignored) {
            }
        }
        toast(getString(R.string.cap_no_manager));
        return false;
    }

    /** Only root can answer this; without it we leave {@link #_moduleReady} unknown. */
    private void refreshModuleStateBlocking() {
        if (Capabilities.hasRoot(this)) {
            // Reading the body settles both questions at once: is there a snapshot, and can we
            // actually get at it. A separate existence check would just be a second su round trip.
            setModuleReady(Capabilities.readSnapshot(this, Capabilities.RIOT_PACKAGE) != null);
        }
    }

    // ------------------------------------------------------------------ on-demand extraction

    /**
     * Shows the outcome of the last round. The answer itself is handled by {@link ProbeReceiver},
     * which survives this activity being backgrounded or killed.
     */
    private void showProbeReport() {
        // Entries pulled in the last round wait here for the user's selection; the manager
        // replaces the plain report dialog, because it already shows everything the report
        // carried (count, source) plus per-entry control.
        List<TotpEntry> incoming = ProbeReceiver.takeIncoming(this);
        if (incoming != null && !incoming.isEmpty()) {
            _pendingProbe = null;
            _handler.removeCallbacks(_probeTimeout);
            ProbeReceiver.clearPending(this);
            setModuleReady(Boolean.TRUE);
            reload();

            String source = summarizeSources(incoming);
            TransferManagerDialog.show(this, getString(R.string.transfer_import_title),
                    getString(R.string.transfer_summary_import, incoming.size(), source),
                    incoming, false, null, selected -> {
                        if (selected.isEmpty()) {
                            return;
                        }
                        List<TotpEntry> all = _store.load();
                        if (_store.readFailed()) {
                            toast(getString(R.string.probe_vault_unreadable));
                            return;
                        }
                        all.addAll(selected);
                        if (!_store.save(all)) {
                            toast(getString(R.string.probe_save_failed));
                            return;
                        }
                        reload();
                        toast(getString(R.string.import_ok, source, selected.size()));
                    });
            refreshInfo();
            return;
        }

        JSONObject report = ProbeReceiver.takeReport(this);
        if (report != null) {
            _pendingProbe = null;
            _handler.removeCallbacks(_probeTimeout);
            ProbeReceiver.clearPending(this);

            boolean ok = report.optBoolean("ok", false);
            boolean duplicate = report.optBoolean("duplicate", false);
            String name = report.optString("name", "");
            String error = report.optString("error", "");
            String custom = report.optString("message", "");
            String message;
            if (!custom.isEmpty()) {
                message = custom;
            } else if (ok) {
                message = getString(duplicate ? R.string.report_duplicate : R.string.report_ok,
                        name);
            } else {
                message = getString(R.string.report_failed, error);
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.report_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.report_stay, null)
                    .show();

            setModuleReady(Boolean.TRUE);
            reload();
            refreshInfo();
            return;
        }

        // Nothing came back and the round has been open for too long: tell the user instead of
        // leaving them wondering why they were bounced into the other app.
        long since = ProbeReceiver.pendingSince(this);
        if (since > 0 && System.currentTimeMillis() - since > PROBE_TIMEOUT_MS) {
            ProbeReceiver.clearPending(this);
            String label = _pendingProbe == null ? "Riot Mobile" : _pendingProbe.label;
            _pendingProbe = null;
            _handler.removeCallbacks(_probeTimeout);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.report_title)
                    .setMessage(getString(R.string.report_timeout, label))
                    .setPositiveButton(R.string.report_stay, null)
                    .show();
        }
    }

    /**
     * Starts an import from a source app.
     *
     * <p>Two transports, picked automatically. With root we read the snapshot the module left in
     * the source app's private directory: instant, no wake-up, works whether or not that app is
     * running. Without root we have to ask over a broadcast, which Android defers for apps that
     * have been in the background for a while, hence the wake dialog further down.
     */
    private void startProbe(ExtractTarget target) {
        if (!isInstalled(target.packageName)) {
            toast(getString(R.string.probe_not_installed, target.label));
            return;
        }
        if (AEGIS_PACKAGE.equals(target.packageName)) {
            showAegisRouteDialog(target);
            return;
        }
        if (AZURE_PACKAGE.equals(target.packageName)) {
            showAzureRouteDialog(target);
            return;
        }
        if (_pendingProbe != null) {
            return;
        }
        if (Capabilities.hasRoot(this) && Capabilities.fastPathEnabled(this)) {
            toast(getString(R.string.probe_requesting, target.label));
            new Thread(() -> {
                String body = Capabilities.readSnapshot(this, target.packageName);
                runOnUiThread(() -> {
                    if (body != null && applySnapshot(target, body)) {
                        setModuleReady(Boolean.TRUE);
                        refreshInfo();
                        return;
                    }
                    // No snapshot (or an unreadable one): fall back to asking over a broadcast.
                    setModuleReady(Boolean.FALSE);
                    refreshInfo();
                    probeViaBroadcast(target);
                });
            }).start();
            return;
        }
        probeViaBroadcast(target);
    }

    /** Aegis has two valid transports: the unlocked in-memory vault, or the encrypted file. */
    private void showAegisRouteDialog(ExtractTarget target) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Aegis 导入方式")
                .setItems(new String[]{
                        "模块直读",
                        "Root 读文件",
                }, (dialog, which) -> {
                    if (which == 0) {
                        probeViaBroadcast(target);
                    } else {
                        readAegisByRoot(target);
                    }
                })
                .show();
    }

    private void readAegisByRoot(ExtractTarget target) {
        // Root state may have been cached before KernelSU/Magisk granted us. Re-check on demand
        // instead of trusting a stale "denied" value. Do the blocking su probe off the UI thread.
        new Thread(() -> {
            int rootState = Capabilities.detectRoot(this);
            if (rootState != Capabilities.ROOT_GRANTED) {
                runOnUiThread(() -> toast(rootState == Capabilities.ROOT_TIMEOUT
                        ? "root 检测超时，请在授权管理器里允许 TOTPull 后重试"
                        : "读 Aegis 需要 root；没有 root 请用文件导入"));
                return;
            }
            String vault = Capabilities.readAegisVault();
            runOnUiThread(() -> {
                if (vault == null) {
                    toast("读不到 Aegis vault：" + Capabilities.lastDiagnostic);
                } else {
                    askAegisPassword(vault);
                }
            });
        }).start();
    }

    /** Azure has two transports: module read (no root) or root database read. */
    private void showAzureRouteDialog(ExtractTarget target) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Microsoft Authenticator 导入方式")
                .setItems(new String[]{
                        "模块直读",
                        "Root 读数据库",
                }, (dialog, which) -> {
                    if (which == 0) {
                        probeViaBroadcast(target);
                    } else {
                        readAzureByRoot(target);
                    }
                })
                .show();
    }

    private void readAzureByRoot(ExtractTarget target) {
        new Thread(() -> {
            int rootState = Capabilities.detectRoot(this);
            if (rootState != Capabilities.ROOT_GRANTED) {
                runOnUiThread(() -> toast(rootState == Capabilities.ROOT_TIMEOUT
                        ? "root 检测超时，请在授权管理器里允许 TOTPull 后重试"
                        : "读 Microsoft Authenticator 需要 root；没有 root 请用文件导入"));
                return;
            }
            File db = Capabilities.copyAzureDatabase(this);
            org.json.JSONArray uris = db == null ? null : readAzureAccounts(db);
            runOnUiThread(() -> {
                if (uris == null || uris.length() == 0) {
                    toast("读不到 Microsoft Authenticator 数据：" + Capabilities.lastDiagnostic);
                } else {
                    applySnapshotBatch(target, uris);
                }
            });
        }).start();
    }

    /**
     * Reads OATH accounts out of the copied PhoneFactor database and returns ready-made otpauth
     * URIs. {@code oath_secret_key} is Base64 of the raw HMAC key; re-encode as base32 so the rest
     * of TOTPull sees the same shape as every other importer.
     *
     * <p>Microsoft's software OATH tokens are TOTP/SHA1 with an 8-digit code, so we pin those
     * parameters here instead of relying on the otpauth defaults.
     */
    private org.json.JSONArray readAzureAccounts(File db) {
        org.json.JSONArray out = new org.json.JSONArray();
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            c = sql.rawQuery("SELECT username, oath_secret_key FROM accounts "
                    + "WHERE oath_enabled=1 AND oath_secret_key <> ''", null);
            int userIdx = c.getColumnIndex("username");
            int keyIdx = c.getColumnIndex("oath_secret_key");
            while (c.moveToNext()) {
                byte[] raw = TotpGenerator.base64Decode(c.getString(keyIdx));
                if (raw.length < 10) {
                    continue;
                }
                String secret = TotpGenerator.base32Encode(raw);
                String username = userIdx >= 0 ? c.getString(userIdx) : "";
                String uri = "otpauth://totp/Microsoft:" + android.net.Uri.encode(username)
                        + "?secret=" + secret + "&issuer=Microsoft"
                        + "&digits=8&period=30&algorithm=SHA1";
                out.put(uri);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
            if (sql != null) {
                sql.close();
            }
        }
        return out;
    }

    /** Turns the module's snapshot into entries. Returns false when it carried nothing usable. */
    private boolean applySnapshot(ExtractTarget target, String body) {
        try {
            JSONObject json = new JSONObject(body);

            // Google Authenticator (and any other bulk source) drops every account into one
            // "entries" array. Fold the whole batch in with a single vault write.
            org.json.JSONArray entries = json.optJSONArray("entries");
            if (entries != null) {
                return applySnapshotBatch(target, entries);
            }

            String uri = json.optString("otpauth", "");
            TotpEntry entry = uri.isEmpty() ? null : TotpEntry.fromOtpauth(uri);
            if (entry == null) {
                return false;
            }
            entry.sourcePackage = target.packageName;
            entry.sourceLabel = target.label;

            String puuid = json.optString("puuid", "");
            String created = json.optString("created_at", "");
            StringBuilder note = new StringBuilder();
            if (!puuid.isEmpty()) {
                note.append("puuid=").append(puuid);
            }
            if (!created.isEmpty()) {
                if (note.length() > 0) {
                    note.append('\n');
                }
                note.append("created_at=").append(created);
            }
            if (note.length() > 0) {
                entry.note = note.toString();
            }
            addEntry(entry);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Stores a whole snapshot batch in one pass, mirroring ProbeReceiver.handleEntries(). */
    private boolean applySnapshotBatch(ExtractTarget target, org.json.JSONArray array) {
        List<TotpEntry> incoming = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            TotpEntry entry = TotpEntry.fromOtpauth(array.optString(i));
            if (entry == null) {
                continue;
            }
            entry.sourcePackage = target.packageName;
            entry.sourceLabel = target.label;
            incoming.add(entry);
        }
        if (incoming.isEmpty()) {
            return false;
        }
        // Nothing touches the vault yet; the manager decides which rows come in. Returning
        // true even when the user declines: the snapshot was parsed fine, so falling back to
        // the broadcast probe would only pull the same batch again.
        TransferManagerDialog.show(this, getString(R.string.transfer_import_title),
                getString(R.string.transfer_summary_import, incoming.size(), target.label),
                incoming, false, null, selected -> {
                    if (selected.isEmpty()) {
                        return;
                    }
                    List<TotpEntry> all = _store.load();
                    if (_store.readFailed()) {
                        toast(getString(R.string.probe_vault_unreadable));
                        return;
                    }
                    // Keep every answer as a separate row. De-duplication is a deliberate
                    // maintenance action in the manager, never something an import does silently.
                    all.addAll(selected);
                    if (!_store.save(all)) {
                        toast(getString(R.string.probe_save_failed));
                        return;
                    }
                    reload();
                    toast(getString(R.string.import_ok, target.label, selected.size()));
                });
        return true;
    }

    /**
     * Asks the source app for the seed while keeping it in front of the user.
     *
     * <p>The module only answers from a live process, so the source app is launched right away
     * instead of waiting for it to time out in the background. Retries cover the few seconds that
     * process needs to boot and register its receiver. Answers land in {@link ProbeReceiver},
     * which is manifest-declared and therefore still receives them after this activity is
     * backgrounded.
     */
    private void probeViaBroadcast(ExtractTarget target) {
        _pendingProbe = target;
        ProbeReceiver.markPending(this);

        // The answer arrives while this activity is in the background, and Android 10+ blocks a
        // receiver from bringing us back. A notification carrying the return trip is the reliable
        // path, so the permission is worth asking for before we leave rather than after the fact.
        if (needsNotificationPermission()) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFY);
            return;
        }
        startProbeRound(target);
    }

    /** Launches the source app and starts asking it for the seed. */
    private void startProbeRound(ExtractTarget target) {
        toast(getString(R.string.probe_go_hint, target.label));

        launchTarget(target);
        for (int delay : PROBE_RETRY_MS) {
            _handler.postDelayed(() -> {
                if (_pendingProbe == null) {
                    return;
                }
                sendProbe(target);
            }, delay);
        }
        _handler.postDelayed(_probeTimeout, PROBE_TIMEOUT_MS);
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm
                .PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFY) {
            return;
        }
        // Either answer lets the round go ahead; without the permission we simply fall back to
        // trying to start the activity and, failing that, to the report on next launch.
        ExtractTarget target = _pendingProbe;
        if (target != null) {
            startProbeRound(target);
        }
    }

    private void sendProbe(ExtractTarget target) {
        Intent probe = new Intent(ACTION_PROBE);
        probe.setPackage(target.packageName);
        sendBroadcast(probe);
    }

    /** Stops waiting quietly. The dialog itself is driven by the pending timestamp on resume. */
    private final Runnable _probeTimeout = () -> _pendingProbe = null;

    /**
     * Wakes the source app so its module can answer. The launch intent returned by
     * {@code getLaunchIntentForPackage} carries CATEGORY_LAUNCHER, and OEM launchers (ColorOS in
     * particular) intercept those and pop an app-clone/dual-app chooser instead of just starting
     * the app. Rebuilding the intent around the resolved component, without the category, goes
     * straight through.
     */
    private void launchTarget(ExtractTarget target) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent base = pm.getLaunchIntentForPackage(target.packageName);
            android.content.ComponentName component = base == null ? null : base.getComponent();

            Intent launch;
            if (component != null) {
                launch = new Intent();
                launch.setComponent(component);
            } else {
                launch = base;
            }
            if (launch == null) {
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Throwable ignored) {
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void scanQr() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
            String text = data.getStringExtra(ScanActivity.EXTRA_TEXT);
            if (text != null && !text.isEmpty()) {
                applyImport(text);
            }
            return;
        }
        if (requestCode == REQ_OPEN_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importFromUri(data.getData());
            return;
        }
        if (requestCode == REQ_EDIT_GROUP && resultCode == RESULT_OK
                && data != null) {
            String pkg = data.getStringExtra(EditGroupActivity.EXTRA_PACKAGE);
            ExtractTarget target = pkg == null ? null : findTarget(pkg);
            if (target != null) {
                // Re-import from the source app; the probe flow owns the dialogs from here.
                startProbe(target);
            }
        }
    }

    private void importFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                toast(getString(R.string.import_failed));
                return;
            }
            // Read to EOF: a single read() from a DocumentsProvider pipe may return fewer
            // bytes than the file holds, which used to truncate large vaults into garbage.
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = is.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            if (buffer.size() <= 0) {
                toast(getString(R.string.import_failed));
                return;
            }
            applyImport(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            toast(getString(R.string.import_failed));
        }
    }

    private void applyImport(String text) {
        // All import paths funnel here, so this is the single place to catch encrypted
        // Aegis backups: ask for the password off-thread, then feed the decrypted
        // {"db": ...} back through the unchanged ImportEngine pipeline.
        if (AegisVault.isEncrypted(text)) {
            askAegisPassword(text);
            return;
        }
        ImportEngine.Result result = ImportEngine.importText(text);
        if (result.isEmpty()) {
            toast(getString(R.string.import_failed));
            return;
        }

        // Nothing touches the vault yet: the manager lists what was parsed and the user
        // picks which rows come in. Duplicate secrets are allowed to coexist - cleaning
        // them up stays a separate, explicit operation.
        StringBuilder format = new StringBuilder(
                result.detectedFormat.isEmpty() ? "文本" : result.detectedFormat);
        StringBuilder hint = new StringBuilder();
        if (result.migrationSize > 1) {
            // Google exports split accounts across pages; show the collected progress and
            // remind the user that missing pages still need importing.
            format.append("（已收集 ").append(result.migrationPages).append(" / ")
                    .append(result.migrationSize).append(" 页）");
            if (result.migrationPages < result.migrationSize) {
                hint.append("，还有 ").append(result.migrationSize - result.migrationPages)
                        .append(" 页未导入");
            }
        }
        final String formatLabel = format.toString();
        final String afterHint = hint.toString();
        TransferManagerDialog.show(this, getString(R.string.transfer_import_title),
                getString(R.string.transfer_summary_import, result.entries.size(), formatLabel),
                result.entries, false, null,
                selected -> saveImported(selected, formatLabel, afterHint));
    }

    /** Writes the manager's selection into the vault and reports the outcome. */
    private void saveImported(List<TotpEntry> selected, String format, String afterHint) {
        if (selected.isEmpty()) {
            return;
        }
        List<TotpEntry> current = _store.load();
        if (_store.readFailed()) {
            // Never write over something we could not read: the import would look like it
            // worked and every existing entry would be gone.
            toast(getString(R.string.probe_vault_unreadable));
            return;
        }
        current.addAll(selected);
        if (!_store.save(current)) {
            toast(getString(R.string.probe_save_failed));
            return;
        }
        reload();
        toast(getString(R.string.import_ok, format, selected.size()) + afterHint);
    }

    private void askAegisPassword(String text) {
        View view = getLayoutInflater().inflate(R.layout.dialog_aegis_password, null);
        EditText input = view.findViewById(R.id.aegis_password);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_aegis_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String typed = input.getText().toString();
                    char[] password = typed.toCharArray();
                    decryptAegis(text, password);
                })
                .show();
    }

    private void decryptAegis(String text, char[] password) {
        View progressView = getLayoutInflater().inflate(R.layout.dialog_aegis_working, null);
        final androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .show();

        _importIo.execute(() -> {
            String result = null;
            AegisVault.Reason failure = null;
            try {
                String content = AegisVault.decrypt(text, password);
                // Wrap as {"db": {...}} so parseAegis() consumes it unchanged.
                result = new JSONObject().put("db", new JSONObject(content)).toString();
            } catch (AegisVault.AegisException e) {
                failure = e.reason;
            } catch (Exception e) {
                failure = AegisVault.Reason.BAD_FORMAT;
            } finally {
                Arrays.fill(password, '\0');
            }
            final String decrypted = result;
            final AegisVault.Reason error = failure;
            runOnUiThread(() -> {
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (decrypted != null) {
                    applyImport(decrypted);
                } else {
                    toast(aegisErrorMessage(error));
                }
            });
        });
    }

    private String aegisErrorMessage(AegisVault.Reason reason) {
        if (reason == AegisVault.Reason.WRONG_PASSWORD) {
            return getString(R.string.import_aegis_wrong_password);
        }
        if (reason == AegisVault.Reason.BIOMETRIC_ONLY) {
            return getString(R.string.import_aegis_biometric_only);
        }
        if (reason == AegisVault.Reason.UNSUPPORTED) {
            return getString(R.string.import_aegis_unsupported);
        }
        return getString(R.string.import_aegis_bad_format);
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            toast(getString(R.string.nothing_in_clipboard));
            return;
        }
        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.toString().trim().isEmpty()) {
            toast(getString(R.string.nothing_in_clipboard));
            return;
        }
        applyImport(text.toString());
    }

    private void openEditor(@Nullable String entryId) {
        Intent intent = new Intent(this, EditEntryActivity.class);
        if (entryId != null) {
            intent.putExtra(EditEntryActivity.EXTRA_ID, entryId);
        }
        startActivity(intent);
    }

    /**
     * First launch: land on the info tab and detect privileges instead of popping a dialog.
     * The tier the user lands in decides which features are even shown from then on.
     */
    private void startFirstRun() {
        UiBridge.INSTANCE.showTab(1);
        if (!Capabilities.isRootDecided(this)) {
            detectRoot(true);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
