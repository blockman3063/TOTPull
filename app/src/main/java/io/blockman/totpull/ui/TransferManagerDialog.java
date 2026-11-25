package io.blockman.totpull.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;

/**
 * The import/export confirmation manager. Lists every entry that is about to move, all ticked
 * by default, with a select-all toggle and an invert button; the callback receives only the
 * entries that are still ticked when the user confirms. Confirming with zero entries selected
 * is impossible - the positive button disables itself.
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
     * @param summary  static header line: entry count plus where they come from / go to
     * @param isExport only decides the wording of the confirm button
     */
    public static void show(AppCompatActivity activity, String title, String summary,
                            List<TotpEntry> entries, boolean isExport, Callback callback) {
        View view = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_transfer_manager, null);
        TextView summaryView = view.findViewById(R.id.tm_summary);
        CheckBox selectAll = view.findViewById(R.id.tm_select_all);
        View invert = view.findViewById(R.id.btn_invert);
        LinearLayout list = view.findViewById(R.id.tm_list);
        ScrollView scroll = view.findViewById(R.id.tm_scroll);
        summaryView.setText(summary);

        List<CheckBox> checks = new ArrayList<>();
        LayoutInflater inflater = activity.getLayoutInflater();
        for (TotpEntry entry : entries) {
            View row = inflater.inflate(R.layout.item_transfer_entry, list, false);
            CheckBox check = row.findViewById(R.id.tm_check);
            check.setChecked(true);
            TextView name = row.findViewById(R.id.tm_name);
            TextView meta = row.findViewById(R.id.tm_meta);

            String account = entry.displayAccount();
            String display = entry.displayName();
            name.setText(account.isEmpty() || account.equals(display)
                    ? display : display + " · " + account);
            String source = entry.displaySource();
            meta.setText(typeLabel(activity, entry)
                    + (source.isEmpty() ? "" : " · " + source));

            // The checkbox itself is not clickable so the whole row is the touch target.
            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            list.addView(row);
            checks.add(check);
        }

        final AlertDialog[] holder = new AlertDialog[1];
        // Guards against listener loops: setChecked() on the rows re-fires their listeners,
        // which would bounce back into setChecked() on the select-all box forever.
        final boolean[] syncing = {false};

        Runnable syncHead = () -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            int selected = 0;
            for (CheckBox check : checks) {
                if (check.isChecked()) {
                    selected++;
                }
            }
            selectAll.setChecked(selected == checks.size());
            AlertDialog dialog = holder[0];
            if (dialog != null) {
                android.widget.Button positive =
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setText(confirmLabel(activity, isExport, selected));
                positive.setEnabled(selected > 0);
            }
            syncing[0] = false;
        };

        for (CheckBox check : checks) {
            check.setOnCheckedChangeListener((button, checked) -> syncHead.run());
        }
        // The guard matters here: syncHead mirrors this box's state after every row change,
        // and without the early return that setChecked would re-enter this listener and
        // stomp every row back to the box's old value.
        selectAll.setOnCheckedChangeListener((button, checked) -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            for (CheckBox check : checks) {
                check.setChecked(checked);
            }
            syncing[0] = false;
            syncHead.run();
        });
        invert.setOnClickListener(v -> {
            syncing[0] = true;
            for (CheckBox check : checks) {
                check.setChecked(!check.isChecked());
            }
            syncing[0] = false;
            syncHead.run();
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(confirmLabel(activity, isExport, entries.size()),
                        (d, which) -> {
                            List<TotpEntry> selected = new ArrayList<>();
                            for (int i = 0; i < checks.size(); i++) {
                                if (checks.get(i).isChecked()) {
                                    selected.add(entries.get(i));
                                }
                            }
                            callback.onConfirmed(selected);
                        })
                .create();
        holder[0] = dialog;
        dialog.show();
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
