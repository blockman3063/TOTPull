package io.blockman.totpull.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.LinkedHashMap;
import java.util.List;

import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpStore;

/**
 * Group-level editor: rename the whole source group, read its facts (source app, package,
 * counts, type breakdown) and reach export / re-import / delete. Opened by long-pressing a
 * group header - no intermediate menu, mirroring how an entry long-press opens its editor.
 */
public class EditGroupActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE = "group_package";

    private TotpStore _store;
    private List<TotpEntry> _entries;
    private String _pkg = "";
    private String _currentTitle = "";

    private TextInputEditText _name;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_group);

        _store = new TotpStore(this);
        _entries = _store.load();

        String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        _pkg = pkg == null ? "" : pkg;

        _name = findViewById(R.id.input_group_name);
        TextView stats = findViewById(R.id.text_group_stats);
        View sync = findViewById(R.id.btn_sync);

        populate(stats);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportGroup());
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDeleteGroup());

        // Only real extract sources can be re-imported; local entries have nothing to probe.
        boolean syncable = MainActivity.findTarget(_pkg) != null;
        sync.setVisibility(syncable ? View.VISIBLE : View.GONE);
        if (syncable) {
            sync.setOnClickListener(v -> {
                // The probe flow (wake dialogs, retries) lives in MainActivity; hand the
                // package back and let it drive.
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PACKAGE, _pkg));
                finish();
            });
        }
    }

    private void populate(TextView stats) {
        int count = 0;
        LinkedHashMap<String, Integer> typeCounts = new LinkedHashMap<>();
        for (TotpEntry e : _entries) {
            String rowKey = e.sourcePackage == null ? "" : e.sourcePackage;
            if (!rowKey.equals(_pkg)) {
                continue;
            }
            count++;
            if (_currentTitle.isEmpty()) {
                _currentTitle = e.displaySource();
            }
            String type = e.isSteam() ? "Steam"
                    : e.isSupported() ? e.type : getString(R.string.settings_type_other);
            Integer seen = typeCounts.get(type);
            typeCounts.put(type, seen == null ? 1 : seen + 1);
        }
        if (_currentTitle.isEmpty()) {
            _currentTitle = _pkg.isEmpty()
                    ? getString(R.string.local_source) : _pkg;
        }
        _name.setText(_currentTitle);
        _name.setSelection(_name.getText().length());

        StringBuilder types = new StringBuilder();
        for (LinkedHashMap.Entry<String, Integer> row : typeCounts.entrySet()) {
            if (types.length() > 0) {
                types.append("  ·  ");
            }
            types.append(row.getKey()).append(" × ").append(row.getValue());
        }
        if (types.length() == 0) {
            types.append("-");
        }

        MainActivity.ExtractTarget target = MainActivity.findTarget(_pkg);
        String source;
        if (target != null) {
            source = target.label; // The app's real name, even if the group was renamed.
        } else if (_pkg.isEmpty()) {
            source = getString(R.string.local_source);
        } else {
            source = getString(R.string.settings_unknown_source);
        }

        stats.setText(getString(R.string.settings_label_source) + ": " + source
                + "\n" + getString(R.string.settings_label_package) + ": "
                + (_pkg.isEmpty() ? getString(R.string.settings_no_package) : _pkg)
                + "\n" + getString(R.string.settings_label_count) + ": "
                + getString(R.string.entry_count, count)
                + "\n" + getString(R.string.settings_label_types) + ": " + types);
    }

    /** Renames the label shown for the whole group by stamping sourceLabel on every row. */
    private void save() {
        String name = _name.getText() == null ? "" : _name.getText().toString().trim();
        if (name.isEmpty()) {
            toast(getString(R.string.group_rename_blank));
            return;
        }
        for (TotpEntry e : _entries) {
            String rowKey = e.sourcePackage == null ? "" : e.sourcePackage;
            if (rowKey.equals(_pkg)) {
                e.sourceLabel = name;
            }
        }
        _store.save(_entries);
        finish();
    }

    /** Shares the group's entries as otpauth lines other authenticator apps can import. */
    private void exportGroup() {
        StringBuilder text = new StringBuilder();
        for (TotpEntry e : _entries) {
            String rowKey = e.sourcePackage == null ? "" : e.sourcePackage;
            if (rowKey.equals(_pkg)) {
                text.append(e.toOtpauthUri()).append('\n');
            }
        }
        if (text.length() == 0) {
            toast(getString(R.string.group_export_empty));
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.group_export_title, _currentTitle));
        share.putExtra(Intent.EXTRA_TEXT, text.toString().trim());
        startActivity(Intent.createChooser(share,
                getString(R.string.group_export_title, _currentTitle)));
    }

    private void confirmDeleteGroup() {
        int count = 0;
        for (TotpEntry e : _entries) {
            String rowKey = e.sourcePackage == null ? "" : e.sourcePackage;
            if (rowKey.equals(_pkg)) {
                count++;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_group_title)
                .setMessage(getString(R.string.delete_group_message, _currentTitle, count))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    for (int i = _entries.size() - 1; i >= 0; i--) {
                        String rowKey = _entries.get(i).sourcePackage == null
                                ? "" : _entries.get(i).sourcePackage;
                        if (rowKey.equals(_pkg)) {
                            _entries.remove(i);
                        }
                    }
                    _store.save(_entries);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
