package com.codex.air3nativecamera.runtime;

import org.json.JSONObject;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Immutable device bootstrap returned by the managed activation service. */
public final class DeviceActivationRecord {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Set<String> ALLOWED_FIELDS = new HashSet<>(Arrays.asList(
            "schemaVersion",
            "backendBaseUrl",
            "bootstrapCredential",
            "credentialExpiresAtMillis",
            "deviceId",
            "organizationId",
            "policyVersion"));

    private final int schemaVersion;
    private final String backendBaseUrl;
    private final String bootstrapCredential;
    private final long credentialExpiresAtMillis;
    private final String deviceId;
    private final String organizationId;
    private final String policyVersion;

    private DeviceActivationRecord(
            int schemaVersion,
            String backendBaseUrl,
            String bootstrapCredential,
            long credentialExpiresAtMillis,
            String deviceId,
            String organizationId,
            String policyVersion) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("device_activation_schema_unsupported");
        }
        this.schemaVersion = schemaVersion;
        this.backendBaseUrl = requireHttpsUrl(backendBaseUrl);
        this.bootstrapCredential = requireBootstrap(bootstrapCredential);
        if (credentialExpiresAtMillis <= 0L) {
            throw new IllegalArgumentException("device_activation_expiry_invalid");
        }
        this.credentialExpiresAtMillis = credentialExpiresAtMillis;
        this.deviceId = requireText(deviceId, "device_activation_device_invalid");
        this.organizationId = requireText(
                organizationId, "device_activation_organization_invalid");
        this.policyVersion = requireText(
                policyVersion, "device_activation_policy_invalid");
    }

    public static DeviceActivationRecord create(
            String backendBaseUrl,
            String bootstrapCredential,
            long credentialExpiresAtMillis,
            String deviceId,
            String organizationId,
            String policyVersion) {
        return new DeviceActivationRecord(
                CURRENT_SCHEMA_VERSION,
                backendBaseUrl,
                bootstrapCredential,
                credentialExpiresAtMillis,
                deviceId,
                organizationId,
                policyVersion);
    }

    public static DeviceActivationRecord parse(String serialized) {
        try {
            JSONObject json = new JSONObject(serialized == null ? "" : serialized);
            Iterator<String> fields = json.keys();
            while (fields.hasNext()) {
                if (!ALLOWED_FIELDS.contains(fields.next())) {
                    throw new IllegalArgumentException("device_activation_unknown_field");
                }
            }
            return new DeviceActivationRecord(
                    json.optInt("schemaVersion", -1),
                    json.optString("backendBaseUrl", ""),
                    json.optString("bootstrapCredential", ""),
                    json.optLong("credentialExpiresAtMillis", -1L),
                    json.optString("deviceId", ""),
                    json.optString("organizationId", ""),
                    json.optString("policyVersion", ""));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("device_activation_record_invalid", error);
        }
    }

    public String toJson() {
        try {
            return new JSONObject()
                    .put("schemaVersion", schemaVersion)
                    .put("backendBaseUrl", backendBaseUrl)
                    .put("bootstrapCredential", bootstrapCredential)
                    .put("credentialExpiresAtMillis", credentialExpiresAtMillis)
                    .put("deviceId", deviceId)
                    .put("organizationId", organizationId)
                    .put("policyVersion", policyVersion)
                    .toString();
        } catch (Exception error) {
            throw new IllegalStateException("device_activation_record_serialization_failed", error);
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String backendBaseUrl() {
        return backendBaseUrl;
    }

    public String bootstrapCredential() {
        return bootstrapCredential;
    }

    public long credentialExpiresAtMillis() {
        return credentialExpiresAtMillis;
    }

    public String deviceId() {
        return deviceId;
    }

    public String organizationId() {
        return organizationId;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public boolean isValidAt(long nowMillis) {
        return nowMillis >= 0L && credentialExpiresAtMillis > nowMillis;
    }

    private static String requireHttpsUrl(String value) {
        String normalized = clean(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("device_activation_https_required");
            }
            return normalized;
        } catch (IllegalArgumentException error) {
            if ("device_activation_https_required".equals(error.getMessage())) throw error;
            throw new IllegalArgumentException("device_activation_https_required", error);
        }
    }

    private static String requireBootstrap(String value) {
        String normalized = clean(value);
        if (normalized.length() < 24 || normalized.length() > 1024
                || containsControlOrWhitespace(normalized)) {
            throw new IllegalArgumentException("device_activation_bootstrap_invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String errorCode) {
        String normalized = clean(value);
        if (normalized.length() == 0 || normalized.length() > 200
                || containsControl(normalized)) {
            throw new IllegalArgumentException(errorCode);
        }
        return normalized;
    }

    private static boolean containsControlOrWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            char candidate = value.charAt(index);
            if (Character.isISOControl(candidate) || Character.isWhitespace(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
