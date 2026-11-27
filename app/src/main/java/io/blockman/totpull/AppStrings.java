package io.blockman.totpull;

import android.content.Context;

/**
 * Locale-independent access to string resources for classes that have no Context
 * (data models and parsers). Initialized by {@link App}.
 */
public final class AppStrings {
    private static Context sCtx;

    private AppStrings() {
    }

    public static void init(Context context) {
        sCtx = context.getApplicationContext();
    }

    public static String get(int resId) {
        return sCtx == null ? "" : sCtx.getString(resId);
    }

    public static String get(int resId, Object... args) {
        return sCtx == null ? "" : sCtx.getString(resId, args);
    }
}
