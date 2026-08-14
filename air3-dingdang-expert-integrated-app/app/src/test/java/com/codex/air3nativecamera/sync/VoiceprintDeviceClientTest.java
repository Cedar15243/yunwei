package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

public final class VoiceprintDeviceClientTest {
    @Test
    public void verifyUsesTheShortSessionAndParsesOnlyTheNormalizedVerdict() throws Exception {
        CapturingConnection connection = new CapturingConnection(
                200,
                "{\"ok\":true,\"verified\":true,\"status\":\"active\"}");
        VoiceprintDeviceClient client = client(connection);

        VoiceprintDeviceClient.Result result = client.verify(
                new byte[]{0x52, 0x49, 0x46, 0x46}, "verify-a");

        assertEquals("POST", connection.method);
        assertEquals(
                "https://ops.example.com/v9-ops/device-sync/voiceprint/verify",
                connection.openedEndpoint);
        assertEquals("Bearer session-a", connection.getRequestProperty("Authorization"));
        assertFalse(connection.getRequestProperty("Authorization").contains("bootstrap-a"));
        JSONObject request = new JSONObject(connection.requestBody());
        assertEquals("verify-a", request.getString("idempotencyKey"));
        assertTrue(request.getString("audioBase64").length() > 0);
        assertTrue(result.ok());
        assertTrue(result.verified());
        assertEquals("active", result.status());
        assertEquals(3_000, connection.connectTimeoutMs);
        assertEquals(5_000, connection.readTimeoutMs);
    }

    @Test
    public void consentAndDeleteUseTheExactUtf8ConfirmationContract() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.add(new CapturingConnection(201, "{\"ok\":true,\"status\":\"enrolling\"}"));
        connections.add(new CapturingConnection(200, "{\"ok\":true,\"status\":\"deleted\"}"));
        VoiceprintDeviceClient client = client(connections);

        client.consent("2026-08-02.v1", "consent-a");
        client.delete("delete-a");

        JSONObject consent = new JSONObject(connections.used(0).requestBody());
        JSONObject delete = new JSONObject(connections.used(1).requestBody());
        assertTrue(consent.getBoolean("consentAccepted"));
        assertEquals("2026-08-02.v1", consent.getString("consentVersion"));
        assertEquals("删除我的声纹", delete.getString("confirmation"));
    }

    @Test
    public void enrollmentKeepsTheStrictSampleOrderInTheRequest() throws Exception {
        CapturingConnection connection = new CapturingConnection(
                201,
                "{\"ok\":true,\"status\":\"enrolling\",\"sampleCount\":2}");
        VoiceprintDeviceClient client = client(connection);

        VoiceprintDeviceClient.Result result = client.enrollSample(
                2, new byte[]{1, 2, 3}, "sample-2");

        JSONObject request = new JSONObject(connection.requestBody());
        assertEquals(2, request.getInt("sampleIndex"));
        assertEquals(2, result.sampleCount());
        assertEquals(
                "https://ops.example.com/v9-ops/device-sync/voiceprint/enrollment/samples",
                connection.openedEndpoint);
    }

    @Test
    public void profileAndProviderFailuresRemainExplicitWithoutVendorPayloads() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.add(new CapturingConnection(
                404, "{\"ok\":false,\"error\":\"voiceprint_not_enrolled\"}"));
        connections.add(new CapturingConnection(
                503, "{\"ok\":false,\"error\":\"voiceprint_unavailable\"}"));
        VoiceprintDeviceClient client = client(connections);

        VoiceprintDeviceClient.Result profile = client.profile();
        VoiceprintDeviceClient.Result verify = client.verify(new byte[]{1}, "verify-b");

        assertEquals("voiceprint_not_enrolled", profile.error());
        assertEquals("voiceprint_unavailable", verify.error());
        assertFalse(verify.toString().contains("provider"));
    }

    private static VoiceprintDeviceClient client(CapturingConnection connection) {
        return client(new QueueConnectionFactory(connection));
    }

    private static VoiceprintDeviceClient client(HttpConnectionFactory factory) {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        return new VoiceprintDeviceClient(
                configuration,
                new DeviceAccessTokenProvider() {
                    @Override
                    public String accessToken() {
                        return "session-a";
                    }
                },
                factory);
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<CapturingConnection> pending = new ArrayDeque<>();
        private final Queue<CapturingConnection> used = new ArrayDeque<>();

        QueueConnectionFactory(CapturingConnection... connections) {
            for (CapturingConnection connection : connections) add(connection);
        }

        void add(CapturingConnection connection) {
            pending.add(connection);
        }

        CapturingConnection used(int index) {
            return used.toArray(new CapturingConnection[0])[index];
        }

        @Override
        public HttpURLConnection open(String endpoint) throws IOException {
            CapturingConnection connection = pending.remove();
            connection.openedEndpoint = endpoint;
            used.add(connection);
            return connection;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int responseStatus;
        private final byte[] responseBody;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private String method = "";
        private String openedEndpoint = "";
        private int connectTimeoutMs;
        private int readTimeoutMs;

        CapturingConnection(int status, String response) throws Exception {
            super(new URL("https://unused.example"));
            responseStatus = status;
            responseBody = response.getBytes(StandardCharsets.UTF_8);
        }

        String requestBody() {
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void setRequestMethod(String method) {
            this.method = method;
        }

        @Override
        public OutputStream getOutputStream() {
            return body;
        }

        @Override
        public int getResponseCode() {
            return responseStatus;
        }

        @Override
        public void setConnectTimeout(int timeout) {
            connectTimeoutMs = timeout;
        }

        @Override
        public void setReadTimeout(int timeout) {
            readTimeoutMs = timeout;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (responseStatus >= 400) throw new IOException("http_" + responseStatus);
            return new ByteArrayInputStream(responseBody);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(responseBody);
        }

        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
