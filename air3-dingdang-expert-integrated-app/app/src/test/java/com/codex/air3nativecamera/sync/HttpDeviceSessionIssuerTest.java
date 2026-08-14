package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HttpDeviceSessionIssuerTest {
    @Test
    public void classifiesOnlyStableClientErrorsAsAuthoritativeRevocation()
            throws Exception {
        HttpDeviceSessionIssuer issuer = issuer(new StubConnection(
                403,
                "{\"ok\":false,\"error\":\"device_revoked\","
                        + "\"bootstrapCredential\":\"must-not-leak\"}"));

        try {
            issuer.exchange("bootstrap-a");
            fail("expected authoritative revocation");
        } catch (DeviceSessionException expected) {
            assertEquals("device_revoked", expected.errorCode());
            assertEquals(403, expected.httpStatus());
            assertTrue(expected.isAuthoritativeRevocation());
            assertFalse(expected.getMessage().contains("must-not-leak"));
        }
    }

    @Test
    public void serverErrorsNeverClearCredentialsEvenIfTheBodyClaimsRevocation()
            throws Exception {
        HttpDeviceSessionIssuer issuer = issuer(new StubConnection(
                503,
                "{\"ok\":false,\"error\":\"device_revoked\"}"));

        try {
            issuer.exchange("bootstrap-a");
            fail("expected server error");
        } catch (DeviceSessionException expected) {
            assertEquals("device_session_http_503", expected.errorCode());
            assertEquals(503, expected.httpStatus());
            assertFalse(expected.isAuthoritativeRevocation());
        }
    }

    @Test
    public void ordinaryInvalidBootstrapResponsesAreNotAuthoritativeRevocation()
            throws Exception {
        HttpDeviceSessionIssuer issuer = issuer(new StubConnection(
                401,
                "{\"ok\":false,\"error\":\"invalid_bootstrap\"}"));

        try {
            issuer.exchange("bootstrap-a");
            fail("expected invalid bootstrap");
        } catch (DeviceSessionException expected) {
            assertEquals("invalid_bootstrap", expected.errorCode());
            assertFalse(expected.isAuthoritativeRevocation());
        }
    }

    private static HttpDeviceSessionIssuer issuer(HttpURLConnection connection) {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events",
                "bootstrap-a");
        return new HttpDeviceSessionIssuer(configuration, endpoint -> connection);
    }

    private static final class StubConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        StubConnection(int status, String response) throws Exception {
            super(new URL("https://ops.example.com/v9-ops/device-sync/session"));
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void setRequestMethod(String method) { }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() throws IOException {
            if (status >= 400) throw new IOException("http_" + status);
            return new ByteArrayInputStream(response);
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
