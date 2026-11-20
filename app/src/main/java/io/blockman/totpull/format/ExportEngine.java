package io.blockman.totpull.format;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.blockman.totpull.TotpEntry;
import io.blockman.totpull.TotpGenerator;

/**
 * Turns vault entries back into portable export formats. The Aegis shape mirrors what
 * {@link ImportEngine#parseAegis} reads, so a round trip through TOTPull itself reproduces
 * type, parameters and group names; the same file also imports into Aegis directly.
 */
public final class ExportEngine {

    private ExportEngine() {
    }

    /** One otpauth:// URI per line - the shape the group export already shares. */
    public static String toOtpauthText(List<TotpEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (TotpEntry e : entries) {
            sb.append(e.toOtpauthUri()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Aegis plaintext vault, version 1, no password slots. Entries TOTPull cannot generate
     * (Yandex / MOTP) are left out - their seeds are not plain base32 and no reader here
     * would reconstruct them correctly.
     */
    public static String toAegisJson(List<TotpEntry> entries) throws Exception {
        // One Aegis group per TOTPull group name; entries reference groups by uuid.
        Map<String, String> groupUuids = new LinkedHashMap<>();
        for (TotpEntry e : entries) {
            String group = e.group == null ? "" : e.group;
            if (!group.isEmpty() && !groupUuids.containsKey(group)) {
                groupUuids.put(group, UUID.randomUUID().toString());
            }
        }

        JSONArray groups = new JSONArray();
        for (Map.Entry<String, String> row : groupUuids.entrySet()) {
            groups.put(new JSONObject()
                    .put("uuid", row.getValue())
                    .put("name", row.getKey()));
        }

        JSONArray out = new JSONArray();
        for (TotpEntry e : entries) {
            if (!e.isSupported()) {
                continue;
            }
            JSONObject info = new JSONObject();
            info.put("secret", TotpGenerator.canonicalSecret(e.secret));
            info.put("algo", e.getAlgorithmForMac());
            info.put("digits", e.digits);
            info.put("period", e.period);
            if (e.isHotp()) {
                info.put("counter", e.counter);
            }
            info.put("pin", e.pin == null ? "" : e.pin);

            JSONArray groupIds = new JSONArray();
            String group = e.group == null ? "" : e.group;
            if (!group.isEmpty()) {
                groupIds.put(groupUuids.get(group));
            }

            JSONObject o = new JSONObject();
            o.put("type", e.isSteam() ? "steam" : e.isHotp() ? "hotp" : "totp");
            o.put("uuid", e.id);
            o.put("name", e.name == null ? "" : e.name);
            o.put("issuer", e.issuer == null ? "" : e.issuer);
            o.put("note", e.note == null ? "" : e.note);
            o.put("icon", JSONObject.NULL);
            o.put("groups", groupIds);
            o.put("info", info);
            out.put(o);
        }

        JSONObject db = new JSONObject();
        db.put("version", 1);
        db.put("groups", groups);
        db.put("entries", out);

        JSONObject header = new JSONObject();
        header.put("slots", JSONObject.NULL);
        header.put("params", JSONObject.NULL);

        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("header", header);
        root.put("db", db);
        return root.toString(2);
    }
}
