package io.blockman.totpull;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Manual per-app language override. Stored in the app's own preferences and applied by
 * wrapping each activity's base context, which keeps us independent of the
 * AppCompatDelegate auto-storage path (its manifest-metadata lookup crashed on this
 * device). Empty preference = follow the system locale.
 */
public final class LocaleHelper {
    private static final String PREF_FILE = "totpull";
    private static final String KEY = "app_locale";

    private LocaleHelper() {
    }

    /** Values: "" = system, "zh-CN", "en". */
    public static String current(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(KEY, "");
    }

    public static void set(Context context, String tag) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, tag).apply();
    }

    public static Context wrap(Context base) {
        String tag = current(base);
        if (tag.isEmpty()) {
            return base;
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(tag));
        return base.createConfigurationContext(config);
    }
}
