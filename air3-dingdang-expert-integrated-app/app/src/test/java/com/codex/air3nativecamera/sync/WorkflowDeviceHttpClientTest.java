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
import java.util.Arrays;
import java.util.Queue;

public final class WorkflowDeviceHttpClientTest {
    @Test
    public void listsAssignmentsAndFetchesAPackageWithShortSessionAndCapabilities() throws Exception {
        DeviceSyncConfiguration configuration = configuration();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"assignmentId\":\"assignment-a\","
                + "\"workOrderId\":\"order-a\",\"projectId\":\"project-a\","
                + "\"workflowVersionId\":\"version-a\",\"mode\":\"required\","
                + "\"status\":\"queued\",\"deliverySequence\":7,"
                + "\"assignedAt\":\"2026-08-01T00:00:00Z\"}],\"nextSequence\":7}");
        connections.enqueue(200, "{\"assignmentId\":\"assignment-a\","
                + "\"workflowVersionId\":\"version-a\",\"schemaVersion\":1,"
                + "\"executionPackage\":{},\"contentSha256\":\""
                + repeat('a', 64) + "\",\"packageSignature\":\"signature\","
                + "\"signatureKeyId\":\"key-a\",\"requiredCapabilities\":["
                + "\"workflow.runtime.v1\",\"camera.photo\"],\"minAppVersionCode\":9000}");
        WorkflowDeviceHttpClient client = client(configuration, connections);

        WorkflowDeviceHttpClient.AssignmentPage page = client.listAssignments(3, 50);
        JSONObject envelope = client.fetchPackage("assignment-a");

        assertEquals(1, page.items().size());
        assertEquals(7L, page.nextSequence());
        assertEquals("required", page.items().get(0).mode());
        assertEquals("version-a", envelope.getString("workflowVersionId"));
        assertEquals(configuration.workflowEndpoint()
                + "/assignments?afterSequence=3&limit=50", connections.opened.get(0));
        assertEquals(configuration.workflowEndpoint()
                + "/assignments/assignment-a/package", connections.opened.get(1));
        CapturingConnection packageConnection = connections.used.get(1);
        assertEquals("Bearer access-a", packageConnection.getRequestProperty("Authorization"));
        assertFalse(packageConnection.getRequestProperty("Authorization").contains("bootstrap-a"));
        assertEquals("9002", packageConnection.getRequestProperty("X-App-Version-Code"));
        assertEquals("1", packageConnection.getRequestProperty("X-Workflow-Schema-Version"));
        assertEquals("workflow.runtime.v1,camera.photo",
                packageConnection.getRequestProperty("X-Workflow-Capabilities"));
    }

    @Test
    public void routesPersistedWorkflowEventsOnlyToWhitelistedGatewayEndpoints() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"assignmentId\":\"assignment-a\",\"status\":\"verified\"}");
        connections.enqueue(201, "{\"executionId\":\"execution-a\"}");
        connections.enqueue(200, "{\"stepExecutionId\":\"step-a\"}");
        WorkflowDeviceHttpClient client = client(configuration(), connections);

        client.send(workflowEvent(
                "assignment-a:verified",
                "workflow_assignment_status",
                json("assignmentId", "assignment-a", "status", "verified",
                        "idempotencyKey", "assignment-a:verified",
                        "failureStage", JSONObject.NULL, "failureReason", JSONObject.NULL),
                1000L));
        client.send(workflowEvent(
                "assignment-a:start",
                "workflow_execution_start",
                json("assignmentId", "assignment-a", "projectId", "project-a",
                        "taskId", "task-a", "initialNodeId", "photo",
                        "runtimeSnapshot", new JSONObject(),
                        "idempotencyKey", "assignment-a:start"),
                1001L));
        client.send(workflowEvent(
                "execution-a:photo:1:completed",
                "workflow_step_event",
                json("executionId", "execution-a", "nodeId", "photo", "attemptNumber", 1,
                        "status", "completed", "idempotencyKey", "execution-a:photo:1:completed",
                        "inputData", new JSONObject(), "outputData", new JSONObject(),
                        "evidenceAssetIds", new org.json.JSONArray(),
                        "transitionResult", new JSONObject(), "failureCode", JSONObject.NULL,
                        "failureReason", JSONObject.NULL, "nextNodeId", "complete",
                        "runtimeSnapshot", new JSONObject()),
                1002L));

        assertTrue(connections.opened.get(0).endsWith("/assignments/assignment-a/status"));
        assertTrue(connections.opened.get(1).endsWith("/executions"));
        assertTrue(connections.opened.get(2).endsWith("/executions/execution-a/steps"));
        assertFalse(connections.used.get(0).requestText().contains("\"assignmentId\""));
        assertFalse(connections.used.get(2).requestText().contains("\"executionId\""));
    }

    private WorkflowDeviceHttpClient client(
            DeviceSyncConfiguration configuration,
            QueueConnectionFactory connections
    ) {
        return new WorkflowDeviceHttpClient(
                configuration,
                new DeviceAccessTokenProvider() {
                    @Override
                    public String accessToken() {
                        return "access-a";
                    }
                },
                9002,
                1,
                Arrays.asList("workflow.runtime.v1", "camera.photo"),
                connections);
    }

    private DeviceSyncConfiguration configuration() {
        return DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/functions/v1/ops-glasses/device-sync/events",
                "bootstrap-a");
    }

    private static JSONObject json(Object... values) {
        try {
            JSONObject result = new JSONObject();
            for (int index = 0; index + 1 < values.length; index += 2) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static TaskSyncEvent workflowEvent(
            String idempotencyKey,
            String eventType,
            JSONObject payload,
            long createdAt
    ) {
        return new TaskSyncEvent(
                "project-a", "task-a", eventType, payload.toString(), createdAt, idempotencyKey);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        @Override
        public HttpURLConnection open(String endpoint) throws IOException {
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
        private String method = "";

        private CapturingConnection(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void setRequestMethod(String method) { this.method = method; }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }

        private String requestText() {
            return new String(request.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
