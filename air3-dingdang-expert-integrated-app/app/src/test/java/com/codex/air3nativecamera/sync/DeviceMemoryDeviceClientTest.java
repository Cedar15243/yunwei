package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.Test;

public final class DeviceMemoryDeviceClientTest {
    @Test
    public void loadsAuthorizedDeviceMemoryFromTheShortSessionEndpoint() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, catalogJson());
        DeviceMemoryDeviceClient client = new DeviceMemoryDeviceClient(
                DeviceSyncConfiguration.fromManagedValues(
                        "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a"),
                new DeviceAccessTokenProvider() {
                    @Override public String accessToken() { return "access-a"; }
                },
                connections);

        DeviceMemoryDeviceClient.Catalog catalog = client.load();

        assertEquals(1, catalog.items().size());
        DeviceMemoryDeviceClient.Item item = catalog.items().get(0);
        assertEquals("equipment-a", item.id());
        assertEquals("\u6696\u901a", item.system());
        assertEquals("\u534e\u65b9", item.brand());
        assertEquals(4, item.quantity());
        assertEquals(1, item.linkedProjects().size());
        assertEquals("project-a", item.linkedProjects().get(0).localProjectId());
        assertTrue(connections.opened.get(0).endsWith("/device-sync/device-memory"));
        assertEquals("Bearer access-a",
                connections.used.get(0).getRequestProperty("Authorization"));
    }

    @Test
    public void rejectsMalformedDeviceMemoryWithoutCreatingLocalFallback() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"ok\":true,\"items\":[{\"id\":\"equipment-a\"}]}");
        DeviceMemoryDeviceClient client = new DeviceMemoryDeviceClient(
                DeviceSyncConfiguration.fromManagedValues(
                        "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a"),
                new DeviceAccessTokenProvider() {
                    @Override public String accessToken() { return "access-a"; }
                },
                connections);

        try {
            client.load();
        } catch (IOException error) {
            assertEquals("device_memory_response_invalid", error.getMessage());
            return;
        }
        throw new AssertionError("expected malformed device memory to fail closed");
    }

    private static String catalogJson() {
        return "{\"ok\":true,\"schemaVersion\":1,\"generatedAt\":\"2026-08-07T00:00:00Z\","
                + "\"items\":[{\"id\":\"equipment-a\",\"system\":\"\u6696\u901a\","
                + "\"brand\":\"\u534e\u65b9\",\"model\":\"HF-DDC-100\",\"quantity\":4,"
                + "\"status\":\"normal\",\"lastInspectionAt\":\"2026-08-06T10:00:00Z\","
                + "\"faultCount\":1,\"repairCount\":2,\"keyParameter\":\"\u9001\u98ce 17 C\","
                + "\"linkedProjects\":[{\"projectId\":\"project-cloud-a\","
                + "\"localProjectId\":\"project-a\",\"title\":\"\u56ed\u533a\u7a7a\u8c03\","
                + "\"status\":\"active\",\"taskCount\":2}]}]}";
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        @Override public HttpURLConnection open(String endpoint) throws IOException {
            Response response = responses.remove();
            CapturingConnection connection = new CapturingConnection(
                    new URL(endpoint), response.status, response.body);
            opened.add(endpoint);
            used.add(connection);
            return connection;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        private CapturingConnection(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void setRequestMethod(String method) { }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(response);
        }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(response);
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public java.io.OutputStream getOutputStream() { return request; }
    }
}
