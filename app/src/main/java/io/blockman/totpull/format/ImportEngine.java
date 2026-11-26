package io.blockman.totpull.format;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;

/**
 * Single entry point for turning arbitrary import text into entries.
 *
 * <p>Design rule: every parser must be lossless. A field that the source format carries
 * (algorithm, digits, period, counter, group, note, type) is copied through, never
 * flattened to a default. Anything that cannot be understood becomes a warning instead
 * of silently disappearing.
 *
 * <p>Supported payloads, detected automatically:
 * <ul>
 *   <li>{@code otpauth-migration://} - Google Authenticator export (protobuf)</li>
 *   <li>one or more {@code otpauth://} URIs, anywhere in the text (line-split, embedded
 *       in JSON/XML/HTML, or pasted prose)</li>
 *   <li>Aegis plain JSON export</li>
 *   <li>Bitwarden JSON export ({@code items[].login.totp})</li>
 *   <li>Bitwarden CSV export ({@code login_totp} column)</li>
 *   <li>andOTP plain JSON export</li>
 *   <li>2FAS JSON export ({@code services[]})</li>
 *   <li>FreeOTP / FreeOTP+ JSON export ({@code tokens[]})</li>
 *   <li>Steam desktop-authenticator {@code .maFile} (base64 {@code shared_secret})</li>
 *   <li>any CSV / TSV carrying an OTP column (KeePassXC, 1Password, generic)</li>
 *   <li>a bare base32 / hex / base64 secret</li>
 * </ul>
 */
public final class ImportEngine {

    public static class Result {
        public final List<TotpEntry> entries = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String detectedFormat = "";
        /** Google migration paging: distinct pages collected / total pages (0 when not one). */
        public int migrationPages = 0;
        public int migrationSize = 0;

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private ImportEngine() {
    }

    public static Result importText(String text) {
        Result result = new Result();
        if (text == null) {
            return result;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return result;
        }
        // Strip a UTF-8 BOM, which Windows/Notepad exports love to add.
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1).trim();
        }

        try {
            if (MigrationImporter.looksLike(t)) {
                result.detectedFormat = "Google Authenticator（迁移链接）";
                // The text may carry several migration pages (ScanActivity batches them);
                // merge every line, keeping the largest size and the distinct page count.
                java.util.TreeSet<Integer> seenPages = new java.util.TreeSet<>();
                for (String line : t.split("\n")) {
                    String uri = line.trim();
                    if (!MigrationImporter.looksLike(uri)) {
                        continue;
                    }
                    try {
                        MigrationImporter.Batch batch = MigrationImporter.parseBatch(uri);
                        result.migrationSize = Math.max(result.migrationSize,
                                Math.max(1, batch.size));
                        seenPages.add(Math.min(Math.max(batch.index + 1, 1),
                                Math.max(1, batch.size)));
                        result.entries.addAll(batch.entries);
                    } catch (Exception e) {
                        result.warnings.add("迁移链接解析失败");
                    }
                }
                result.migrationPages = seenPages.size();
                if (!result.entries.isEmpty()) {
                    return result;
                }
            }

            if (t.startsWith("{") || t.startsWith("[")) {
                parseJson(t, result);
                if (!result.entries.isEmpty()) {
                    return result;
                }
            }

            if (t.contains("otpauth://") || t.contains("otpauth-migration://")) {
                parseUriAnywhere(t, result);
                if (!result.entries.isEmpty()) {
                    return result;
                }
            }

            if (looksLikeCsv(t)) {
                parseCsv(t, result);
                if (!result.entries.isEmpty()) {
                    return result;
                }
            }

            // A bare secret is a single token: base32/hex/base64 with at most spaces,
            // dashes and padding around it. Anything carrying JSON/URI punctuation is a
            // format we failed to parse — without this guard the base64 fallback below
            // would strip that punctuation off and "decode" the remainder into a
            // plausible-looking fake secret, so every structured file imported as one.
            if (t.matches("[A-Za-z0-9+=\\-\\s]+")) {
                String secret = TotpGenerator.normalizeAnySecret(t);
                if (TotpGenerator.isValidBase32(secret)) {
                    result.detectedFormat = "裸 secret";
                    TotpEntry entry = new TotpEntry();
                    entry.secret = secret;
                    entry.issuer = "未命名";
                    entry.name = "";
                    result.entries.add(entry);
                }
            }
        } catch (Exception e) {
            result.warnings.add(String.valueOf(e.getMessage()));
        }
        // Repeated secrets are intentionally preserved. Consolidating duplicates is an
        // explicit maintenance operation, not something parsing decides on the user's behalf.
        return result;
    }

    // ---------------------------------------------------------------- URIs

    /**
     * Pulls every {@code otpauth://} occurrence out of free-form text.
     *
     * <p>Deliberately not line-based: real exports embed URIs inside JSON strings, XML
     * attributes and SQL dumps, and those payloads are frequently a single enormous line.
     */
    private static void parseUriAnywhere(String text, Result result) {
        int cursor = 0;
        boolean found = false;
        while (true) {
            int start = text.indexOf("otpauth", cursor);
            if (start < 0) {
                break;
            }
            int end = start;
            while (end < text.length() && !isUriTerminator(text.charAt(end))) {
                end++;
            }
            String candidate = text.substring(start, end);
            cursor = Math.max(end, start + 1);

            if (candidate.startsWith("otpauth-migration://")) {
                found = true;
                try {
                    MigrationImporter.Batch batch = MigrationImporter.parseBatch(candidate);
                    result.migrationSize = Math.max(result.migrationSize,
                            Math.max(1, batch.size));
                    result.migrationPages++;
                    result.entries.addAll(batch.entries);
                } catch (Exception e) {
                    result.warnings.add("迁移链接解析失败");
                }
                continue;
            }
            if (!candidate.startsWith("otpauth://")) {
                continue;
            }
            found = true;
            TotpEntry entry = TotpEntry.fromOtpauth(candidate);
            if (entry == null) {
                result.warnings.add("跳过一条无法解析的 otpauth URI");
            } else if (!entry.isSupported()) {
                result.warnings.add("跳过一条不支持的类型（mOTP / Yandex）");
            } else {
                result.entries.add(entry);
            }
        }
        if (found && result.detectedFormat.isEmpty()) {
            result.detectedFormat = "otpauth URI";
        }
    }

    private static boolean isUriTerminator(char c) {
        switch (c) {
            case ' ':
            case '\t':
            case '\r':
            case '\n':
            case '"':
            case '\'':
            case '<':
            case '>':
            case ',':
            case ';':
            case '\\':
            case ')':
            case ']':
            case '}':
            case '|':
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- JSON

    private static void parseJson(String text, Result result) {
        try {
            if (text.startsWith("{")) {
                JSONObject root = new JSONObject(text);
                if (root.has("db") || root.has("header")) {
                    result.detectedFormat = "Aegis JSON";
                    parseAegis(root, result);
                    return;
                }
                if (root.has("items")) {
                    result.detectedFormat = "Bitwarden JSON";
                    parseBitwardenJson(root, result);
                    return;
                }
                if (root.has("services")) {
                    result.detectedFormat = "2FAS JSON";
                    parseTwoFas(root, result);
                    return;
                }
                if (root.has("tokens") || root.has("token")) {
                    result.detectedFormat = "FreeOTP JSON";
                    JSONArray tokens = root.optJSONArray("tokens");
                    if (tokens == null) {
                        tokens = root.optJSONArray("token");
                    }
                    parseFreeOtp(tokens, result);
                    return;
                }
                if (root.has("shared_secret")) {
                    result.detectedFormat = "Steam maFile";
                    parseSteamMaFile(root, result);
                    return;
                }
                if (root.has("entries")) {
                    JSONArray entries = root.optJSONArray("entries");
                    JSONObject first = entries == null ? null : entries.optJSONObject(0);
                    if (first != null && first.has("info")) {
                        // Plaintext Aegis export: the secret hides in entry.info instead of
                        // the top level, so the generic parser would silently drop every row
                        // and the whole file would end up at the bare-secret fallback.
                        result.detectedFormat = "Aegis JSON";
                        parseAegis(root, result);
                    } else {
                        result.detectedFormat = "通用 JSON";
                        parseGenericEntries(entries, result);
                    }
                }
                return;
            }

            JSONArray array = new JSONArray(text);
            if (array.length() == 0) {
                return;
            }
            JSONObject first = array.optJSONObject(0);
            if (first == null) {
                return;
            }
            if (first.has("label") || (first.has("secret") && first.has("type"))) {
                result.detectedFormat = "andOTP JSON";
                parseAndOtp(array, result);
            } else {
                result.detectedFormat = "通用 JSON 数组";
                parseGenericEntries(array, result);
            }
        } catch (Exception e) {
            result.warnings.add("JSON 解析失败");
        }
    }

    private static void parseAegis(JSONObject root, Result result) {
        JSONObject db = root.optJSONObject("db");
        if (db == null && root.has("entries") && root.has("version")) {
            // Plaintext export: the vault content sits directly on the root object rather
            // than under "db" like the wrapper we build after decrypting an encrypted vault.
            db = root;
        }
        if (db == null) {
            return;
        }

        Map<String, String> groupNames = new HashMap<>();
        JSONArray groups = db.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null) {
                    groupNames.put(g.optString("uuid"), g.optString("name"));
                }
            }
        }

        JSONArray entries = db.optJSONArray("entries");
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject o = entries.optJSONObject(i);
            if (o == null) {
                continue;
            }
            JSONObject info = o.optJSONObject("info");
            if (info == null) {
                continue;
            }
            String secret = TotpGenerator.normalizeAnySecret(info.optString("secret", ""));
            if (!TotpGenerator.isValidBase32(secret)) {
                result.warnings.add("跳过一条 secret 非法的条目");
                continue;
            }

            TotpEntry entry = new TotpEntry();
            entry.secret = secret;
            entry.name = o.optString("name", "");
            entry.issuer = o.optString("issuer", "");
            entry.note = o.optString("note", "");
            entry.algorithm = info.optString("algo", info.optString("algorithm", "SHA1"));
            entry.digits = info.optInt("digits", 6);
            entry.period = info.optInt("period", 30);
            entry.counter = info.optLong("counter", 0);

            String type = o.optString("type", "totp").toLowerCase(Locale.ROOT);
            if (type.contains("hotp")) {
                entry.type = TotpEntry.TYPE_HOTP;
            } else if (type.contains("steam")) {
                entry.type = TotpEntry.TYPE_STEAM;
            } else if (type.contains("yandex")) {
                entry.type = TotpEntry.TYPE_YANDEX;
            } else if (type.contains("motp")) {
                entry.type = TotpEntry.TYPE_MOTP;
            } else {
                entry.type = TotpEntry.TYPE_TOTP;
            }
            // mOTP / Yandex are disabled app-wide: importing them would create invisible
            // entries, so parsed rows of those types are dropped with a warning.
            if (!entry.isSupported()) {
                result.warnings.add("跳过一条不支持的类型（mOTP / Yandex）");
                continue;
            }

            JSONArray groupIds = o.optJSONArray("groups");
            if (groupIds != null && groupIds.length() > 0) {
                String name = groupNames.get(groupIds.optString(0));
                entry.group = name == null ? "" : name;
            }

            entry.sourceLabel = "Aegis";
            entry.sourcePackage = "com.beemdevelopment.aegis";

            if (entry.issuer.isEmpty()) {
                entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
            }
            result.entries.add(entry);
        }
    }

    private static void parseBitwardenJson(JSONObject root, Result result) {
        JSONArray items = root.optJSONArray("items");
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject login = item.optJSONObject("login");
            if (login == null) {
                continue;
            }
            String totp = login.optString("totp", "");
            if (totp.isEmpty()) {
                continue;
            }

            TotpEntry entry;
            if (totp.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
                entry = TotpEntry.fromOtpauth(totp);
            } else {
                String secret = TotpGenerator.normalizeAnySecret(totp);
                if (!TotpGenerator.isValidBase32(secret)) {
                    continue;
                }
                entry = new TotpEntry();
                entry.secret = secret;
                entry.name = login.optString("username", "");
                entry.issuer = item.optString("name", "");
            }
            if (entry == null) {
                continue;
            }
            if (entry.name.isEmpty()) {
                entry.name = login.optString("username", "");
            }
            if (entry.issuer.isEmpty() || "未命名".equals(entry.issuer)) {
                entry.issuer = item.optString("name", entry.issuer);
            }
            entry.note = item.optString("notes", "");
            entry.sourceLabel = "Bitwarden";
            entry.sourcePackage = "com.x8bit.bitwarden";
            result.entries.add(entry);
        }
    }

    private static void parseAndOtp(JSONArray array, Result result) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String secret = TotpGenerator.normalizeAnySecret(o.optString("secret", ""));
            if (!TotpGenerator.isValidBase32(secret)) {
                continue;
            }
            TotpEntry entry = new TotpEntry();
            entry.secret = secret;
            entry.name = o.optString("label", "");
            entry.issuer = o.optString("issuer", "");
            entry.algorithm = o.optString("algorithm", "SHA1");
            entry.digits = o.optInt("digits", 6);
            entry.period = o.optInt("period", 30);
            entry.counter = o.optLong("counter", 0);

            String type = o.optString("type", "TOTP").toUpperCase(Locale.ROOT);
            entry.type = type.contains("HOTP") ? TotpEntry.TYPE_HOTP : TotpEntry.TYPE_TOTP;
            if (!entry.isSupported()) {
                continue;
            }

            JSONArray tags = o.optJSONArray("tags");
            if (tags != null && tags.length() > 0) {
                entry.group = tags.optString(0);
            }

            entry.sourceLabel = "andOTP";
            entry.sourcePackage = "org.shadowice.flocke.andotp";

            if (entry.issuer.isEmpty()) {
                entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
            }
            result.entries.add(entry);
        }
    }

    /** 2FAS: {@code {"services":[{"name":..,"secret":..,"otp":{...}}],"schemaVersion":4}} */
    private static void parseTwoFas(JSONObject root, Result result) {
        JSONArray services = root.optJSONArray("services");
        if (services == null) {
            return;
        }
        for (int i = 0; i < services.length(); i++) {
            JSONObject o = services.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String secret = TotpGenerator.normalizeAnySecret(o.optString("secret", ""));
            if (!TotpGenerator.isValidBase32(secret)) {
                result.warnings.add("跳过一条 2FAS secret 非法的条目");
                continue;
            }
            TotpEntry entry = new TotpEntry();
            entry.secret = secret;

            JSONObject otp = o.optJSONObject("otp");
            String issuer = o.optString("name", "");
            String account = "";
            if (otp != null) {
                if (otp.optString("issuer", "").length() > 0) {
                    issuer = otp.optString("issuer");
                }
                account = otp.optString("account", otp.optString("label", ""));
                entry.algorithm = otp.optString("algorithm", "SHA1");
                entry.digits = otp.optInt("digits", 6);
                entry.period = otp.optInt("period", 30);
                entry.counter = otp.optLong("counter", 0);
                String tokenType = otp.optString("tokenType", "TOTP").toUpperCase(Locale.ROOT);
                if (tokenType.contains("HOTP")) {
                    entry.type = TotpEntry.TYPE_HOTP;
                } else if (tokenType.contains("STEAM")) {
                    entry.type = TotpEntry.TYPE_STEAM;
                }
            }
            entry.issuer = issuer;
            entry.name = account;
            if (!entry.isSupported()) {
                continue;
            }

            JSONArray groups = o.optJSONArray("group");
            if (groups != null && groups.length() > 0) {
                entry.group = groups.optString(0);
            }

            entry.sourceLabel = "2FAS";
            entry.sourcePackage = "com.twofasapp";

            if (entry.issuer.isEmpty()) {
                entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
            }
            result.entries.add(entry);
        }
    }

    /** FreeOTP / FreeOTP+ / Aegis legacy: {@code {"tokens":[{"issuer","label","secret",...}]}} */
    private static void parseFreeOtp(JSONArray tokens, Result result) {
        if (tokens == null) {
            return;
        }
        for (int i = 0; i < tokens.length(); i++) {
            JSONObject o = tokens.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String secret = TotpGenerator.normalizeAnySecret(o.optString("secret", ""));
            if (!TotpGenerator.isValidBase32(secret)) {
                result.warnings.add("跳过一条 FreeOTP secret 非法的条目");
                continue;
            }
            TotpEntry entry = new TotpEntry();
            entry.secret = secret;
            entry.issuer = o.optString("issuer", "");
            entry.name = o.optString("label", "");
            entry.algorithm = o.optString("algo", o.optString("algorithm", "SHA1"));
            entry.digits = o.optInt("digits", 6);
            entry.period = o.optInt("period", 30);
            entry.counter = o.optLong("counter", 0);

            String type = o.optString("type", "TOTP").toUpperCase(Locale.ROOT);
            if (type.contains("HOTP")) {
                entry.type = TotpEntry.TYPE_HOTP;
            } else if (type.contains("STEAM")) {
                entry.type = TotpEntry.TYPE_STEAM;
            }
            if (!entry.isSupported()) {
                continue;
            }

            entry.sourceLabel = "FreeOTP";
            entry.sourcePackage = "org.fedorahosted.freeotp";

            if (entry.issuer.isEmpty()) {
                entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
            }
            result.entries.add(entry);
        }
    }

    /**
     * Steam desktop authenticator {@code .maFile}. The shared secret is base64 over the
     * same bytes a normal TOTP would use, so only the presentation differs (5 chars,
     * Steam's own alphabet).
     */
    private static void parseSteamMaFile(JSONObject root, Result result) {
        byte[] bytes = TotpGenerator.base64Decode(root.optString("shared_secret", ""));
        if (bytes.length < 10) {
            result.warnings.add("maFile 的 shared_secret 无法解码");
            return;
        }
        TotpEntry entry = new TotpEntry();
        entry.secret = TotpGenerator.base32Encode(bytes);
        entry.type = TotpEntry.TYPE_STEAM;
        entry.digits = 5;
        entry.period = 30;
        entry.algorithm = "SHA1";
        entry.issuer = "Steam";
        entry.name = root.optString("account_name", "");
        entry.sourceLabel = "Steam";
        entry.sourcePackage = "com.valvesoftware.android.steam.community";
        result.entries.add(entry);
    }

    private static void parseGenericEntries(JSONArray entries, Result result) {
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject o = entries.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String secret = TotpGenerator.normalizeAnySecret(
                    o.optString("secret", o.optString("totp", "")));
            if (!TotpGenerator.isValidBase32(secret)) {
                continue;
            }
            TotpEntry entry = new TotpEntry();
            entry.secret = secret;
            entry.name = o.optString("name", o.optString("account", ""));
            entry.issuer = o.optString("issuer", "");
            entry.note = o.optString("note", o.optString("notes", ""));
            entry.group = o.optString("group", "");
            if (entry.issuer.isEmpty()) {
                entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
            }
            result.entries.add(entry);
        }
    }

    // ---------------------------------------------------------------- CSV

    /**
     * Any delimited table whose header mentions OTP. Covers Bitwarden CSV, KeePassXC
     * (a {@code TOTP} column), 1Password and hand-made spreadsheets.
     */
    private static boolean looksLikeCsv(String text) {
        String[] lines = text.split("\\r?\\n", 3);
        if (lines.length < 2) {
            return false;
        }
        String header = lines[0].toLowerCase(Locale.ROOT);
        char delimiter = detectDelimiter(header);
        if (delimiter == 0) {
            return false;
        }
        for (String h : splitCsv(lines[0], delimiter)) {
            String column = h.trim().toLowerCase(Locale.ROOT);
            if (column.contains("totp") || column.contains("otp") || column.contains("otpauth")) {
                return true;
            }
        }
        return false;
    }

    private static char detectDelimiter(String header) {
        if (header.indexOf(',') >= 0) {
            return ',';
        }
        if (header.indexOf('\t') >= 0) {
            return '\t';
        }
        if (header.indexOf(';') >= 0) {
            return ';';
        }
        return 0;
    }

    private static void parseCsv(String text, Result result) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) {
            return;
        }
        char delimiter = detectDelimiter(lines[0].toLowerCase(Locale.ROOT));
        if (delimiter == 0) {
            return;
        }

        List<String> header = new ArrayList<>();
        for (String h : splitCsv(lines[0], delimiter)) {
            header.add(h.trim().toLowerCase(Locale.ROOT));
        }

        int otpIndex = -1;
        for (int i = 0; i < header.size(); i++) {
            String column = header.get(i);
            if (column.contains("totp") || column.contains("otpauth")
                    || column.equals("otp") || column.contains("otp_")) {
                otpIndex = i;
                break;
            }
        }
        if (otpIndex < 0) {
            return;
        }

        int issuerIndex = firstIndexOf(header, "issuer", "name", "service", "title", "entry", "url");
        int userIndex = firstIndexOf(header, "login_username", "username", "account", "user", "login");
        int notesIndex = firstIndexOf(header, "notes", "note", "comment");
        int groupIndex = firstIndexOf(header, "folder", "group", "category");

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            List<String> row = splitCsv(lines[i], delimiter);
            String totp = value(row, otpIndex);
            if (totp.isEmpty()) {
                continue;
            }

            TotpEntry entry;
            if (totp.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
                entry = TotpEntry.fromOtpauth(totp);
            } else {
                String secret = TotpGenerator.normalizeAnySecret(totp);
                if (!TotpGenerator.isValidBase32(secret)) {
                    continue;
                }
                entry = new TotpEntry();
                entry.secret = secret;
                entry.issuer = value(row, issuerIndex);
                entry.name = value(row, userIndex);
            }
            if (entry == null) {
                continue;
            }
            if (entry.issuer.isEmpty() || "未命名".equals(entry.issuer)) {
                String fallback = value(row, issuerIndex);
                if (!fallback.isEmpty()) {
                    entry.issuer = fallback;
                }
            }
            if (entry.name.isEmpty()) {
                entry.name = value(row, userIndex);
            }
            if (entry.note.isEmpty()) {
                entry.note = value(row, notesIndex);
            }
            if (entry.group.isEmpty()) {
                entry.group = value(row, groupIndex);
            }
            entry.sourceLabel = "CSV";
            entry.sourcePackage = "";
            result.entries.add(entry);
        }
    }

    private static int firstIndexOf(List<String> header, String... candidates) {
        for (String candidate : candidates) {
            int i = header.indexOf(candidate);
            if (i >= 0) {
                return i;
            }
        }
        // Second pass: substring match, so "login_totp_extra" style columns still work.
        for (String candidate : candidates) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).contains(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String value(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }

    /** Handles quoted fields and doubled quotes, which Bitwarden's exporter emits. */
    private static List<String> splitCsv(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }
}
