package io.blockman.totpull.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;

/**
 * The import/export confirmation manager. Lists every entry that is about to move, all ticked
 * by default, with a select-all toggle, an invert button and a search box; the callback
 * receives only the entries that are still ticked when the user confirms. Confirming with
 * zero entries selected is impossible - the positive button disables itself.
 *
 * <p>Search only shapes the view: the tick state lives in a per-entry map, so hiding rows
 * never silently changes what gets exported, and select-all/invert keep acting on every
 * selectable entry, visible or not. Rows are always alphabetical — vault order and
 * last-use are meaningless inside a pick list.
 */
public final class TransferManagerDialog {

    public interface Callback {
        void onConfirmed(List<TotpEntry> selected);
    }

    /** Cap on the list area so huge vaults stay inside the dialog instead of off-screen. */
    private static final int MAX_LIST_DP = 320;

    private TransferManagerDialog() {
    }

    /**
     * @param summary     static header line: entry count plus where they come from / go to
     * @param isExport    only decides the wording of the confirm button
     * @param unsupported entries that the chosen mode cannot carry (e.g. Steam in a Google
     *                    migration): rendered grey with a disabled checkbox and never
     *                    collected; may be empty when everything is selectable
     */
    public static void show(AppCompatActivity activity, String title, String summary,
                            List<TotpEntry> entries, boolean isExport,
                            java.util.Set<TotpEntry> unsupported, Callback callback) {
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_transfer_manager, null);
        TextView summaryView = view.findViewById(R.id.tm_summary);
        CheckBox selectAll = view.findViewById(R.id.tm_select_all);
        View invert = view.findViewById(R.id.btn_invert);
        EditText searchInput = view.findViewById(R.id.tm_search);
        TextView noMatch = view.findViewById(R.id.tm_no_match);
        LinearLayout list = view.findViewById(R.id.tm_list);
        ScrollView scroll = view.findViewById(R.id.tm_scroll);
        summaryView.setText(summary);

        // mOTP / Yandex entries are disabled app-wide and never listed anywhere, so they
        // cannot be picked for a transfer either.
        final List<TotpEntry> shown = new ArrayList<>();
        for (TotpEntry entry : entries) {
            if (entry.isSupported()) {
                shown.add(entry);
            }
        }
        java.util.Set<TotpEntry> blocked =
                unsupported == null ? java.util.Collections.emptySet() : unsupported;
        // Tick state keyed by entry, independent of what is currently rendered; blocked
        // entries stay unticked forever.
        final Map<TotpEntry, Boolean> checked = new HashMap<>();
        for (TotpEntry entry : shown) {
            checked.put(entry, !blocked.contains(entry));
        }
        final AlertDialog[] holder = new AlertDialog[1];
        // Guards against listener loops: setChecked() on the rows re-fires their listeners,
        // which would bounce back into setChecked() on the select-all box forever. Rebuild
        // carries its own guard so select-all can call it while "syncing" is held.
        final boolean[] syncing = {false};
        final boolean[] rebuilding = {false};
        final String[] query = {""};

        Runnable syncHead = () -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            int selected = 0;
            int selectableCount = 0;
            for (TotpEntry entry : shown) {
                if (blocked.contains(entry)) {
                    continue;
                }
                selectableCount++;
                if (Boolean.TRUE.equals(checked.get(entry))) {
                    selected++;
                }
            }
            selectAll.setChecked(selectableCount > 0 && selected == selectableCount);
            AlertDialog dialog = holder[0];
            if (dialog != null) {
                Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setText(confirmLabel(activity, isExport, selected));
                positive.setEnabled(selected > 0);
            }
            syncing[0] = false;
        };

        LayoutInflater inflater = activity.getLayoutInflater();
        Runnable rebuildRows = () -> {
            if (rebuilding[0]) {
                return;
            }
            rebuilding[0] = true;
            list.removeAllViews();
            List<TotpEntry> visible = new ArrayList<>();
            for (TotpEntry entry : shown) {
                if (entryMatches(entry, query[0])) {
                    visible.add(entry);
                }
            }
            // Always alphabetical in the manager: vault order and last-use make no sense
            // for a pick list, and dragging does not exist here.
            sortEntries(visible);
            noMatch.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
            for (TotpEntry entry : visible) {
                boolean ok = !blocked.contains(entry);
                View row = inflater.inflate(R.layout.item_transfer_entry, list, false);
                CheckBox check = row.findViewById(R.id.tm_check);
                check.setChecked(ok && Boolean.TRUE.equals(checked.get(entry)));
                check.setEnabled(ok);
                TextView name = row.findViewById(R.id.tm_name);
                TextView meta = row.findViewById(R.id.tm_meta);

                String account = entry.displayAccount();
                String display = entry.displayName();
                name.setText(account.isEmpty() || account.equals(display)
                        ? display : display + " · " + account);
                String source = entry.displaySource();
                meta.setText(typeLabel(activity, entry)
                        + (source.isEmpty() ? "" : " · " + source)
                        + (ok ? "" : " · 当前模式不支持"));

                if (ok) {
                    // The checkbox itself is not clickable so the whole row is the touch target.
                    row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
                    check.setOnCheckedChangeListener((button, isChecked) -> {
                        if (rebuilding[0]) {
                            return;
                        }
                        checked.put(entry, isChecked);
                        syncHead.run();
                    });
                } else {
                    row.setAlpha(0.45f);
                }
                list.addView(row);
            }
            rebuilding[0] = false;
        };

        selectAll.setOnCheckedChangeListener((button, tick) -> {
            if (syncing[0]) {
                return;
            }
            // Acts on every selectable entry, not just the ones the search shows. No manual
            // flag juggling here: syncHead() guards itself when it writes the checkbox back,
            // and rebuildRows() carries its own guard, so both run with the flag cleared.
            for (TotpEntry entry : shown) {
                if (!blocked.contains(entry)) {
                    checked.put(entry, tick);
                }
            }
            rebuildRows.run();
            syncHead.run();
        });
        invert.setOnClickListener(v -> {
            for (TotpEntry entry : shown) {
                if (!blocked.contains(entry)) {
                    checked.put(entry, !Boolean.TRUE.equals(checked.get(entry)));
                }
            }
            rebuildRows.run();
            syncHead.run();
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query[0] = s.toString().trim();
                rebuildRows.run();
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(confirmLabel(activity, isExport, shown.size()),
                        (d, which) -> {
                            // Vault order, independent of the current view sort/search.
                            List<TotpEntry> selected = new ArrayList<>();
                            for (TotpEntry entry : shown) {
                                if (!blocked.contains(entry)
                                        && Boolean.TRUE.equals(checked.get(entry))) {
                                    selected.add(entry);
                                }
                            }
                            callback.onConfirmed(selected);
                        })
                .create();
        holder[0] = dialog;
        dialog.show();
        rebuildRows.run();
        syncHead.run();

        // The list starts full-size so wrap_content can measure it; then cap the scroll area
        // so a large vault does not push the buttons off-screen.
        view.post(() -> {
            int max = Math.round(MAX_LIST_DP
                    * activity.getResources().getDisplayMetrics().density);
            if (scroll.getHeight() > max) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = max;
                scroll.setLayoutParams(lp);
            }
        });
    }

    /** Same search semantics as the codes page: every token must hit somewhere. */
    private static boolean entryMatches(TotpEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String text = safe(entry.issuer) + "\n" + safe(entry.name) + "\n" + safe(entry.group)
                + "\n" + safe(entry.note) + "\n" + safe(entry.sourceLabel) + "\n"
                + safe(entry.sourcePackage) + "\n" + safe(entry.type);
        text = text.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty() && !text.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Alphabetical by issuer then account — the only ordering that helps picking here. */
    private static void sortEntries(List<TotpEntry> list) {
        list.sort((a, b) -> (safe(a.issuer) + "\n" + safe(a.name))
                .compareToIgnoreCase(safe(b.issuer) + "\n" + safe(b.name)));
    }

    private static String typeLabel(AppCompatActivity activity, TotpEntry entry) {
        if (entry.isSteam()) {
            return activity.getString(R.string.transfer_type_steam);
        }
        return activity.getString(entry.isHotp()
                ? R.string.transfer_type_hotp : R.string.transfer_type_totp);
    }

    private static String confirmLabel(AppCompatActivity activity, boolean isExport, int count) {
        return activity.getString(isExport
                ? R.string.transfer_export_confirm : R.string.transfer_import_confirm, count);
    }
}
