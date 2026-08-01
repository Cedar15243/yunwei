package com.codex.air3nativecamera.sync;

/** In-memory device access session returned by the V9 gateway. */
public final class DeviceAccessSession {
    private final String accessToken;
    private final long expiresAtMillis;

    public DeviceAccessSession(String accessToken, long expiresAtMillis) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.expiresAtMillis = expiresAtMillis;
    }

    public String accessToken() {
        return accessToken;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }
}
