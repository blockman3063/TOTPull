package io.blockman.totpull;

/**
 * Proof that an Xposed framework injected this app's own process.
 *
 * <p>{@link MainHook} writes {@link #ACTIVE} when the framework loads the module into
 * {@code io.blockman.totpull} itself, which only happens when the user also scoped this app - the
 * same handshake LSPosed modules use to show their own activation state.
 *
 * <p>Deliberately root-free: the flag lives in this process, so reading it needs no {@code su},
 * no snapshot and no cooperation from the target app. That matters because a module can be
 * perfectly healthy while this app has no root at all (LSPatch, or root denied for us).
 *
 * <p>False is not proof of absence. Under LSPatch only the patched target app is injected, so our
 * own process never gets the flag even though extraction still works - which is why the info page
 * reports target-side readiness separately.
 */
public final class HookStatus {
    /**
     * Set from the module side through {@code lpparam.classLoader}, so it lands on the copy of
     * this class that the app actually reads.
     */
    public static volatile boolean ACTIVE = false;

    /** Xposed API version reported by the framework, 0 when unknown. */
    public static volatile int API = 0;

    private HookStatus() {
    }

    public static boolean isActive() {
        return ACTIVE;
    }
}
