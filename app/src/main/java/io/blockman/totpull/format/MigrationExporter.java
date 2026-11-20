package io.blockman.totpull.format;

import android.net.Uri;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;

import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;

/**
 * Builds Google Authenticator export links from vault entries: batches of ten entries encoded
 * as {@code otpauth-migration://offline?data=<base64 protobuf>}, the exact format the official
 * app prints as QR codes and {@link MigrationImporter} reads back.
 */
public final class MigrationExporter {

    /** Google Authenticator never puts more than ten accounts on one exported QR. */
    public static final int BATCH_SIZE = 10;

    private MigrationExporter() {
    }

    /** One migration URI per batch, in vault order. */
    public static List<String> toMigrationUris(List<TotpEntry> entries) {
        List<String> uris = new ArrayList<>();
        List<TotpEntry> shareable = new ArrayList<>();
        for (TotpEntry e : entries) {
            // Steam seeds are not plain TOTP raw keys and HOTP fits fine; Yandex / MOTP do not
            // belong in a Google export at all.
            if (!e.isSupported() || e.isSteam()) {
                continue;
            }
            shareable.add(e);
        }

        int batches = (shareable.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        for (int b = 0; b < batches; b++) {
            int from = b * BATCH_SIZE;
            int to = Math.min(from + BATCH_SIZE, shareable.size());

            ProtoWriter payload = new ProtoWriter();
            for (int i = from; i < to; i++) {
                payload.bytesField(1, encodeParameters(shareable.get(i)));
            }
            payload.varintField(2, 1);          // version
            payload.varintField(3, batches);    // batch_size
            payload.varintField(4, b + 1);      // batch_index, one-based like the official app

            String data = Base64.encodeToString(payload.toBytes(), Base64.NO_WRAP);
            uris.add("otpauth-migration://offline?data=" + Uri.encode(data));
        }
        return uris;
    }

    /**
     * Encodes one OtpParameters message with Google's official field numbering:
     * 1 secret, 2 name, 3 issuer, 4 algorithm, 5 digits, 6 type, 7 counter. Third-party
     * validators parse this format, so the numbering must match exactly - a self-consistent
     * but swapped mapping decodes fine in TOTPull and breaks everywhere else.
     */
    private static byte[] encodeParameters(TotpEntry e) {
        ProtoWriter m = new ProtoWriter();
        m.bytesField(1, TotpGenerator.base32Decode(TotpGenerator.canonicalSecret(e.secret)));
        // Google's label convention: "Issuer:account".
        String label = (e.issuer == null || e.issuer.isEmpty() ? "" : e.issuer + ":")
                + (e.name == null ? "" : e.name);
        m.stringField(2, label);
        m.stringField(3, e.issuer == null ? "" : e.issuer);
        m.varintField(4, algorithmCode(e.getAlgorithmForMac()));
        // DigitCount: SIX = 1, EIGHT = 2 (official enum values).
        m.varintField(5, e.digits >= 8 ? 2 : 1);
        // OtpType: HOTP = 1, TOTP = 2.
        m.varintField(6, e.isHotp() ? 1 : 2);
        m.varintField(7, e.isHotp() ? e.counter : 0);
        return m.toBytes();
    }

    private static int algorithmCode(String algorithm) {
        switch (algorithm == null ? "SHA1" : algorithm) {
            case "SHA256":
                return 2;
            case "SHA512":
                return 3;
            case "MD5":
                return 4;
            default:
                return 1; // ALGORITHM_TYPE_SHA1
        }
    }
}
