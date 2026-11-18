# TOTPull

Pulls the TOTP seed out of Riot Mobile's encrypted storage, so you can keep generating your own two-factor codes after switching devices or uninstalling the client.

## Why a hook is needed

Riot Mobile keeps its TOTP secret in `androidx.security` EncryptedSharedPreferences. The master key lives in the Android keystore and is bound to the app's uid inside the TEE. A root shell can copy the ciphertext file but can never decrypt it — the plaintext only ever exists inside Riot's own process.

So the seed has to be read from the inside. That is the whole reason this project ships as an Xposed module.

## Requirements

- Android 7.0+ (API 24)
- Riot Mobile (`com.riotgames.mobile.leagueconnect`) installed and signed in
- An Xposed-compatible loader, either one works:
  - **LSPosed** (root) — enables the fast path
  - **LSPatch** (no root) — patch Riot Mobile's APK; no root needed

The module declares no permissions and no system scope. Its scope is the Riot package plus `io.blockman.totpull` itself — the second one is what lets the module report back that it is actually running (see *Status detection*).

## Two transport paths

| Path | Needs root | How |
|---|---|---|
| Fast path | yes | Module writes a snapshot to the Riot app's private dir; the app reads it through `su` |
| Fallback | no | Module broadcasts the seed when Riot generates a code; the app listens |

The fast path is a convenience only. The fallback alone is enough to import — this is why the module works under LSPatch.

Note that a `su` shell handed to an ordinary app can land in a mount namespace where `/data/data` is not reachable, in which case the fast path fails and the fallback takes over. Both outcomes are handled.

## Usage

1. Install the APK.
2. Enable the module in LSPosed (or patch Riot Mobile with LSPatch) and scope it to
   `com.riotgames.mobile.leagueconnect` **and** `io.blockman.totpull`.
3. Open Riot Mobile and let it show a verification code once.
4. Open TOTPull → **从应用导入** (import from app).

Existing entries can also be imported from Aegis, Bitwarden, FreeOTP, KeePassXC, or a Google Authenticator migration URI, via share sheet or the add button.

Storage: entries are written as a single AES/GCM blob (`vault.bin`) in the app's private directory. The key lives in the Android keystore, so the file is useless when copied off the device. Changing the package name changes the uid, and with it the key — a renamed build starts with an empty vault and the seed must be pulled again.

## Status detection

The info page reports what is actually available rather than guessing:

- **Root** — four-state probe (granted / denied / no response / undetected). A timeout is never cached as a denial.
- **LSPosed** — the module sets a flag inside this app's own process when it is injected; the UI reads that flag. This is a real handshake, not a guess from outside, and it needs no root.

## Build

```
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The debug keystore in `keystore/` is the standard public Android debug key (`android`/`android`). It exists so a fresh clone builds without setup. Never use it for a release build.

## Legal

This tool extracts a secret from an app you installed, on a device you own, for an account you control. Use it on your own accounts only.

Riot's terms of service may not permit modifying their client. That risk is yours to evaluate.
