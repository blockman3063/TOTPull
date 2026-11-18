# TOTPull

Pulls the TOTP seed out of an app's encrypted storage on Android, so you can keep generating your own two-factor codes after switching devices or uninstalling the client.

| Source | Package | Code format |
| --- | --- | --- |
| Riot Mobile | `com.riotgames.mobile.leagueconnect` | TOTP · SHA-256 · 6 digits · 30 s |
| Steam | `com.valvesoftware.android.steam.community` | Steam Guard · 5 characters · 30 s |
| Google Authenticator | `com.google.android.apps.authenticator2` | TOTP / HOTP · per-entry algorithm and digits |

Google Authenticator can return dozens of accounts in one answer; the whole batch is folded into the vault with a single write.

## Why a hook is needed

All three apps encrypt their secrets with keys in the Android keystore, bound to each app's uid. A root shell can copy the ciphertext files but can never decrypt them — the plaintext only exists inside the app's own process. That is the whole reason this project ships as an Xposed module.

Riot keeps its seed in `androidx.security` EncryptedSharedPreferences. Steam keeps its guard blob in `shared_prefs/SecureStore.xml` as `{ct, iv, tlen, keystoreAlias}`; the module unwraps it in-process using the app's own keystore entry, whose alias is prefixed (`AES/GCM/NoPadding:key_v1:…`). Steam's shared secret is base64, so it is converted to base32 before being saved as `otpauth://steam/…`.

Google Authenticator v7.2+ stores each signed-in account in `files/accounts/<id>/otp_database.db`; the older global `databases/databases` may be empty. The `secret` column is base64 over an AES/GCM blob (12-byte IV + ciphertext). Its decrypted plaintext is itself a base32 string, sometimes padded with `=` — it must be used directly, not base32-encoded a second time.

## Requirements

- Android 7.0+ (API 24)
- Any of the source apps installed and signed in
- An Xposed-compatible loader:
  - **LSPosed** (root)
  - **LSPatch** (no root) — patch each source app you want to import from

Scope only the source packages. Do **not** add `io.blockman.totpull` itself: LSPosed rejects `module cannot target itself`, and module readiness is detected from a real answer instead.

## Import flow

Tap **从应用导入** (import from app) and pick a source. TOTPull launches it and steps into the background. When the module is ready it announces itself; TOTPull's manifest-declared receiver wakes the frozen process, asks for the seed, saves the entry, and the module brings TOTPull back with a report dialog. If that activity start is blocked, a notification provides the way back.

| Path | Needs root | How |
| --- | --- | --- |
| Snapshot | yes | Module writes a sealed snapshot into the source app's private dir; TOTPull reads it through `su` |
| Broadcast | no | Readiness announcement → probe → answer, all over package-targeted broadcasts |

The broadcast path is complete on its own; root only avoids a round trip. A `su` shell handed to an ordinary app can land in a mount namespace where `/data/data` is unreachable, in which case the snapshot read fails and the broadcast path takes over.

## Browsing codes

Entries are grouped by source app. Tap a group header to fold or unfold it (the chevron points down when open, right when folded). Long-press opens the matching editor directly — no intermediate menu:

- **Entry** — opens the entry editor (rename, secret, parameters; delete lives there too).
- **Group** — opens the group editor: rename the group label, read its source app / package / entry count / type breakdown, and reach export, re-import from source (only for sources TOTPull knows how to extract from) and delete-group.

The search box matches issuer, name, group, notes and source. The filter dialog offers source and type pickers built from the entries actually in the vault; search and both dimensions AND together. Filtering happens in the list adapter only — the vault is never touched. The subtitle switches to `显示 X / Y 条` while a filter is active.

Tap a code to copy it; long-press an entry to edit or delete it.

## Other imports

Aegis, Bitwarden, FreeOTP, KeePassXC, and Google Authenticator migration URIs are accepted through the share sheet or the add button.

**Aegis supports both export styles.** A plaintext JSON export goes straight through `ImportEngine`. An encrypted backup (vault version 1) is detected by `db` being a base64 string; TOTPull prompts for the backup password, derives the wrapping key with an in-tree RFC 7914 scrypt implementation (no external BouncyCastle dependency — Android's boot classpath ships a trimmed one), unwraps the master key, decrypts the database, and feeds the result through the same plaintext parser. The scrypt code is verified against the RFC test vectors and Python's `hashlib.scrypt`.

Only password slots can be opened. Backups protected solely by Aegis' biometric slot are bound to Aegis' own AndroidKeyStore entry and cannot be decrypted elsewhere — re-export with a password in that case. scrypt runs on a background executor; the password `char[]` is zeroed after use and never persisted.

## Storage

Entries are written as a single AES/GCM blob (`vault.bin`) in the app's private directory. The key lives in the Android keystore, so the file is useless when copied off the device. Changing the package name changes the uid and therefore the key — a renamed build starts with an empty vault.

If the vault ever becomes unreadable, writing is refused rather than overwriting it with an empty list. The contents cannot be recovered, but clearing this app's data from system settings removes the broken file and key and lets you import again. Riot, Steam and Google Authenticator still hold the originals.

On startup the vault is normalised once: secrets that an older build double-encoded are rewritten to canonical base32 so exports and de-duplication use the same value as code generation.

## Status detection

- **Root** — four-state probe (granted / denied / no response / undetected). A timeout is never cached as a denial.
- **Module** — marked ready only after a source app actually answered a probe, so the indicator reflects a working hook rather than a framework guess.

## Build

```
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The debug keystore in `keystore/` is the standard public Android debug key (`android`/`android`). It exists so a fresh clone builds without setup. Never use it for a release build.

## Legal

This tool extracts a secret from an app you installed, on a device you own, for an account you control. Use it on your own accounts only.

Riot's and Valve's terms of service may not permit modifying their clients. That risk is yours to evaluate.
