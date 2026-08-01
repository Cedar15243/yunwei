package com.codex.air3nativecamera.sync;

import java.io.IOException;
import java.net.HttpURLConnection;

/** Applies either legacy server authorization or a current V9 device session. */
public final class BackendAuthorization {
    private final DeviceAccessTokenProvider accessTokenProvider;
    private final String legacyCredential;

    private BackendAuthorization(
            DeviceAccessTokenProvider accessTokenProvider,
            String legacyCredential) {
        this.accessTokenProvider = accessTokenProvider;
        this.legacyCredential = clean(legacyCredential);
    }

    public static BackendAuthorization session(DeviceAccessTokenProvider accessTokenProvider) {
        return new BackendAuthorization(accessTokenProvider, "");
    }

    public static BackendAuthorization legacy(String credential) {
        return new BackendAuthorization(null, credential);
    }

    public boolean isProvisioned() {
        return accessTokenProvider != null || legacyCredential.length() > 0;
    }

    public String bearerToken() throws IOException {
        String token = accessTokenProvider == null
                ? legacyCredential
                : clean(accessTokenProvider.accessToken());
        if (token.length() == 0) throw new IOException("managed_backend_credential_missing");
        return token;
    }

    public void apply(HttpURLConnection connection) throws IOException {
        String token = bearerToken();
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (legacyCredential.length() > 0) {
            connection.setRequestProperty("x-ops-glasses-key", legacyCredential);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
