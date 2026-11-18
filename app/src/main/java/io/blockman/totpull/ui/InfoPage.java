package io.blockman.totpull.ui;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import io.blockman.totpull.Capabilities;
import io.blockman.totpull.HookStatus;
import io.blockman.totpull.R;

/**
 * The "info" tab: a status card plus grouped label/value rows, the way LSPosed and ColorDuo
 * present their own info pages. It states conclusions only - no explanatory paragraphs.
 *
 * <p>Rows are built in code rather than in XML because includes would share child ids across the
 * page, and every row needs its own label/value pair.
 */
public class InfoPage {
    /** Callbacks back into the activity; this class owns no capability logic of its own. */
    public interface Listener {
        void onRedetect();

        void onOpenRootManager();

        void onFastPathChanged(boolean enabled);

        void onStart();
    }

    private final Activity _activity;
    private final Listener _listener;

    private final LinearLayout _statusCard;
    private final TextView _statusBadge;
    private final TextView _statusTitle;
    private final LinearLayout _groupPerm;
    private final LinearLayout _groupFeature;
    private final LinearLayout _groupAbout;
    private final MaterialButton _btnStart;
    private final MaterialButton _btnManager;

    /** Guards the switch listener while {@link #bind} is writing initial values. */
    private boolean _binding;

    public InfoPage(Activity activity, View root, Listener listener) {
        _activity = activity;
        _listener = listener;

        _statusCard = root.findViewById(R.id.status_card);
        _statusBadge = root.findViewById(R.id.status_badge);
        _statusTitle = root.findViewById(R.id.status_title);
        _groupPerm = root.findViewById(R.id.group_perm);
        _groupFeature = root.findViewById(R.id.group_feature);
        _groupAbout = root.findViewById(R.id.group_about);
        _btnStart = root.findViewById(R.id.btn_start);
        _btnManager = root.findViewById(R.id.btn_manager);

        MaterialButton redetect = root.findViewById(R.id.btn_redetect);
        redetect.setOnClickListener(v -> _listener.onRedetect());
        _btnManager.setOnClickListener(v -> _listener.onOpenRootManager());
        _btnStart.setOnClickListener(v -> _listener.onStart());
    }

    /** @param moduleReady null when we cannot tell (no root), TRUE/FALSE once we can */
    public void bind(Boolean moduleReady, boolean setupDone) {
        _binding = true;
        try {
            boolean root = Capabilities.hasRoot(_activity);
            int state = Capabilities.cachedRootState(_activity);
            boolean decided = Capabilities.isRootDecided(_activity);
            boolean moduleOk = Boolean.TRUE.equals(moduleReady);
            // Handshake flag: the framework injected this app, so the module is installed and in
            // scope here. Independent of root and of whether the target app is running.
            boolean injected = HookStatus.isActive();

            bindStatus(root, state, decided, moduleOk, injected);
            bindPermissions(root, state, decided, injected);
            bindFeatures(root, moduleOk);
            bindAbout();

            _btnStart.setVisibility(setupDone ? View.GONE : View.VISIBLE);
            _btnManager.setVisibility(root ? View.GONE : View.VISIBLE);
        } finally {
            _binding = false;
        }
    }

    // ------------------------------------------------------------------ status card

    private void bindStatus(boolean root, int state, boolean decided, boolean moduleOk,
                            boolean injected) {
        String title;
        int bg;
        int textColor = R.color.status_warn_text;

        if (root && moduleOk) {
            title = str(R.string.info_level_full);
            bg = R.drawable.bg_status_ok;
            textColor = R.color.status_ok_text;
        } else if (root) {
            title = str(R.string.info_level_partial);
            bg = R.drawable.bg_status_warn;
        } else if (injected) {
            // Framework works here, just without the root fast path.
            title = str(R.string.info_level_standard);
            bg = R.drawable.bg_status_warn;
        } else if (state == Capabilities.ROOT_TIMEOUT) {
            title = str(R.string.info_level_busy);
            bg = R.drawable.bg_status_bad;
            textColor = R.color.status_bad_text;
        } else if (decided) {
            title = str(R.string.info_level_basic);
            bg = R.drawable.bg_status_warn;
        } else {
            title = str(R.string.info_level_undetected);
            bg = R.drawable.bg_status_warn;
        }

        _statusCard.setBackgroundResource(bg);
        _statusTitle.setText(title);
        _statusTitle.setTextColor(color(textColor));
        _statusBadge.setTextColor(color(textColor));
    }

    // ------------------------------------------------------------------ permission rows

    private void bindPermissions(boolean root, int state, boolean decided, boolean injected) {
        _groupPerm.removeAllViews();

        String rootValue;
        int rootColor;
        if (state == Capabilities.ROOT_GRANTED) {
            rootValue = str(R.string.row_root_granted);
            rootColor = R.color.status_ok_text;
        } else if (state == Capabilities.ROOT_TIMEOUT) {
            rootValue = str(R.string.row_root_timeout);
            rootColor = R.color.status_bad_fg;
        } else if (decided) {
            rootValue = str(R.string.row_root_denied);
            rootColor = R.color.text_secondary;
        } else {
            rootValue = str(R.string.row_root_unknown);
            rootColor = R.color.text_secondary;
        }
        addRow(_groupPerm, str(R.string.row_root), rootValue, rootColor);

        // Read straight from our own process: the module sets this flag when the framework
        // injects us, which is the same handshake Xposed modules use to report their own state.
        addRow(_groupPerm, str(R.string.row_lsposed),
                str(injected ? R.string.row_lsposed_on : R.string.row_lsposed_off),
                injected ? R.color.status_ok_text : R.color.text_secondary);
    }

    // ------------------------------------------------------------------ feature rows

    private void bindFeatures(boolean root, boolean moduleOk) {
        _groupFeature.removeAllViews();

        addRow(_groupFeature, str(R.string.row_extract),
                str(moduleOk ? R.string.row_extract_ok : R.string.row_extract_unconfirmed),
                moduleOk ? R.color.status_ok_text : R.color.text_secondary);

        // Root-only: hidden by default when there is no root, per the privilege-tier rule.
        if (root) {
            addSwitchRow(_groupFeature);
        }
    }

    private void bindAbout() {
        _groupAbout.removeAllViews();
        addRow(_groupAbout, str(R.string.row_version), versionName(), R.color.text_secondary);
        addRow(_groupAbout, str(R.string.row_target), "Riot Mobile", R.color.text_secondary);
    }

    // ------------------------------------------------------------------ row builders

    private void addRow(LinearLayout parent, String label, String value, int valueColor) {
        View row = LayoutInflater.from(_activity).inflate(R.layout.item_info_row, parent, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(label);
        TextView valueView = row.findViewById(R.id.row_value);
        valueView.setText(value);
        valueView.setTextColor(color(valueColor));
        row.setBackground(null);
        parent.addView(row);
    }

    private void addSwitchRow(LinearLayout parent) {
        View row = LayoutInflater.from(_activity).inflate(R.layout.item_info_switch, parent, false);
        ((TextView) row.findViewById(R.id.row_label)).setText(str(R.string.row_fast));
        SwitchMaterial toggle = row.findViewById(R.id.row_switch);
        toggle.setChecked(Capabilities.fastPathEnabled(_activity));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (_binding) {
                return;
            }
            _listener.onFastPathChanged(checked);
        });
        parent.addView(row);
    }

    private String versionName() {
        try {
            PackageInfo info = _activity.getPackageManager()
                    .getPackageInfo(_activity.getPackageName(), 0);
            return info.versionName == null ? "1.0" : info.versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private String str(int resId) {
        return _activity.getString(resId);
    }

    private int color(int resId) {
        return _activity.getColor(resId);
    }
}
