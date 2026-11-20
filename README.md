# TOTPull

Pulls the TOTP seed out of an app's encrypted storage on Android, so you can keep generating your own two-factor codes after switching devices or uninstalling the client.

| App | Root 导入 | Root 导出 | Xposed 导入 | Xposed 导出 | 无特权 导入 | 无特权 导出 |
| --- | :-: | :-: | :-: | :-: | :-: | :-: |
| Riot Mobile<br>`com.riotgames.mobile.leagueconnect` | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Steam<br>`com.valvesoftware.android.steam.community` | ❌ | ❌ | ✅ | ❌ | ✅ maFile | ❌ |
| Google Authenticator<br>`com.google.android.apps.authenticator2` | ❌ | — | ✅ | — | ✅ QR | ✅ QR |
| Aegis<br>`com.beemdevelopment.aegis` | ✅ 密码 | — | ✅ 解锁 | — | ✅ JSON | ✅ |
| Microsoft Authenticator<br>`com.azure.authenticator` | ✅ | — | ✅ | — | ❌ | ❌ |
| Bitwarden<br>`com.x8bit.bitwarden` | — | — | — | — | ✅ | ⚠️ |
| Stratum<br>`com.stratumauth.app` | — | — | — | — | ✅ | ✅ steam |

`✅` 已完美适配 · `⚠️` 可用但需注意（Bitwarden 的 Steam 方言 deeplink 导入为错码，steamguard-cli#468） · `❌` 目标应用自身限制导致不可用（keystore 加密密文离进程无法解密 / 客户端不提供导入导出功能） · `—` 本软件尚未实现

## Requirements

- Android 7.0+ (API 24)
- Any of the source apps installed and signed in
- An Xposed-compatible loader:
  - **LSPosed** (root)
  - **LSPatch** (no root) — patch each source app you want to import from

Scope only the source packages. Do **not** add `io.blockman.totpull` itself: LSPosed rejects `module cannot target itself`, and module readiness is detected from a real answer instead.

## Why a hook is needed

Riot, Steam and Google Authenticator encrypt their secrets with keys in the Android keystore, bound to each app's uid. A root shell can copy the ciphertext files but can never decrypt them — the plaintext only exists inside the app's own process. That is the whole reason this project ships as an Xposed module.

Microsoft Authenticator is the exception: its OATH seed sits in `databases/PhoneFactor` as a plain Base64 blob, with no keystore wrapping, so no decryption is ever needed. It has two transports, offered as a choice in **从应用导入**:

- **Module read** — with the module in its LSPosed scope, the injected process opens its own database directly (`context.getDatabasePath`) and replies over the broadcast. No root, works under LSPatch too.
- **Root read** — TOTPull copies the database (plus WAL/SHM) with `su -M` into its own files dir and reads `accounts.oath_secret_key` through Android's SQLite. No Xposed scope needed.

Both decode the raw key from base64 then re-encode to base32 before import, and produce 8-digit SHA-1 codes.

Riot keeps its seed in `androidx.security` EncryptedSharedPreferences. Steam keeps its guard blob in `shared_prefs/SecureStore.xml` as `{ct, iv, tlen, keystoreAlias}`; the module unwraps it in-process using the app's own keystore entry, whose alias is prefixed (`AES/GCM/NoPadding:key_v1:…`). Steam's shared secret is base64, so it is converted to base32 before being saved as `otpauth://steam/…`.

Google Authenticator v7.2+ stores each signed-in account in `files/accounts/<id>/otp_database.db`; the older global `databases/databases` may be empty. The `secret` column is base64 over an AES/GCM blob (12-byte IV + ciphertext). Its decrypted plaintext is itself a base32 string, sometimes padded with `=` — it must be used directly, not base32-encoded a second time.

Aegis also offers two transports, and unlike Microsoft's both have prerequisites:

- **模块直读** — the module reads the unlocked vault straight out of the Aegis process. No password needed, but Aegis must be unlocked (vault decrypted in memory) at the moment of import, and the module must be in Aegis's LSPosed scope.
- **Root 读文件** — TOTPull reads the encrypted `aegis-vault.json` with `su -M` and decrypts it with the vault password you type in. No Xposed scope needed.

## Import & export

- **otpauth 链接** — the whole vault as one `otpauth://` URI per line, plain text. Anything that accepts pasted URIs (Aegis, Stratum, …) can import it.
- **Aegis JSON** — a plaintext Aegis vault file, importable by Aegis itself.
- **Deep link** — sends `otpauth-migration://offline?data=…` intents through the system app chooser straight into another validator's import flow. Google's migration protobuf carries at most ten entries per link, so the vault is batched and you pick a batch first. The field numbering follows Google's official schema: `1 secret, 2 name, 3 issuer, 4 algorithm, 5 digits(SIX=1/EIGHT=2), 6 type(HOTP=1/TOTP=2), 7 counter`. Be aware of the ecosystem reality: almost no validator registers a handler for the migration scheme — Google Authenticator and Aegis only read it as QR codes, and TOTPull itself is in the list as a round-trip target.
- **Google 验证器二维码** — the same migration payloads rendered as QR codes, ten entries per page, scanned by Google Authenticator's import screen.
- **发送到验证器** (single entry, three-bar menu) — one `otpauth://` deep link handed to the validator of your choice via the system chooser. Validators that never registered the deep-link schemes (notably Google Authenticator) will not appear; use the QR export for those.

### Steam entries and the deeplink mess

Steam's 5-character code type has no standard otpauth representation, and every
validator speaks a different dialect:

- `otpauth://steam/...` — what TOTPull exports; understood by Stratum (Authenticator Pro) and TOTPull itself. Bitwarden's parser ignores this host, so it never appears in the chooser.
- `otpauth://totp/Steam:...&issuer=Steam` — the common convention (Aegis exports this), but Bitwarden imports it **wrong**: the generated code comes out numeric instead of Steam's alphanumeric 5-character set (unfixed as of 2026-06, steamguard-cli#468). KeePassXC/gopass additionally want an `encoder=steam` parameter Bitwarden ignores.
- `steam://SECRET` — Bitwarden's only Steam format. The **Bitwarden Authenticator**
  app reads it (the password-manager app itself dropped the hand-typed field), so for
  Bitwarden the three-bar menu offers **复制 Bitwarden Authenticator 密钥**: it puts
  `steam://<secret>` on the clipboard for pasting/importing there. The deep-link item
  still works for Stratum-style validators.
- **扫码导入** (+ menu) — the in-app camera scanner reads single `otpauth://` QR codes and Google migration QR codes (ten-entry batches included), so you can pull entries in from any validator that exports them as images without file or clipboard round-trips.

## Storage

Entries are written as a single AES/GCM blob (`vault.bin`) in the app's private directory. The key lives in the Android keystore, so the file is useless when copied off the device. Changing the package name changes the uid and therefore the key — a renamed build starts with an empty vault.

If the vault ever becomes unreadable, writing is refused rather than overwriting it with an empty list. The contents cannot be recovered, but clearing this app's data from system settings removes the broken file and key and lets you import again. Riot, Steam and Google Authenticator still hold the originals.

On startup the vault is normalised once: secrets that an older build double-encoded are rewritten to canonical base32 so exports and de-duplication use the same value as code generation.

## Build

```
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The debug keystore in `keystore/` is the standard public Android debug key (`android`/`android`). It exists so a fresh clone builds without setup. Never use it for a release build.

## Legal

This tool extracts a secret from an app you installed, on a device you own, for an account you control. Use it on your own accounts only.

Riot's and Valve's terms of service may not permit modifying their clients. That risk is yours to evaluate.
