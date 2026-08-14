package com.codex.air3nativecamera.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

/** Strict schema for audit-only offline wake credentials stored in app-private storage. */
public final class DebugPrivateWakeProvisioning {
    public static final String FILE_NAME = "v9-debug-iflytek-wake.json";
    public static final int MAX_FILE_BYTES = 4_096;
    private static final Set<String> ALLOWED_FIELDS;

    static {
        Set<String> fields = new HashSet<>();
        fields.add("schemaVersion");
        fields.add("iflytekAppId");
        fields.add("iflytekApiKey");
        fields.add("iflytekApiSecret");
        ALLOWED_FIELDS = Collections.unmodifiableSet(fields);
    }

    private DebugPrivateWakeProvisioning() {
    }

    public static Map<String, String> parse(String content) {
        try {
            JSONObject json = new JSONObject(content == null ? "" : content);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                if (!ALLOWED_FIELDS.contains(keys.next())) {
                    throw new IllegalArgumentException("debug_wake_provisioning_unknown_field");
                }
            }
            if (json.optInt("schemaVersion", 0) != 1) {
                throw new IllegalArgumentException("debug_wake_provisioning_schema_unsupported");
            }
            String appId = credential(json.optString("iflytekAppId", ""), 4);
            String apiKey = credential(json.optString("iflytekApiKey", ""), 8);
            String apiSecret = credential(json.optString("iflytekApiSecret", ""), 8);
            Map<String, String> values = new HashMap<>();
            values.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, appId);
            values.put(ManagedRuntimeConfiguration.IFLYTEK_API_KEY, apiKey);
            values.put(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET, apiSecret);
            return Collections.unmodifiableMap(values);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("debug_wake_provisioning_invalid", error);
        }
    }

    public static Map<String, String> mergeAfterManaged(
            Map<String, String> managed,
            Map<String, String> debugFallback) {
        Map<String, String> result = new HashMap<>();
        if (managed != null) result.putAll(managed);
        boolean hasManagedWake = hasValue(result.get(ManagedRuntimeConfiguration.IFLYTEK_APP_ID))
                || hasValue(result.get(ManagedRuntimeConfiguration.IFLYTEK_API_KEY))
                || hasValue(result.get(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET));
        if (hasManagedWake || debugFallback == null) return result;

        String appId = clean(debugFallback.get(ManagedRuntimeConfiguration.IFLYTEK_APP_ID));
        String apiKey = clean(debugFallback.get(ManagedRuntimeConfiguration.IFLYTEK_API_KEY));
        String apiSecret = clean(debugFallback.get(
                ManagedRuntimeConfiguration.IFLYTEK_API_SECRET));
        if (hasValue(appId) && hasValue(apiKey) && hasValue(apiSecret)) {
            result.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, appId);
            result.put(ManagedRuntimeConfiguration.IFLYTEK_API_KEY, apiKey);
            result.put(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET, apiSecret);
        }
        return result;
    }

    private static String credential(String value, int minimumLength) {
        String result = clean(value);
        if (result.length() < minimumLength || result.length() > 256) {
            throw new IllegalArgumentException("debug_wake_provisioning_credential_invalid");
        }
        for (int index = 0; index < result.length(); index++) {
            char character = result.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("debug_wake_provisioning_credential_invalid");
            }
        }
        return result;
    }

    private static boolean hasValue(String value) {
        return clean(value).length() > 0;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
