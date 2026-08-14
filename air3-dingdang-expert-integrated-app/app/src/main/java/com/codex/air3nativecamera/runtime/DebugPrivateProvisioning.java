package com.codex.air3nativecamera.runtime;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

/** Strict schema for an ADB-delivered debug bootstrap stored in app-private storage. */
public final class DebugPrivateProvisioning {
    public static final String FILE_NAME = "v9-debug-provisioning.json";
    public static final int MAX_FILE_BYTES = 4_096;
    private static final Set<String> ALLOWED_FIELDS;

    static {
        Set<String> fields = new HashSet<>();
        fields.add("schemaVersion");
        fields.add("backendBaseUrl");
        fields.add("bootstrapCredential");
        ALLOWED_FIELDS = Collections.unmodifiableSet(fields);
    }

    private DebugPrivateProvisioning() {
    }

    public static Map<String, String> parse(String content) {
        try {
            JSONObject json = new JSONObject(content == null ? "" : content);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                if (!ALLOWED_FIELDS.contains(keys.next())) {
                    throw new IllegalArgumentException("debug_provisioning_unknown_field");
                }
            }
            if (json.optInt("schemaVersion", 0) != 1) {
                throw new IllegalArgumentException("debug_provisioning_schema_unsupported");
            }
            String baseUrl = trimTrailingSlash(json.optString("backendBaseUrl", ""));
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("debug_provisioning_https_required");
            }
            String credential = clean(json.optString("bootstrapCredential", ""));
            if (!credential.matches("[A-Za-z0-9_-]{32,256}")) {
                throw new IllegalArgumentException("debug_provisioning_credential_invalid");
            }
            Map<String, String> values = new HashMap<>();
            values.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, baseUrl);
            values.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, credential);
            return Collections.unmodifiableMap(values);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("debug_provisioning_invalid", error);
        }
    }

    public static Map<String, String> mergeAfterManaged(
            Map<String, String> managed,
            Map<String, String> debugFallback) {
        Map<String, String> result = new HashMap<>();
        if (managed != null) result.putAll(managed);
        boolean hasManagedBackend = clean(result.get(
                ManagedRuntimeConfiguration.BACKEND_BASE_URL)).length() > 0
                || clean(result.get(
                ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN)).length() > 0;
        if (hasManagedBackend || debugFallback == null) {
            return result;
        }
        String baseUrl = clean(debugFallback.get(ManagedRuntimeConfiguration.BACKEND_BASE_URL));
        String credential = clean(debugFallback.get(
                ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN));
        if (baseUrl.length() > 0 && credential.length() > 0) {
            result.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, baseUrl);
            result.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, credential);
        }
        return result;
    }

    private static String trimTrailingSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
