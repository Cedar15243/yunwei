package com.codex.air3nativecamera.sync;

import java.net.URI;

/** Validated device-sync configuration supplied by the Android enterprise policy. */
public final class DeviceSyncConfiguration {
    private final String endpoint;
    private final String sessionEndpoint;
    private final String bootstrapCredential;

    private DeviceSyncConfiguration(String endpoint, String sessionEndpoint, String bootstrapCredential) {
        this.endpoint = endpoint;
        this.sessionEndpoint = sessionEndpoint;
        this.bootstrapCredential = bootstrapCredential;
    }

    public String endpoint() {
        return endpoint;
    }

    public String sessionEndpoint() {
        return sessionEndpoint;
    }

    public String bootstrapCredential() {
        return bootstrapCredential;
    }

    public static DeviceSyncConfiguration fromManagedValues(String endpoint, String deviceToken) {
        String safeEndpoint = trimTrailingSlash(endpoint);
        String safeToken = clean(deviceToken);
        if (safeEndpoint.length() == 0 || safeToken.length() == 0) return null;
        try {
            URI uri = URI.create(safeEndpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            return new DeviceSyncConfiguration(safeEndpoint, siblingSessionEndpoint(safeEndpoint), safeToken);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String siblingSessionEndpoint(String endpoint) {
        return endpoint.endsWith("/events")
                ? endpoint.substring(0, endpoint.length() - "/events".length()) + "/session"
                : endpoint + "/session";
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
