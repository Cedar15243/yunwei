package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Ed25519 trust anchors supplied by managed app configuration, with key rotation support. */
public final class ManagedWorkflowPublicKeySource implements WorkflowPublicKeySource {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_KEYS = 8;
    private static final Set<String> ROOT_KEYS = set("schemaVersion", "keys");
    private static final Set<String> ENTRY_KEYS = set("keyId", "algorithm", "publicKey");

    private final Map<String, PublicKey> keys;

    private ManagedWorkflowPublicKeySource(Map<String, PublicKey> keys) {
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    public static ManagedWorkflowPublicKeySource fromManagedJson(String value) {
        String source = clean(value);
        if (source.isEmpty() || source.length() > 32_768) {
            throw new IllegalArgumentException("workflow public key configuration is missing");
        }
        try {
            JSONObject root = new JSONObject(source);
            if (!keysOf(root).equals(ROOT_KEYS)
                    || exactPositiveInt(root.opt("schemaVersion")) != SCHEMA_VERSION) {
                throw new IllegalArgumentException("workflow public key configuration is invalid");
            }
            JSONArray entries = root.optJSONArray("keys");
            if (entries == null || entries.length() < 1 || entries.length() > MAX_KEYS) {
                throw new IllegalArgumentException("workflow public key set is invalid");
            }
            LinkedHashMap<String, PublicKey> accepted = new LinkedHashMap<>();
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            for (int index = 0; index < entries.length(); index += 1) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null || !keysOf(entry).equals(ENTRY_KEYS)) {
                    throw new IllegalArgumentException("workflow public key entry is invalid");
                }
                String keyId = clean(entry.optString("keyId", ""));
                String algorithm = clean(entry.optString("algorithm", ""));
                String encodedValue = clean(entry.optString("publicKey", ""));
                if (!keyId.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}$")
                        || !"Ed25519".equals(algorithm)
                        || encodedValue.length() < 32 || encodedValue.length() > 2_048
                        || accepted.containsKey(keyId)) {
                    throw new IllegalArgumentException("workflow public key entry is invalid");
                }
                byte[] encoded = Base64.getDecoder().decode(encodedValue);
                if (encoded.length < 32 || encoded.length > 1_024) {
                    throw new IllegalArgumentException("workflow public key material is invalid");
                }
                PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
                accepted.put(keyId, publicKey);
            }
            return new ManagedWorkflowPublicKeySource(accepted);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("workflow public key configuration is invalid", exception);
        }
    }

    @Override
    public PublicKey find(String keyId) {
        return keys.get(clean(keyId));
    }

    public int size() {
        return keys.size();
    }

    private static int exactPositiveInt(Object value) {
        if (!(value instanceof Number)) return -1;
        Number number = (Number) value;
        double decimal = number.doubleValue();
        int integer = number.intValue();
        return Double.isFinite(decimal) && decimal == (double) integer && integer > 0
                ? integer : -1;
    }

    private static Set<String> set(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> keysOf(JSONObject value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) result.add(keys.next());
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
