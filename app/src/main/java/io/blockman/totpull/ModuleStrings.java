package io.blockman.totpull;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;

/**
 * String resources for code running inside a hooked host process. The host cannot use
 * {@link AppStrings} directly (that resolves against the host's resources), so this loads
 * the module APK's own resources through the package manager; the locale follows the
 * host process config, which follows the system.
 */
public final class ModuleStrings {
    private static final String MODULE_PKG = "io.blockman.totpull";
    private static Resources sRes;

    private ModuleStrings() {
    }

    public static String get(Context hostContext, String name) {
        if (hostContext == null) {
            return "";
        }
        try {
            if (sRes == null) {
                ApplicationInfo info = hostContext.getPackageManager()
                        .getApplicationInfo(MODULE_PKG, 0);
                sRes = hostContext.getPackageManager().getResourcesForApplication(info);
            }
            int id = sRes.getIdentifier(name, "string", MODULE_PKG);
            if (id != 0) {
                return sRes.getString(id);
            }
        } catch (Throwable ignored) {
            // Fall through: a broken lookup must never break a probe reply.
        }
        return "";
    }
}
