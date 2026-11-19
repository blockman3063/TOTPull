package io.blockman.totpull.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.blockman.totpull.R;
import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;

/**
 * Two-row-type list: a header per source app, then that app's entries. Grouping uses
 * {@link TotpEntry#sourcePackage}, so everything extracted from one app stays together.
 */
public class EntryAdapter extends BaseAdapter {
    private static final int VIEW_HEADER = 0;
    private static final int VIEW_ENTRY = 1;

    private final Context _context;
    private final List<TotpEntry> _entries;
    private final List<Object> _rows = new ArrayList<>();

    // Filter state. null source/type means "no restriction" for that dimension.
    private String _query = "";
    private String _sourcePackage;
    private String _type;
    private int _visibleCount;

    // Group keys (sourcePackage) the user has folded. Persistent across rebuilds/filters.
    private final Set<String> _collapsed = new HashSet<>();

    /** Receives the three-dot button tap; the activity shows the popup and handles actions. */
    public interface OnEntryMenuListener {
        void onEntryMenu(TotpEntry entry, View anchor);
    }

    private OnEntryMenuListener _menuListener;

    public void setOnEntryMenuListener(OnEntryMenuListener listener) {
        _menuListener = listener;
    }

    public EntryAdapter(Context context, List<TotpEntry> entries) {
        _context = context;
        _entries = entries;
        rebuild();
    }

    /** Applies the search/filter selection and rebuilds the grouped rows. */
    public void setFilter(String query, String sourcePackage, String type) {
        _query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        _sourcePackage = sourcePackage;
        _type = type;
        rebuild();
    }

    public boolean isFiltering() {
        return !_query.isEmpty() || _sourcePackage != null || _type != null;
    }

    /** Flips a group between expanded and folded. The key is the sourcePackage. */
    public void toggleGroup(String key) {
        if (key == null) {
            key = "";
        }
        if (_collapsed.contains(key)) {
            _collapsed.remove(key);
        } else {
            _collapsed.add(key);
        }
        rebuild();
    }

    /** Number of entries currently visible (headers excluded). */
    public int getVisibleCount() {
        return _visibleCount;
    }

    public void rebuild() {
        _rows.clear();
        _visibleCount = 0;

        Map<String, List<TotpEntry>> groups = new LinkedHashMap<>();
        for (TotpEntry entry : _entries) {
            if (!matches(entry)) {
                continue;
            }
            String key = entry.sourcePackage == null ? "" : entry.sourcePackage;
            List<TotpEntry> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(entry);
        }

        List<String> keys = new ArrayList<>(groups.keySet());
        // Extracted sources first (alphabetical), hand-added entries last.
        keys.sort((a, b) -> {
            if (a.isEmpty() && b.isEmpty()) {
                return 0;
            }
            if (a.isEmpty()) {
                return 1;
            }
            if (b.isEmpty()) {
                return -1;
            }
            return a.compareToIgnoreCase(b);
        });

        for (String key : keys) {
            List<TotpEntry> bucket = groups.get(key);
            String label;
            if (key.isEmpty()) {
                label = _context.getString(R.string.local_source);
            } else {
                label = bucket.get(0).displaySource();
            }
            boolean collapsed = _collapsed.contains(key);
            _rows.add(new Header(label, key, bucket.size(), collapsed));
            if (!collapsed) {
                _rows.addAll(bucket);
            }
            _visibleCount += bucket.size();
        }

        notifyDataSetChanged();
    }

    /**
     * All three dimensions AND together. Search covers the visible text plus source and notes;
     * source and type are exact matches chosen in the filter dialog.
     */
    private boolean matches(TotpEntry entry) {
        if (_sourcePackage != null) {
            String pkg = entry.sourcePackage == null ? "" : entry.sourcePackage;
            if (!pkg.equals(_sourcePackage)) {
                return false;
            }
        }
        if (_type != null && !typeMatches(entry, _type)) {
            return false;
        }
        if (_query.isEmpty()) {
            return true;
        }
        return contains(entry.issuer) || contains(entry.name) || contains(entry.group)
                || contains(entry.note) || contains(entry.sourceLabel)
                || contains(entry.sourcePackage) || contains(entry.type);
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(_query);
    }

    private static boolean typeMatches(TotpEntry entry, String type) {
        if (TotpEntry.TYPE_TOTP.equals(type)) {
            return !entry.isHotp() && !entry.isSteam() && entry.isSupported();
        }
        if (TotpEntry.TYPE_HOTP.equals(type)) {
            return entry.isHotp();
        }
        if (TotpEntry.TYPE_STEAM.equals(type)) {
            return entry.isSteam();
        }
        // "other" - unsupported types such as Yandex / MOTP.
        return !entry.isSupported();
    }

    public List<TotpEntry> getEntries() {
        return _entries;
    }

    @Override
    public int getCount() {
        return _rows.size();
    }

    @Override
    public Object getItem(int position) {
        return _rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return _rows.get(position) instanceof Header ? VIEW_HEADER : VIEW_ENTRY;
    }

    @Override
    public boolean isEnabled(int position) {
        // Headers stay enabled: taps fold/unfold, long-press deletes the whole group.
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Object row = _rows.get(position);
        if (row instanceof Header) {
            return getHeaderView((Header) row, convertView, parent);
        }
        return getEntryView((TotpEntry) row, convertView, parent);
    }

    private View getHeaderView(Header header, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null || view.findViewById(R.id.source_name) == null) {
            view = LayoutInflater.from(_context).inflate(R.layout.item_group_header, parent, false);
        }
        TextView name = view.findViewById(R.id.source_name);
        TextView count = view.findViewById(R.id.source_count);
        ImageView arrow = view.findViewById(R.id.group_arrow);
        name.setText(header.title);
        String subtitle = header.packageName.isEmpty()
                ? _context.getString(R.string.entry_count, header.count)
                : header.packageName + "  ·  " + _context.getString(R.string.entry_count, header.count);
        count.setText(subtitle);
        // Point down when expanded, right when folded.
        arrow.setRotation(header.collapsed ? 0f : 90f);
        return view;
    }

    private View getEntryView(TotpEntry entry, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null || view.findViewById(R.id.code) == null) {
            view = LayoutInflater.from(_context).inflate(R.layout.item_entry, parent, false);
            holder = new Holder();
            holder.issuer = view.findViewById(R.id.issuer);
            holder.account = view.findViewById(R.id.account);
            holder.meta = view.findViewById(R.id.meta);
            holder.code = view.findViewById(R.id.code);
            holder.countdown = view.findViewById(R.id.countdown);
            holder.progress = view.findViewById(R.id.progress);
            holder.menu = view.findViewById(R.id.entry_menu);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }

        final TotpEntry menuEntry = entry;
        holder.menu.setOnClickListener(v -> {
            if (_menuListener != null) {
                _menuListener.onEntryMenu(menuEntry, v);
            }
        });

        holder.issuer.setText(entry.displayName());
        String account = entry.displayAccount();
        holder.account.setText(account.equals(entry.displayName()) ? "" : account);
        holder.meta.setText(buildMeta(entry));
        bindCode(holder, entry);
        return view;
    }

    private String buildMeta(TotpEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.type);
        if (!entry.isSteam() && !entry.isHotp()) {
            sb.append(" · ").append(entry.getAlgorithmForMac());
            sb.append(" · ").append(entry.period).append("s");
        }
        if (entry.isHotp()) {
            sb.append(" · #").append(entry.counter);
        }
        if (entry.group != null && entry.group.length() > 0) {
            sb.append(" · ").append(entry.group);
        }
        return sb.toString();
    }

    private void bindCode(Holder holder, TotpEntry entry) {
        if (!entry.isSupported()) {
            holder.code.setText("----");
            holder.countdown.setVisibility(View.GONE);
            holder.progress.setVisibility(View.GONE);
            return;
        }
        String code = TotpGenerator.generate(entry);
        holder.code.setText(code.isEmpty() ? "----" : TotpGenerator.format(code, entry.isSteam()));

        if (entry.isHotp()) {
            // HOTP advances only when used; a clock countdown would be meaningless.
            holder.countdown.setVisibility(View.GONE);
            holder.progress.setVisibility(View.GONE);
        } else {
            long now = System.currentTimeMillis();
            holder.countdown.setVisibility(View.VISIBLE);
            holder.countdown.setText(String.valueOf(TotpGenerator.secondsLeft(entry.period, now)));
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setProgress(TotpGenerator.progressLeft(entry.period, now));
        }
    }

    /** Recomputes codes for the children currently on screen. */
    public void refreshVisibleCodes(ListView listView) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Holder)) {
                continue;
            }
            int position = listView.getFirstVisiblePosition() + i;
            if (position < 0 || position >= _rows.size()) {
                continue;
            }
            Object row = _rows.get(position);
            if (row instanceof TotpEntry) {
                bindCode((Holder) tag, (TotpEntry) row);
            }
        }
    }

    private static class Holder {
        TextView issuer;
        TextView account;
        TextView meta;
        TextView code;
        TextView countdown;
        ProgressBar progress;
        View menu;
    }

    /** One source-app bucket. Tapping folds it, long-pressing deletes the whole group. */
    public static class Header {
        public final String title;
        public final String packageName;
        public final int count;
        public final boolean collapsed;

        Header(String title, String packageName, int count, boolean collapsed) {
            this.title = title;
            this.packageName = packageName;
            this.count = count;
            this.collapsed = collapsed;
        }
    }
}
