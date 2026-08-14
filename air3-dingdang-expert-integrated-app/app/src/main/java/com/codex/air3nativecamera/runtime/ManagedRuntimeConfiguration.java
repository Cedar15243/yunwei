package com.codex.air3nativecamera.runtime;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

/** Resolves runtime credentials without allowing secure builds to fall back to APK secrets. */
public final class ManagedRuntimeConfiguration {
    public static final String BACKEND_BASE_URL = "dingdang_backend_base_url";
    public static final String BACKEND_DEVICE_TOKEN = "dingdang_backend_device_token";
    public static final String IFLYTEK_APP_ID = "iflytek_app_id";
    public static final String IFLYTEK_API_KEY = "iflytek_api_key";
    public static final String IFLYTEK_API_SECRET = "iflytek_api_secret";

    private final String backendBaseUrl;
    private final String backendCredential;
    private final String iflytekAppId;
    private final String iflytekApiKey;
    private final String iflytekApiSecret;
    private final boolean localActivation;

    private ManagedRuntimeConfiguration(
            String backendBaseUrl,
            String backendCredential,
            String iflytekAppId,
            String iflytekApiKey,
            String iflytekApiSecret,
            boolean localActivation) {
        this.backendBaseUrl = backendBaseUrl;
        this.backendCredential = backendCredential;
        this.iflytekAppId = iflytekAppId;
        this.iflytekApiKey = iflytekApiKey;
        this.iflytekApiSecret = iflytekApiSecret;
        this.localActivation = localActivation;
    }

    public static ManagedRuntimeConfiguration resolve(
            boolean secureRuntime,
            String generatedBackendBaseUrl,
            String generatedBackendCredential,
            String generatedIflytekAppId,
            String generatedIflytekApiKey,
            String generatedIflytekApiSecret,
            Map<String, String> managedValues) {
        return resolve(
                secureRuntime,
                generatedBackendBaseUrl,
                generatedBackendCredential,
                generatedIflytekAppId,
                generatedIflytekApiKey,
                generatedIflytekApiSecret,
                managedValues,
                null,
                System.currentTimeMillis());
    }

    public static ManagedRuntimeConfiguration resolve(
            boolean secureRuntime,
            String generatedBackendBaseUrl,
            String generatedBackendCredential,
            String generatedIflytekAppId,
            String generatedIflytekApiKey,
            String generatedIflytekApiSecret,
            Map<String, String> managedValues,
            DeviceActivationRecord localActivation,
            long nowMillis) {
        Map<String, String> safeManaged = managedValues == null
                ? Collections.<String, String>emptyMap()
                : managedValues;
        if (!secureRuntime) {
            return new ManagedRuntimeConfiguration(
                    trimTrailingSlash(generatedBackendBaseUrl),
                    clean(generatedBackendCredential),
                    clean(generatedIflytekAppId),
                    clean(generatedIflytekApiKey),
                    clean(generatedIflytekApiSecret),
                    false);
        }
        String rawManagedBaseUrl = clean(safeManaged.get(BACKEND_BASE_URL));
        String rawManagedCredential = clean(safeManaged.get(BACKEND_DEVICE_TOKEN));
        boolean managedBackendPresent = rawManagedBaseUrl.length() > 0
                || rawManagedCredential.length() > 0;
        String backendBaseUrl;
        String backendCredential;
        boolean usesLocalActivation = false;
        if (managedBackendPresent) {
            backendBaseUrl = validHttpsUrl(rawManagedBaseUrl);
            backendCredential = backendBaseUrl.length() > 0
                    && rawManagedCredential.length() > 0
                    ? rawManagedCredential : "";
        } else if (localActivation != null && localActivation.isValidAt(nowMillis)) {
            backendBaseUrl = localActivation.backendBaseUrl();
            backendCredential = localActivation.bootstrapCredential();
            usesLocalActivation = true;
        } else {
            backendBaseUrl = validHttpsUrl(generatedBackendBaseUrl);
            backendCredential = "";
        }
        return new ManagedRuntimeConfiguration(
                backendBaseUrl,
                backendCredential,
                clean(safeManaged.get(IFLYTEK_APP_ID)),
                clean(safeManaged.get(IFLYTEK_API_KEY)),
                clean(safeManaged.get(IFLYTEK_API_SECRET)),
                usesLocalActivation);
    }

    public String backendBaseUrl() {
        return backendBaseUrl;
    }

    public String backendCredential() {
        return backendCredential;
    }

    public String iflytekAppId() {
        return iflytekAppId;
    }

    public String iflytekApiKey() {
        return iflytekApiKey;
    }

    public String iflytekApiSecret() {
        return iflytekApiSecret;
    }

    public boolean isBackendProvisioned() {
        return backendBaseUrl.length() > 0 && backendCredential.length() > 0;
    }

    public boolean hasIflytekCredentials() {
        return iflytekAppId.length() > 0 && iflytekApiKey.length() > 0 && iflytekApiSecret.length() > 0;
    }

    public boolean usesLocalActivation() {
        return localActivation;
    }

    private static String validHttpsUrl(String value) {
        String result = trimTrailingSlash(value);
        if (result.length() == 0) return "";
        try {
            URI uri = URI.create(result);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? result : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
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
