package io.blockman.totpull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern (libxposed API 102) entry point.
 *
 * <p>All extraction/decryption/transport logic lives in {@link HookCore}; this class only installs
 * hooks. API 102 gives each hook a stable id, so a module update can replace hooks atomically and
 * the framework can hot-reload the module without restarting the scoped app.
 */
public class MainHook extends XposedModule {
    private static final String TAG = "TOTPull";

    private static final String RIOT = "com.riotgames.mobile.leagueconnect";
    private static final String STEAM = "com.valvesoftware.android.steam.community";
    private static final String GA = "com.google.android.apps.authenticator2";
    private static final String AEGIS = "com.beemdevelopment.aegis";
    private static final String AZURE = "com.azure.authenticator";

    /** R8 name of Riot's androidx.security SharedPreferences wrapper. */
    private static final String RIOT_WRAPPER = "y43";

    private final List<HookHandle> _handles = new ArrayList<>();
    private String _package;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        moduleLog("module loaded in " + param.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!RIOT.equals(pkg) && !STEAM.equals(pkg) && !GA.equals(pkg)
                && !AEGIS.equals(pkg) && !AZURE.equals(pkg)) {
            return;
        }
        _package = pkg;
        ClassLoader cl = param.getClassLoader();
        moduleLog("attached to " + pkg);

        hookApplication();
        if (RIOT.equals(pkg)) {
            hookRiot(cl);
        } else if (AEGIS.equals(pkg)) {
            hookAegis(cl);
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        moduleLog("hot reloading");
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        moduleLog("hot reloaded, old hooks=" + param.getOldHookHandles().size());
        for (HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Registers the probe receiver through our own business layer. */
    private void hookApplication() {
        int i = 0;
        for (Method method : methodsNamed(Application.class, "onCreate")) {
            final String id = "totpull-app-oncreate-" + (i++);
            hook(method, id, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Context) {
                    HookCore.onApplication((Context) chain.getThisObject(), _package, label(_package));
                }
                return result;
            });
        }
    }

    /** Remember Riot's decrypted SharedPreferences wrapper instances. */
    private void hookRiot(ClassLoader cl) {
        Class<?> wrapper;
        try {
            wrapper = Class.forName(RIOT_WRAPPER, false, cl);
        } catch (Throwable t) {
            moduleLog("Riot wrapper not found: " + t);
            return;
        }
        for (String name : new String[]{"getAll", "getString", "getStringSet", "contains"}) {
            int i = 0;
            for (Method method : methodsNamed(wrapper, name)) {
                final String id = "totpull-riot-" + name + "-" + (i++);
                hook(method, id, chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof SharedPreferences) {
                        HookCore.remember((SharedPreferences) chain.getThisObject());
                    }
                    return result;
                });
            }
        }
    }

    /**
     * Aegis 3.4.3 on this device is heavily R8-processed: VaultRepository is merged away and its
     * accessors are inlined. The AES/GCM doFinal that decrypts the vault is still a stable method,
     * so observe its plaintext and keep otpauth URIs in HookCore.
     */
    private void hookAegis(ClassLoader cl) {
        int i = 0;
        for (Method method : methodsNamed(Cipher.class, "doFinal")) {
            final String id = "totpull-aegis-cipher-" + (i++);
            hook(method, id, chain -> {
                Object result = chain.proceed();
                inspectCipher(result, chain.getArgs());
                return result;
            });
        }
        try {
            Class<?> activity = Class.forName(
                    "com.beemdevelopment.aegis.ui.AegisActivity", false, cl);
            int j = 0;
            for (Method method : methodsNamed(activity, "onResume")) {
                final String id = "totpull-aegis-resume-" + (j++);
                hook(method, id, chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Context) {
                        HookCore.announceAegisIfReady((Context) chain.getThisObject());
                    }
                    return result;
                });
            }
        } catch (Throwable t) {
            moduleLog("Aegis onResume hook failed: " + t);
        }
    }

    private void inspectCipher(Object result, List<Object> args) {
        if (result instanceof byte[]) {
            HookCore.captureAegisVaultBytes((byte[]) result);
            return;
        }
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof byte[]) {
                HookCore.captureAegisVaultBytes((byte[]) arg);
            } else if (arg instanceof ByteBuffer) {
                ByteBuffer copy = ((ByteBuffer) arg).duplicate();
                copy.clear();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                HookCore.captureAegisVaultBytes(bytes);
            }
        }
    }

    private void hook(Method method, String id, Hooker hooker) {
        try {
            _handles.add(hook(method)
                    .setId(id)
                    .setExceptionMode(ExceptionMode.PASSTHROUGH)
                    .intercept(hooker));
        } catch (Throwable t) {
            moduleLog("hook " + id + " failed: " + t);
        }
    }

    private static List<Method> methodsNamed(Class<?> type, String name) {
        List<Method> result = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private static String label(String pkg) {
        if (RIOT.equals(pkg)) return "Riot Mobile";
        if (STEAM.equals(pkg)) return "Steam";
        if (GA.equals(pkg)) return "谷歌验证器";
        if (AEGIS.equals(pkg)) return "Aegis";
        if (AZURE.equals(pkg)) return "Microsoft Authenticator";
        return pkg;
    }

    private void moduleLog(String msg) {
        log(Log.INFO, TAG, msg);
    }
}
