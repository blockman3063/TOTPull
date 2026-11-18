package io.blockman.totpull.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.blockman.totpull.Capabilities;
import io.blockman.totpull.ProbeReceiver;
import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;
import io.blockman.totpull.TotpStore;
import io.blockman.totpull.format.ImportEngine;

public class MainActivity extends AppCompatActivity {
    /** Optional extras so an extractor can stamp the originating app onto the entry. */
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_SOURCE_LABEL = "source_label";

    private static final int REQ_OPEN_FILE = 1001;
    /** Asked right before we leave for the source app, so the answer can call us back. */
    private static final int REQ_NOTIFY = 1002;

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
    private static final ExtractTarget[] EXTRACT_TARGETS = {
            new ExtractTarget("com.riotgames.mobile.leagueconnect", "Riot Mobile"),
    };

    private static final class ExtractTarget {
        final String packageName;
        final String label;

        ExtractTarget(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private TotpStore _store;
    private final List<TotpEntry> _entries = new ArrayList<>();
    private EntryAdapter _adapter;
    private ListView _listView;
    private View _emptyView;
    private TextView _codesSubtitle;

    /** 0 = codes list, 1 = info page. */
    private int _tab;
    private View _pageCodes;
    private View _pageInfo;
    private View _navCodesPill;
    private View _navInfoPill;
    private android.widget.ImageView _navCodesIcon;
    private android.widget.ImageView _navInfoIcon;
    private TextView _navCodesLabel;
    private TextView _navInfoLabel;
    private InfoPage _info;

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
    private final Runnable _tick = new Runnable() {
        @Override
        public void run() {
            if (_adapter != null) {
                _adapter.refreshVisibleCodes(_listView);
            }
            _handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _store = new TotpStore(this);
        _adapter = new EntryAdapter(this, _entries);

        _listView = findViewById(R.id.list);
        _emptyView = findViewById(R.id.empty);
        _listView.setAdapter(_adapter);

        _listView.setOnItemClickListener((parent, view, position, id) -> {
            Object row = _adapter.getItem(position);
            if (row instanceof TotpEntry) {
                onEntryClicked((TotpEntry) row);
            }
        });

        _listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Object row = _adapter.getItem(position);
            if (row instanceof TotpEntry) {
                showEntryMenu((TotpEntry) row);
                return true;
            }
            if (row instanceof EntryAdapter.Header) {
                showGroupMenu((EntryAdapter.Header) row);
                return true;
            }
            return false;
        });

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddMenu());

        _codesSubtitle = findViewById(R.id.codes_subtitle);
        // Module readiness survives restarts; only a probe can change it.
        _moduleReady = Capabilities.cachedModuleReady(this);
        setUpNavigation();
        reload();

        handleIntent(getIntent());

        if (!Capabilities.isSetupDone(this)) {
            _handler.postDelayed(this::startFirstRun, 350);
        }
    }

    // ------------------------------------------------------------------ navigation

    private void setUpNavigation() {
        _pageCodes = findViewById(R.id.page_codes);
        _pageInfo = findViewById(R.id.page_info);
        _navCodesPill = findViewById(R.id.nav_codes_pill);
        _navInfoPill = findViewById(R.id.nav_info_pill);
        _navCodesIcon = findViewById(R.id.nav_codes_icon);
        _navInfoIcon = findViewById(R.id.nav_info_icon);
        _navCodesLabel = findViewById(R.id.nav_codes_label);
        _navInfoLabel = findViewById(R.id.nav_info_label);

        findViewById(R.id.nav_codes).setOnClickListener(v -> showTab(0));
        findViewById(R.id.nav_info).setOnClickListener(v -> showTab(1));

        _info = new InfoPage(this, _pageInfo, new InfoPage.Listener() {
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
                Capabilities.setFastPathEnabled(MainActivity.this, enabled);
            }

            @Override
            public void onStart() {
                Capabilities.markSetupDone(MainActivity.this);
                showTab(0);
            }
        });

        showTab(0, false);
    }

    /** Side-effect-free tab switch, used while wiring the layout up. */
    private void applyTab(int tab) {
        _tab = tab;
        _pageCodes.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        _pageInfo.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);

        applyNavState(_navCodesPill, _navCodesIcon, _navCodesLabel, tab == 0);
        applyNavState(_navInfoPill, _navInfoIcon, _navInfoLabel, tab == 1);
    }

    private void showTab(int tab) {
        showTab(tab, true);
    }

    private void showTab(int tab, boolean userInitiated) {
        boolean changed = _tab != tab;
        applyTab(tab);

        if (tab == 1) {
            refreshInfo();
        } else if (userInitiated && changed && !Capabilities.isSetupDone(this)) {
            // Leaving the info page is the same as accepting the detected setup.
            Capabilities.markSetupDone(this);
        }
    }

    private void applyNavState(View pill, android.widget.ImageView icon, TextView label,
                               boolean active) {
        pill.setBackgroundResource(active ? R.drawable.bg_nav_pill : 0);
        int tint = getColor(active ? R.color.nav_item_active : R.color.nav_item_inactive);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        label.setTextColor(tint);
        label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * First launch: land on the info tab and detect privileges instead of popping a dialog.
     * The tier the user lands in decides which features are even shown from then on.
     */
    private void startFirstRun() {
        showTab(1);
        if (!Capabilities.isRootDecided(this)) {
            detectRoot(true);
        }
    }

    /** Single place that records module readiness, in memory and across restarts. */
    private void setModuleReady(Boolean ready) {
        _moduleReady = ready;
        Capabilities.setModuleReady(this, ready);
    }

    private void refreshInfo() {
        if (_info != null) {
            _info.bind(_moduleReady, Capabilities.isSetupDone(this));
        }
    }

    @Override
    protected void onDestroy() {
        _pendingProbe = null;
        _handler.removeCallbacks(_probeTimeout);
        _handler.removeCallbacks(_tick);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        _handler.post(_tick);

        // The answer is delivered to ProbeReceiver, possibly while this activity was not running.
        // Show whatever came back as soon as we are back in front of the user.
        showProbeReport();

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
    protected void onPause() {
        super.onPause();
        _handler.removeCallbacks(_tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        reload();
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
        if (_adapter != null) {
            _adapter.rebuild();
        }
        updateEmptyState();
        updateSubtitle();
    }

    /** "12 个条目 · 2 个来源" under the page title. */
    private void updateSubtitle() {
        if (_codesSubtitle == null) {
            return;
        }
        if (_entries.isEmpty()) {
            _codesSubtitle.setText(R.string.codes_subtitle_empty);
            return;
        }
        java.util.HashSet<String> sources = new java.util.HashSet<>();
        for (TotpEntry entry : _entries) {
            sources.add(entry.sourcePackage == null ? "" : entry.sourcePackage);
        }
        _codesSubtitle.setText(getString(R.string.codes_subtitle, _entries.size(), sources.size()));
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

    private void addEntry(TotpEntry entry) {
        List<TotpEntry> current = _store.load();
        for (TotpEntry existing : current) {
            if (existing.key().equals(entry.key())) {
                toast(getString(R.string.duplicate));
                return;
            }
        }

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
        _adapter.refreshVisibleCodes(_listView);
        toast(getString(R.string.copied, TotpGenerator.format(code, entry.isSteam())));
    }

    private void showEntryMenu(TotpEntry entry) {
        String[] items = new String[]{getString(R.string.edit_title), getString(R.string.delete)};
        new MaterialAlertDialogBuilder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openEditor(entry.id);
                    } else {
                        confirmDelete(entry);
                    }
                })
                .show();
    }

    private void showGroupMenu(EntryAdapter.Header header) {
        String[] items = new String[]{getString(R.string.group_menu_delete)};
        new MaterialAlertDialogBuilder(this)
                .setItems(items, (d, which) -> confirmDeleteGroup(header))
                .show();
    }

    private void confirmDeleteGroup(EntryAdapter.Header header) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_group_title)
                .setMessage(getString(R.string.delete_group_message, header.title, header.count))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    String key = header.packageName == null ? "" : header.packageName;
                    List<TotpEntry> current = _store.load();
                    int removed = 0;
                    for (int i = current.size() - 1; i >= 0; i--) {
                        String rowKey = current.get(i).sourcePackage == null
                                ? "" : current.get(i).sourcePackage;
                        if (rowKey.equals(key)) {
                            current.remove(i);
                            removed++;
                        }
                    }
                    _store.save(current);
                    reload();
                    toast(getString(R.string.delete_group_done, removed));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(TotpEntry entry) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, entry.displayName()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    List<TotpEntry> current = _store.load();
                    for (int i = current.size() - 1; i >= 0; i--) {
                        if (current.get(i).id.equals(entry.id)) {
                            current.remove(i);
                        }
                    }
                    _store.save(current);
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAddMenu() {
        // "Import from an app" is always offered. Only a probe can tell whether the module is
        // there, and it degrades to a broadcast when the snapshot itself is unreadable, so hiding
        // the entry based on the snapshot check would remove a path that still works.
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add(getString(R.string.action_from_app));
        actions.add(0);
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
        String[] labels = new String[EXTRACT_TARGETS.length];
        for (int i = 0; i < EXTRACT_TARGETS.length; i++) {
            labels[i] = EXTRACT_TARGETS[i].label;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.probe_title)
                .setItems(labels, (d, which) -> startProbe(EXTRACT_TARGETS[which]))
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
            showTab(1);
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
            setModuleReady(Capabilities.readSnapshot(this) != null);
        }
    }

    // ------------------------------------------------------------------ on-demand extraction

    /**
     * Shows the outcome of the last round. The answer itself is handled by {@link ProbeReceiver},
     * which survives this activity being backgrounded or killed.
     */
    private void showProbeReport() {
        JSONObject report = ProbeReceiver.takeReport(this);
        if (report != null) {
            _pendingProbe = null;
            _handler.removeCallbacks(_probeTimeout);
            ProbeReceiver.clearPending(this);

            boolean ok = report.optBoolean("ok", false);
            boolean duplicate = report.optBoolean("duplicate", false);
            String name = report.optString("name", "");
            String error = report.optString("error", "");
            String message;
            if (ok) {
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
        if (_pendingProbe != null) {
            return;
        }
        if (Capabilities.hasRoot(this) && Capabilities.fastPathEnabled(this)) {
            toast(getString(R.string.probe_requesting, target.label));
            new Thread(() -> {
                String body = Capabilities.readSnapshot(this);
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

    /** Turns the module's snapshot into an entry. Returns false when it carried nothing usable. */
    private boolean applySnapshot(ExtractTarget target, String body) {
        try {
            JSONObject json = new JSONObject(body);
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

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importFromUri(data.getData());
        }
    }

    private void importFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                toast(getString(R.string.import_failed));
                return;
            }
            byte[] buffer = new byte[8 * 1024 * 1024];
            int read = is.read(buffer);
            if (read <= 0) {
                toast(getString(R.string.import_failed));
                return;
            }
            applyImport(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            toast(getString(R.string.import_failed));
        }
    }

    private void applyImport(String text) {
        ImportEngine.Result result = ImportEngine.importText(text);
        if (result.isEmpty()) {
            toast(getString(R.string.import_failed));
            return;
        }

        List<TotpEntry> current = _store.load();
        int added = 0;
        for (TotpEntry entry : result.entries) {
            boolean duplicate = false;
            for (TotpEntry existing : current) {
                if (existing.key().equals(entry.key())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                current.add(entry);
                added++;
            }
        }

        if (added == 0) {
            toast(getString(R.string.duplicate));
            return;
        }
        _store.save(current);
        reload();

        String format = result.detectedFormat.isEmpty() ? "文本" : result.detectedFormat;
        toast(getString(R.string.import_ok, format, added));
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

    private void updateEmptyState() {
        boolean empty = _entries.isEmpty();
        _emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        _listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
