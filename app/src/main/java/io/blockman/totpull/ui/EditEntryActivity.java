package io.blockman.totpull.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;
import io.blockman.totpull.TotpStore;

/** Editor modelled on Aegis' entry dialog: same fields, same order. */
public class EditEntryActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "entry_id";

    private static final String[] TYPES = {"TOTP", "HOTP", "Steam", "Yandex", "MOTP"};
    private static final String[] ALGORITHMS = {"SHA1", "SHA256", "SHA512"};

    private TotpStore _store;
    private List<TotpEntry> _entries;
    private TotpEntry _entry;
    private boolean _isNew;

    private TextInputEditText _name;
    private TextInputEditText _issuer;
    private TextInputEditText _group;
    private TextInputEditText _note;
    private TextInputEditText _secret;
    private MaterialAutoCompleteTextView _type;
    private MaterialAutoCompleteTextView _algorithm;
    private TextInputEditText _period;
    private TextInputEditText _digits;
    private TextInputEditText _counter;
    private TextInputLayout _counterLayout;
    private TextInputEditText _source;
    private TextView _stats;
    private TextView _delete;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        _store = new TotpStore(this);
        _entries = _store.load();

        bindViews();

        String id = getIntent().getStringExtra(EXTRA_ID);
        _isNew = id == null;
        if (!_isNew) {
            for (TotpEntry candidate : _entries) {
                if (candidate.id.equals(id)) {
                    _entry = candidate;
                    break;
                }
            }
        }
        if (_entry == null) {
            _entry = new TotpEntry();
            _isNew = true;
            _entry.id = id == null ? _entry.id : id;
            _entry.type = TotpEntry.TYPE_TOTP;
        }

        TextView title = findViewById(R.id.title);
        title.setText(_isNew ? R.string.edit_title_new : R.string.edit_title);

        setupDropdown(_type, TYPES);
        setupDropdown(_algorithm, ALGORITHMS);

        populate();

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        _delete.setVisibility(_isNew ? View.GONE : View.VISIBLE);
        _delete.setOnClickListener(v -> confirmDelete());

        _type.setOnItemClickListener((parent, view, position, rowId) -> onTypeChanged(TYPES[position]));
    }

    private void bindViews() {
        _name = findViewById(R.id.input_name);
        _issuer = findViewById(R.id.input_issuer);
        _group = findViewById(R.id.input_group);
        _note = findViewById(R.id.input_note);
        _secret = findViewById(R.id.input_secret);
        _type = findViewById(R.id.input_type);
        _algorithm = findViewById(R.id.input_algorithm);
        _period = findViewById(R.id.input_period);
        _digits = findViewById(R.id.input_digits);
        _counter = findViewById(R.id.input_counter);
        _counterLayout = findViewById(R.id.layout_counter);
        _source = findViewById(R.id.input_source);
        _stats = findViewById(R.id.text_stats);
        _delete = findViewById(R.id.btn_delete);

        _period.setInputType(InputType.TYPE_CLASS_NUMBER);
        _digits.setInputType(InputType.TYPE_CLASS_NUMBER);
        _counter.setInputType(InputType.TYPE_CLASS_NUMBER);
    }

    private void setupDropdown(MaterialAutoCompleteTextView view, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, items);
        view.setAdapter(adapter);
    }

    private void populate() {
        _name.setText(_entry.name);
        _issuer.setText(_entry.issuer);
        _group.setText(_entry.group);
        _note.setText(_entry.note);
        _secret.setText(_entry.secret);
        _type.setText(typeLabel(_entry.type), false);
        _algorithm.setText(_entry.getAlgorithmForMac(), false);
        _period.setText(String.valueOf(_entry.period));
        _digits.setText(String.valueOf(_entry.digits));
        _counter.setText(String.valueOf(_entry.counter));

        if (_entry.sourcePackage != null && _entry.sourcePackage.length() > 0) {
            String label = _entry.sourceLabel == null || _entry.sourceLabel.isEmpty()
                    ? _entry.sourcePackage
                    : _entry.sourceLabel + "\n" + _entry.sourcePackage;
            _source.setText(label);
        } else {
            _source.setText(getString(R.string.local_source));
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        String lastUsed = _entry.lastUsedAt > 0 ? fmt.format(new Date(_entry.lastUsedAt))
                : getString(R.string.never);
        _stats.setText(getString(R.string.field_usage) + ": " + _entry.usageCount
                + "\n" + getString(R.string.field_last_used) + ": " + lastUsed);

        onTypeChanged(_entry.type);
    }
    private String typeLabel(String type) {
        if (TotpEntry.TYPE_HOTP.equals(type)) {
            return "HOTP";
        }
        if (TotpEntry.TYPE_STEAM.equals(type)) {
            return "Steam";
        }
        if (TotpEntry.TYPE_YANDEX.equals(type)) {
            return "Yandex";
        }
        if (TotpEntry.TYPE_MOTP.equals(type)) {
            return "MOTP";
        }
        return "TOTP";
    }

    private String normalizeType(String label) {
        if ("HOTP".equals(label)) {
            return TotpEntry.TYPE_HOTP;
        }
        if ("Steam".equals(label)) {
            return TotpEntry.TYPE_STEAM;
        }
        if ("Yandex".equals(label)) {
            return TotpEntry.TYPE_YANDEX;
        }
        if ("MOTP".equals(label)) {
            return TotpEntry.TYPE_MOTP;
        }
        return TotpEntry.TYPE_TOTP;
    }

    private void onTypeChanged(String label) {
        applyTypeUi(normalizeType(label));
    }

    private void applyTypeUi(String type) {
        boolean hotp = TotpEntry.TYPE_HOTP.equals(type);
        boolean steam = TotpEntry.TYPE_STEAM.equals(type);

        _counterLayout.setVisibility(hotp ? View.VISIBLE : View.GONE);
        _period.setEnabled(!hotp && !steam);
        _digits.setEnabled(!hotp && !steam);
        _algorithm.setEnabled(!steam);
        if (steam) {
            _algorithm.setText("SHA1", false);
            _period.setText("30");
            _digits.setText("5");
        }
    }

    private void save() {
        String secret = TotpGenerator.normalizeSecret(text(_secret));
        if (secret.isEmpty()) {
            toast(getString(R.string.secret_required));
            return;
        }
        if (!TotpGenerator.isValidBase32(secret)) {
            toast(getString(R.string.manual_invalid));
            return;
        }

        String type = normalizeType(text(_type));

        _entry.secret = secret;
        _entry.type = type;
        _entry.name = text(_name);
        _entry.issuer = text(_issuer);
        _entry.group = text(_group);
        _entry.note = text(_note);
        _entry.algorithm = text(_algorithm).isEmpty() ? "SHA1" : text(_algorithm);

        int period = parseInt(text(_period), 30);
        int digits = parseInt(text(_digits), TotpEntry.TYPE_STEAM.equals(type) ? 5 : 6);
        if (period < 10 || period > 300) {
            period = 30;
        }
        if (digits < 5 || digits > 8) {
            digits = 6;
        }
        _entry.period = period;
        _entry.digits = digits;
        _entry.counter = parseLong(text(_counter), 0);

        if (_entry.issuer.isEmpty()) {
            _entry.issuer = _entry.name.isEmpty() ? "未命名" : _entry.name;
        }

        boolean replaced = false;
        for (int i = 0; i < _entries.size(); i++) {
            if (_entries.get(i).id.equals(_entry.id)) {
                _entries.set(i, _entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            _entries.add(_entry);
        }
        _store.save(_entries);
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, _entry.displayName()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    for (int i = _entries.size() - 1; i >= 0; i--) {
                        if (_entries.get(i).id.equals(_entry.id)) {
                            _entries.remove(i);
                        }
                    }
                    _store.save(_entries);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String text(TextView view) {
        CharSequence value = view.getText();
        return value == null ? "" : value.toString().trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
