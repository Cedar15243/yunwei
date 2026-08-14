package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

public final class WorkflowDeviceHttpClientTest {
    private static final String EXECUTION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_EXECUTION_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void listsAssignmentsAndFetchesAPackageWithShortSessionAndCapabilities() throws Exception {
        DeviceSyncConfiguration configuration = configuration();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"assignmentId\":\"assignment-a\","
                + "\"workOrderId\":\"order-a\",\"projectId\":\"project-a\","
                + "\"workflowVersionId\":\"version-a\",\"mode\":\"required\","
                + "\"status\":\"queued\",\"deliverySequence\":7,"
                + "\"assignedAt\":\"2026-08-01T00:00:00Z\","
                + "\"workOrder\":{\"externalWorkOrderId\":\"MVS-20260801-001\","
                + "\"title\":\"冷水机组控制器故障\",\"description\":\"控制器报警\","
                + "\"customerId\":\"customer-a\",\"workOrderType\":\"repair\","
                + "\"assetId\":\"asset-a\",\"assetCategory\":\"hvac\","
                + "\"assetBrand\":\"Huafang\",\"assetModel\":\"HF-CH-01\","
                + "\"priority\":\"high\",\"riskLevel\":\"medium\","
                + "\"status\":\"received\",\"dueAt\":\"2026-08-02T01:00:00Z\","
                + "\"receivedAt\":\"2026-08-01T00:30:00Z\"}}],\"nextSequence\":7}");
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
        assertWorkOrderSummary(page.items().get(0));
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
        connections.enqueue(201, "{\"executionId\":\"" + EXECUTION_ID + "\"}");
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
                json("executionId", EXECUTION_ID,
                        "assignmentId", "assignment-a", "projectId", "project-a",
                        "localTaskId", "workflow-task-a", "initialNodeId", "photo",
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

    @Test
    public void uploadsWorkflowPhotoEvidenceAndAcceptsOnlyARealAssetUuid() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String assignmentId = "33333333-3333-4333-8333-333333333333";
        String assetId = "77777777-7777-4777-8777-777777777777";
        connections.enqueue(201, "{\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"synced\",\"byteSize\":3,\"sha256\":\""
                + "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
                + "\"}");
        WorkflowDeviceHttpClient client = client(configuration(), connections);

        String uploadedAssetId = client.uploadEvidence(
                assignmentId,
                EXECUTION_ID,
                "workflow-photo-local-1",
                "photo-a",
                "nameplate",
                "photo",
                "image/jpeg",
                0,
                new byte[]{1, 2, 3},
                "2026-08-01T02:00:00.000Z");

        assertEquals(assetId, uploadedAssetId);
        assertTrue(connections.opened.get(0).endsWith("/device-sync/workflows/evidence"));
        JSONObject body = new JSONObject(connections.used.get(0).requestText());
        assertEquals("AQID", body.getString("dataBase64"));
        assertEquals(3, body.getInt("byteSize"));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                body.getString("sha256"));
        assertFalse(connections.used.get(0).requestText().contains("task-evidence"));
        assertEquals("Bearer access-a",
                connections.used.get(0).getRequestProperty("Authorization"));
    }

    @Test
    public void uploadsWorkflowVideoWithItsVerifiedDurationAndMediaContract() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String assetId = "77777777-7777-4777-8777-777777777777";
        connections.enqueue(201, "{\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"synced\",\"byteSize\":4,\"durationSeconds\":12,\"sha256\":\""
                + "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
                + "\"}");
        WorkflowDeviceHttpClient client = client(configuration(), connections);

        String uploadedAssetId = client.uploadEvidence(
                "33333333-3333-4333-8333-333333333333",
                EXECUTION_ID,
                "workflow-video-local-1",
                "video-a",
                "control-panel",
                "video",
                "video/mp4",
                12,
                new byte[]{1, 2, 3, 4},
                "2026-08-02T02:00:00.000Z");

        assertEquals(assetId, uploadedAssetId);
        JSONObject body = new JSONObject(connections.used.get(0).requestText());
        assertEquals("video", body.getString("kind"));
        assertEquals("video/mp4", body.getString("contentType"));
        assertEquals(12, body.getInt("durationSeconds"));
        assertEquals("AQIDBA==", body.getString("dataBase64"));
    }

    @Test
    public void resumesPersistedEvidenceThroughSessionChunksAndComplete() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String uploadId = "88888888-8888-4888-8888-888888888888";
        String assetId = "77777777-7777-4777-8777-777777777777";
        String digest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
        connections.enqueue(201, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"uploading\",\"byteSize\":3"
                + ",\"sha256\":\"" + digest
                + "\",\"chunkSize\":3,\"chunkCount\":1,\"receivedChunks\":[]"
                + ",\"nextChunkIndex\":0}");
        connections.enqueue(200, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"uploading\",\"byteSize\":3"
                + ",\"sha256\":\"" + digest
                + "\",\"chunkSize\":3,\"chunkCount\":1,\"receivedChunks\":[0]"
                + ",\"nextChunkIndex\":1}");
        connections.enqueue(201, "{\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"synced\",\"byteSize\":3"
                + ",\"sha256\":\"" + digest + "\"}");
        File file = Files.createTempFile("workflow-evidence", ".jpg").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            String uploaded = client(configuration(), connections).uploadResumable(
                    new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                            "33333333-3333-4333-8333-333333333333",
                            EXECUTION_ID,
                            "local-resumable",
                            "photo-a",
                            "nameplate",
                            WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO,
                            "task-evidence/photo.jpg",
                            0),
                    file,
                    "2026-08-02T02:00:00.000Z");
            assertEquals(assetId, uploaded);
            assertTrue(connections.opened.get(0).endsWith("/evidence/session"));
            assertTrue(connections.opened.get(1).endsWith("/evidence/chunk"));
            assertTrue(connections.opened.get(2).endsWith("/evidence/complete"));
            assertEquals("AQID", new JSONObject(connections.used.get(1).requestText())
                    .getString("dataBase64"));
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void cancellationAfterSessionCreationCallsCancelWithoutUploadingChunks() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String uploadId = "88888888-8888-4888-8888-888888888888";
        String assetId = "77777777-7777-4777-8777-777777777777";
        String digest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
        WorkflowDeviceHttpClient[] holder = new WorkflowDeviceHttpClient[1];
        connections.enqueue(201, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"uploading\",\"byteSize\":3"
                + ",\"sha256\":\"" + digest
                + "\",\"chunkSize\":3,\"chunkCount\":1,\"receivedChunks\":[]"
                + ",\"nextChunkIndex\":0}",
                () -> holder[0].cancelActiveUpload());
        connections.enqueue(200, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"cancelled\",\"receivedChunks\":[]}");
        holder[0] = client(configuration(), connections);
        File file = Files.createTempFile("workflow-cancel-evidence", ".jpg").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            try {
                holder[0].uploadResumable(
                        new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                                "33333333-3333-4333-8333-333333333333",
                                EXECUTION_ID,
                                "local-cancel",
                                "photo-a",
                                "nameplate",
                                WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO,
                                "task-evidence/cancel.jpg",
                                0),
                        file,
                        "2026-08-05T15:00:00.000Z");
                throw new AssertionError("expected upload cancellation");
            } catch (WorkflowEvidenceUploadCoordinator.UploadCancelledException expected) {
                assertTrue(expected.getMessage().contains("cancelled"));
            }
            assertEquals(2, connections.opened.size());
            assertTrue(connections.opened.get(0).endsWith("/evidence/session"));
            assertTrue(connections.opened.get(1).endsWith("/evidence/cancel"));
            JSONObject body = new JSONObject(connections.used.get(1).requestText());
            assertEquals(uploadId, body.getString("uploadId"));
            assertEquals(assetId, body.getString("assetId"));
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void cancellationRequestedBeforeUploadStartsSurvivesSessionCreation() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String uploadId = "88888888-8888-4888-8888-888888888888";
        String assetId = "77777777-7777-4777-8777-777777777777";
        String digest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
        connections.enqueue(201, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"uploading\",\"byteSize\":3"
                + ",\"sha256\":\"" + digest
                + "\",\"chunkSize\":3,\"chunkCount\":1,\"receivedChunks\":[]"
                + ",\"nextChunkIndex\":0}");
        connections.enqueue(200, "{\"uploadId\":\"" + uploadId
                + "\",\"assetId\":\"" + assetId
                + "\",\"uploadStatus\":\"cancelled\",\"receivedChunks\":[]}");
        WorkflowDeviceHttpClient client = client(configuration(), connections);
        client.cancelActiveUpload();
        File file = Files.createTempFile("workflow-pre-cancel-evidence", ".jpg").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            try {
                client.uploadResumable(
                        new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                                "33333333-3333-4333-8333-333333333333",
                                EXECUTION_ID,
                                "local-pre-cancel",
                                "photo-a",
                                "nameplate",
                                WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO,
                                "task-evidence/pre-cancel.jpg",
                                0),
                        file,
                        "2026-08-05T15:00:00.000Z");
                throw new AssertionError("expected upload cancellation");
            } catch (WorkflowEvidenceUploadCoordinator.UploadCancelledException expected) {
                assertTrue(expected.getMessage().contains("cancelled"));
            }
            assertEquals(2, connections.opened.size());
            assertTrue(connections.opened.get(0).endsWith("/evidence/session"));
            assertTrue(connections.opened.get(1).endsWith("/evidence/cancel"));
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test(expected = IOException.class)
    public void rejectsExecutionStartWhenServerReturnsADifferentExecutionId() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(201, "{\"executionId\":\"" + OTHER_EXECUTION_ID + "\"}");

        client(configuration(), connections).send(workflowEvent(
                "assignment-a:start",
                "workflow_execution_start",
                json("executionId", EXECUTION_ID,
                        "assignmentId", "assignment-a", "projectId", "project-a",
                        "localTaskId", "workflow-task-a", "initialNodeId", "photo",
                        "runtimeSnapshot", new JSONObject(),
                        "idempotencyKey", "assignment-a:start"),
                1001L));
    }

    @Test(expected = IOException.class)
    public void rejectsFractionalAssignmentSequencesInsteadOfTruncatingThem() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"assignmentId\":\"assignment-a\","
                + "\"workOrderId\":\"order-a\",\"projectId\":\"project-a\","
                + "\"workflowVersionId\":\"version-a\",\"mode\":\"required\","
                + "\"status\":\"queued\",\"deliverySequence\":7.5,"
                + "\"assignedAt\":\"2026-08-01T00:00:00Z\"}],\"nextSequence\":7.5}");

        client(configuration(), connections).listAssignments(3L, 50);
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

    private static void assertWorkOrderSummary(WorkflowDeviceHttpClient.Assignment assignment)
            throws Exception {
        Object summary = assignment.getClass().getMethod("workOrder").invoke(assignment);
        assertEquals("MVS-20260801-001",
                summary.getClass().getMethod("externalWorkOrderId").invoke(summary));
        assertEquals("冷水机组控制器故障",
                summary.getClass().getMethod("title").invoke(summary));
        assertEquals("HF-CH-01", summary.getClass().getMethod("assetModel").invoke(summary));
        assertEquals("high", summary.getClass().getMethod("priority").invoke(summary));
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body) {
            enqueue(status, body, null);
        }

        void enqueue(int status, String body, Runnable onResponse) {
            responses.add(new Response(status, body, onResponse));
        }

        @Override
        public HttpURLConnection open(String endpoint) throws IOException {
            Response response = responses.remove();
            CapturingConnection connection = new CapturingConnection(
                    new URL(endpoint), response.status, response.body, response.onResponse);
            opened.add(endpoint);
            used.add(connection);
            return connection;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final Runnable onResponse;

        private Response(int status, String body, Runnable onResponse) {
            this.status = status;
            this.body = body;
            this.onResponse = onResponse;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final Runnable onResponse;
        private String method = "";
        private boolean responseCallbackInvoked;

        private CapturingConnection(URL url, int status, String body, Runnable onResponse) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
            this.onResponse = onResponse;
        }

        @Override public void setRequestMethod(String method) { this.method = method; }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public int getResponseCode() {
            if (!responseCallbackInvoked && onResponse != null) {
                responseCallbackInvoked = true;
                onResponse.run();
            }
            return status;
        }
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
