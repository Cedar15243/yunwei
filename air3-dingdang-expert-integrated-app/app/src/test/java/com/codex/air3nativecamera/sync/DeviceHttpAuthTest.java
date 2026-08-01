package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DeviceHttpAuthTest {
    @Test
    public void exchangesBootstrapOnlyAtTheSessionEndpoint() throws Exception {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/functions/v1/ops-glasses/device-sync/events",
                "bootstrap-a");
        CapturingConnection connection = new CapturingConnection(
                new URL(configuration.sessionEndpoint()),
                201,
                "{\"ok\":true,\"accessToken\":\"access-a\",\"expiresAt\":\"2026-07-31T04:15:00.000Z\"}");
        HttpDeviceSessionIssuer issuer = new HttpDeviceSessionIssuer(configuration,
                new SingleConnectionFactory(connection));

        DeviceAccessSession session = issuer.exchange(configuration.bootstrapCredential());

        assertEquals("Bearer bootstrap-a", connection.getRequestProperty("Authorization"));
        assertEquals("POST", connection.method);
        assertEquals("access-a", session.accessToken());
        assertEquals(1785471300000L, session.expiresAtMillis());
    }

    @Test
    public void taskEventsUseOnlyTheInMemoryAccessToken() throws Exception {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/functions/v1/ops-glasses/device-sync/events",
                "bootstrap-a");
        CapturingConnection connection = new CapturingConnection(
                new URL(configuration.endpoint()), 202, "{}");
        DeviceAccessTokenProvider provider = new DeviceAccessTokenProvider() {
            @Override
            public String accessToken() {
                return "access-a";
            }
        };
        HttpTaskSyncTransport transport = new HttpTaskSyncTransport(
                configuration, provider, new SingleConnectionFactory(connection));

        transport.send(new TaskSyncEvent(
                "project-a", "task-a", "user_message", "{}", 1_000L, "event-a"));

        assertEquals("Bearer access-a", connection.getRequestProperty("Authorization"));
        assertEquals(false, connection.getRequestProperty("Authorization").contains("bootstrap-a"));
    }

    private static final class SingleConnectionFactory implements HttpConnectionFactory {
        private final HttpURLConnection connection;

        SingleConnectionFactory(HttpURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpURLConnection open(String endpoint) {
            return connection;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        String method = "";

        CapturingConnection(URL url, int status, String response) {
            super(url);
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void setRequestMethod(String method) {
            this.method = method;
        }

        @Override
        public OutputStream getOutputStream() {
            return requestBody;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(response);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(response);
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() throws IOException {
        }
    }
}
