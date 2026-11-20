package io.blockman.totpull.format;

import android.net.Uri;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;

import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;

/**
 * Parses Google Authenticator's export format: {@code otpauth-migration://offline?data=<base64>}.
 *
 * <p>Layout (protobuf, per Google's official migration.proto):
 * <pre>
 * MigrationPayload { repeated OtpParameters otp_parameters = 1; ... }
 * OtpParameters { bytes secret = 1; string name = 2; string issuer = 3;
 *                 Algorithm algorithm = 4; DigitCount digits = 5; OtpType type = 6;
 *                 int64 counter = 7; }
 * </pre>
 * The secret here is raw bytes, not base32 - that is the usual source of "invalid secret" bugs
 * when people hand-roll this parser.
 */
public final class MigrationImporter {
    private MigrationImporter() {
    }

    public static boolean looksLike(String text) {
        return text != null && text.trim().startsWith("otpauth-migration://");
    }

    public static List<TotpEntry> parse(String uriString) {
        List<TotpEntry> result = new ArrayList<>();
        if (uriString == null) {
            return result;
        }
        String dataParam = extractData(uriString);
        if (dataParam == null || dataParam.isEmpty()) {
            return result;
        }

        byte[] payload;
        try {
            payload = Base64.decode(dataParam, Base64.DEFAULT);
        } catch (Exception e) {
            return result;
        }
        if (payload.length == 0) {
            return result;
        }

        ProtoReader reader = new ProtoReader(payload);
        while (reader.hasMore()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                TotpEntry entry = parseParameters(reader.readBytes());
                if (entry != null) {
                    result.add(entry);
                }
            } else {
                reader.skip(wire);
            }
        }
        return result;
    }

    private static String extractData(String uriString) {
        int index = uriString.indexOf("data=");
        if (index < 0) {
            return null;
        }
        String raw = uriString.substring(index + 5);
        int amp = raw.indexOf('&');
        if (amp >= 0) {
            raw = raw.substring(0, amp);
        }
        // Uri.decode leaves '+' alone, which URLDecoder would turn into a space and break the
        // base64 payload.
        return Uri.decode(raw);
    }

    private static TotpEntry parseParameters(byte[] message) {
        ProtoReader reader = new ProtoReader(message);
        String secret = null;
        String name = "";
        String issuer = "";
        int algorithm = 0;
        int digits = 0;
        int type = 0;
        long counter = 0;

        while (reader.hasMore()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1:
                    if (wire == 2) {
                        secret = TotpGenerator.base32Encode(reader.readBytes());
                    } else {
                        reader.skip(wire);
                    }
                    break;
                case 2:
                    name = reader.readString();
                    break;
                case 3:
                    issuer = reader.readString();
                    break;
                case 4:
                    algorithm = (int) reader.readVarint();
                    break;
                case 5:
                    // DigitCount: SIX = 1, EIGHT = 2 (official enum).
                    digits = (int) reader.readVarint();
                    break;
                case 6:
                    // OtpType: HOTP = 1, TOTP = 2.
                    type = (int) reader.readVarint();
                    break;
                case 7:
                    counter = reader.readVarint();
                    break;
                default:
                    reader.skip(wire);
                    break;
            }
        }

        if (secret == null || secret.isEmpty()) {
            return null;
        }

        TotpEntry entry = new TotpEntry();
        entry.secret = secret;
        entry.issuer = issuer == null ? "" : issuer;
        entry.name = name == null ? "" : name;

        // Google writes "Issuer:account" into name when the label carries both.
        if (entry.issuer.isEmpty() && entry.name.contains(":")) {
            String[] parts = entry.name.split(":", 2);
            entry.issuer = parts[0];
            entry.name = parts.length > 1 ? parts[1] : "";
        } else if (entry.name.contains(":") && entry.name.startsWith(entry.issuer + ":")) {
            entry.name = entry.name.substring(entry.issuer.length() + 1);
        }

        switch (algorithm) {
            case 2:
                entry.algorithm = "SHA256";
                break;
            case 3:
                entry.algorithm = "SHA512";
                break;
            case 4:
                entry.algorithm = "MD5";
                break;
            default:
                entry.algorithm = "SHA1";
                break;
        }

        entry.digits = digits == 2 ? 8 : 6;
        entry.type = type == 1 ? TotpEntry.TYPE_HOTP : TotpEntry.TYPE_TOTP;
        entry.counter = counter;
        entry.period = 30;
        entry.sourceLabel = "Google Authenticator";
        entry.sourcePackage = "com.google.android.apps.authenticator2";

        if (entry.issuer.isEmpty()) {
            entry.issuer = entry.name.isEmpty() ? "未命名" : entry.name;
        }
        return entry;
    }
}
