package io.blockman.totpull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.UUID;

/**
 * One OTP account. The field set mirrors what Aegis exposes in its editor, so entries can move
 * between the two apps without losing information.
 *
 * <p>{@code sourcePackage} is the package name of the app this entry was extracted from. The list
 * groups on it, and it is the hook for "sync from this app again" later. Hand-added entries have
 * an empty sourcePackage.
 */
public class TotpEntry {
    public static final String TYPE_TOTP = "TOTP";
    public static final String TYPE_HOTP = "HOTP";
    public static final String TYPE_STEAM = "STEAM";
    public static final String TYPE_YANDEX = "YANDEX";
    public static final String TYPE_MOTP = "MOTP";

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String issuer = "";
    public String group = "";
    public String note = "";
    public String secret = "";
    public String type = TYPE_TOTP;
    public String algorithm = "SHA1";
    public int digits = 6;
    public int period = 30;
    public long counter = 0;
    public String pin = "";
    public int usageCount = 0;
    public long lastUsedAt = 0;
    public String sourcePackage = "";
    public String sourceLabel = "";

    public TotpEntry() {
    }

    public boolean isSteam() {
        return TYPE_STEAM.equals(type);
    }

    public boolean isHotp() {
        return TYPE_HOTP.equals(type);
    }

    /** Whether a code can actually be produced for this entry today. */
    public boolean isSupported() {
        return !(TYPE_YANDEX.equals(type) || TYPE_MOTP.equals(type));
    }

    public String getAlgorithmForMac() {
        String a = algorithm == null ? "SHA1" : algorithm.replace("-", "");
        if (a.equals("SHA1") || a.equals("SHA256") || a.equals("SHA512")) {
            return a;
        }
        return "SHA1";
    }

    /** De-duplication key: same issuer + name + secret means the same token. */
    public String key() {
        String n = name == null ? "" : name;
        return (issuer == null ? "" : issuer) + "|" + n + "|"
                + TotpGenerator.canonicalSecret(secret);
    }

    public String displayName() {
        if (issuer != null && issuer.length() > 0) {
            return issuer;
        }
        return name == null || name.isEmpty() ? "未命名" : name;
    }

    public String displayAccount() {
        if (name != null && name.length() > 0) {
            return name;
        }
        return issuer == null ? "" : issuer;
    }

    /** App label when known, package name otherwise. */
    public String displaySource() {
        if (sourceLabel != null && sourceLabel.length() > 0) {
            return sourceLabel;
        }
        if (sourcePackage != null && sourcePackage.length() > 0) {
            return sourcePackage;
        }
        return "";
    }

    public String toOtpauthUri() {
        String label = (issuer == null ? "" : issuer) + ":" + (name == null ? "" : name);
        StringBuilder sb = new StringBuilder("otpauth://");
        if (isSteam()) {
            sb.append("steam/");
        } else if (isHotp()) {
            sb.append("hotp/");
        } else {
            sb.append("totp/");
        }
        sb.append(encode(label));
        // Export a secret any otpauth consumer can use. Old double-encoded GA secrets must not
        // leak out here - they would generate wrong codes everywhere except inside TOTPull.
        sb.append("?secret=").append(TotpGenerator.canonicalSecret(secret));
        if (issuer != null && issuer.length() > 0) {
            sb.append("&issuer=").append(encode(issuer));
        }
        if (isHotp()) {
            sb.append("&counter=").append(counter);
        } else {
            sb.append("&algorithm=").append(getAlgorithmForMac());
            sb.append("&digits=").append(digits);
            sb.append("&period=").append(period);
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            // URLEncoder uses '+' for spaces; otpauth consumers treat that as a literal plus.
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name == null ? "" : name);
        o.put("issuer", issuer == null ? "" : issuer);
        o.put("group", group == null ? "" : group);
        o.put("note", note == null ? "" : note);
        o.put("secret", secret);
        o.put("type", type);
        o.put("algorithm", algorithm);
        o.put("digits", digits);
        o.put("period", period);
        o.put("counter", counter);
        o.put("pin", pin == null ? "" : pin);
        o.put("usageCount", usageCount);
        o.put("lastUsedAt", lastUsedAt);
        o.put("sourcePackage", sourcePackage == null ? "" : sourcePackage);
        o.put("sourceLabel", sourceLabel == null ? "" : sourceLabel);
        return o;
    }

    public static TotpEntry fromJson(JSONObject o) {
        TotpEntry e = new TotpEntry();
        e.id = o.optString("id", UUID.randomUUID().toString());
        // "account" is the field name used by the first version of the vault file.
        e.name = o.has("name") ? o.optString("name", "") : o.optString("account", "");
        e.issuer = o.optString("issuer", "");
        e.group = o.optString("group", "");
        e.note = o.optString("note", "");
        e.secret = o.optString("secret", "");
        e.type = o.optString("type", TYPE_TOTP);
        e.algorithm = o.optString("algorithm", "SHA1");
        e.digits = o.optInt("digits", 6);
        e.period = o.optInt("period", 30);
        e.counter = o.optLong("counter", 0);
        e.pin = o.optString("pin", "");
        e.usageCount = o.optInt("usageCount", 0);
        e.lastUsedAt = o.optLong("lastUsedAt", 0);
        e.sourcePackage = o.optString("sourcePackage", "");
        e.sourceLabel = o.optString("sourceLabel", "");
        return e;
    }

    /** Parses otpauth:// URIs. Returns null when the URI is not one we can use. */
    public static TotpEntry fromOtpauth(String uriString) {
        if (uriString == null) {
            return null;
        }
        String s = uriString.trim();
        if (!s.startsWith("otpauth://")) {
            return null;
        }

        android.net.Uri uri;
        try {
            uri = android.net.Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
        if (uri == null) {
            return null;
        }

        String host = uri.getHost();
        String entryType;
        if ("totp".equals(host)) {
            entryType = TYPE_TOTP;
        } else if ("hotp".equals(host)) {
            entryType = TYPE_HOTP;
        } else if ("steam".equals(host)) {
            entryType = TYPE_STEAM;
        } else {
            return null;
        }

        String secret = uri.getQueryParameter("secret");
        if (secret == null) {
            return null;
        }
        secret = TotpGenerator.normalizeSecret(secret);
        if (secret.isEmpty() || !TotpGenerator.isValidBase32(secret)) {
            return null;
        }

        String label = uri.getPath();
        if (label != null && label.startsWith("/")) {
            label = label.substring(1);
        }
        String issuer = "";
        String name = label == null ? "" : label;
        if (name.contains(":")) {
            String[] parts = name.split(":", 2);
            issuer = parts[0];
            name = parts.length > 1 ? parts[1] : "";
        }

        String issuerParam = uri.getQueryParameter("issuer");
        if (issuerParam != null && issuerParam.length() > 0 && issuer.isEmpty()) {
            issuer = issuerParam;
        }

        String algorithm = uri.getQueryParameter("algorithm");
        int digits = TYPE_STEAM.equals(entryType) ? 5 : 6;
        int period = 30;
        long counter = 0;
        try {
            String d = uri.getQueryParameter("digits");
            if (d != null) {
                digits = Integer.parseInt(d);
            }
            String p = uri.getQueryParameter("period");
            if (p != null) {
                period = Integer.parseInt(p);
            }
            String c = uri.getQueryParameter("counter");
            if (c != null) {
                counter = Long.parseLong(c);
            }
        } catch (NumberFormatException ignored) {
        }

        if (digits < 5 || digits > 8) {
            digits = TYPE_STEAM.equals(entryType) ? 5 : 6;
        }
        if (period < 10 || period > 300) {
            period = 30;
        }
        if (TYPE_STEAM.equals(entryType)) {
            algorithm = "SHA1";
            digits = 5;
            period = 30;
        }

        TotpEntry e = new TotpEntry();
        e.name = name;
        e.issuer = issuer;
        e.secret = secret;
        e.type = entryType;
        e.algorithm = algorithm == null ? "SHA1" : algorithm.toUpperCase(Locale.ROOT);
        e.digits = digits;
        e.period = period;
        e.counter = counter;
        if (e.issuer.isEmpty()) {
            e.issuer = e.name.isEmpty() ? "未命名" : e.name;
        }
        return e;
    }
}
