package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkflowDeviceHttpClient implements
        TaskSyncClient.Transport,
        WorkflowAssignmentGateway,
        WorkflowEvidenceUploadCoordinator.CancellableResumableTransport {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_WORKFLOW_PHOTO_BYTES = 5 * 1024 * 1024;
    private static final int MAX_WORKFLOW_VIDEO_BYTES = 8 * 1024 * 1024;
    private static final int WORKFLOW_CHUNK_SIZE = 256 * 1024;
    private static final int MAX_WORKFLOW_CHUNK_SIZE = 1024 * 1024;
    private static final Set<String> ASSIGNMENT_MODES = set("required", "optional", "none");
    private static final Set<String> ASSIGNMENT_STATUSES = set(
            "queued", "notified", "delivered", "verified", "ready",
            "active", "completed", "failed", "revoked");
    private static final Set<String> WORK_ORDER_STATUSES = set(
            "received", "accepted", "in_progress", "completed", "closed", "cancelled");

    public static final class WorkOrderSummary {
        private final String externalWorkOrderId;
        private final String title;
        private final String description;
        private final String customerId;
        private final String workOrderType;
        private final String assetId;
        private final String assetCategory;
        private final String assetBrand;
        private final String assetModel;
        private final String priority;
        private final String riskLevel;
        private final String status;
        private final String dueAt;
        private final String receivedAt;

        WorkOrderSummary(
                String externalWorkOrderId,
                String title,
                String description,
                String customerId,
                String workOrderType,
                String assetId,
                String assetCategory,
                String assetBrand,
                String assetModel,
                String priority,
                String riskLevel,
                String status,
                String dueAt,
                String receivedAt
        ) {
            this.externalWorkOrderId = clean(externalWorkOrderId);
            this.title = clean(title);
            this.description = clean(description);
            this.customerId = clean(customerId);
            this.workOrderType = clean(workOrderType);
            this.assetId = clean(assetId);
            this.assetCategory = clean(assetCategory);
            this.assetBrand = clean(assetBrand);
            this.assetModel = clean(assetModel);
            this.priority = clean(priority);
            this.riskLevel = clean(riskLevel);
            this.status = clean(status);
            this.dueAt = clean(dueAt);
            this.receivedAt = clean(receivedAt);
        }

        static WorkOrderSummary unavailable() {
            return new WorkOrderSummary("", "", "", "", "", "", "", "", "",
                    "", "", "", "", "");
        }

        public boolean available() { return !title.isEmpty(); }
        public String externalWorkOrderId() { return externalWorkOrderId; }
        public String title() { return title; }
        public String description() { return description; }
        public String customerId() { return customerId; }
        public String workOrderType() { return workOrderType; }
        public String assetId() { return assetId; }
        public String assetCategory() { return assetCategory; }
        public String assetBrand() { return assetBrand; }
        public String assetModel() { return assetModel; }
        public String priority() { return priority; }
        public String riskLevel() { return riskLevel; }
        public String status() { return status; }
        public String dueAt() { return dueAt; }
        public String receivedAt() { return receivedAt; }

        JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("externalWorkOrderId", nullable(externalWorkOrderId))
                        .put("title", title)
                        .put("description", nullable(description))
                        .put("customerId", nullable(customerId))
                        .put("workOrderType", nullable(workOrderType))
                        .put("assetId", nullable(assetId))
                        .put("assetCategory", nullable(assetCategory))
                        .put("assetBrand", nullable(assetBrand))
                        .put("assetModel", nullable(assetModel))
                        .put("priority", nullable(priority))
                        .put("riskLevel", nullable(riskLevel))
                        .put("status", status)
                        .put("dueAt", nullable(dueAt))
                        .put("receivedAt", receivedAt);
            } catch (JSONException exception) {
                throw new IllegalStateException("unable to serialize work order summary", exception);
            }
        }

        static WorkOrderSummary fromJson(JSONObject value) {
            if (value == null) return unavailable();
            WorkOrderSummary parsed = parseWorkOrder(value);
            if (parsed == null) {
                throw new IllegalArgumentException("work order summary is invalid");
            }
            return parsed;
        }

        private static Object nullable(String value) {
            return value.isEmpty() ? JSONObject.NULL : value;
        }
    }

    public static final class Assignment {
        private final String assignmentId;
        private final String workOrderId;
        private final String projectId;
        private final String workflowVersionId;
        private final String mode;
        private final String status;
        private final long deliverySequence;
        private final String assignedAt;
        private final WorkOrderSummary workOrder;

        Assignment(
                String assignmentId,
                String workOrderId,
                String projectId,
                String workflowVersionId,
                String mode,
                String status,
                long deliverySequence,
                String assignedAt
        ) {
            this(assignmentId, workOrderId, projectId, workflowVersionId, mode, status,
                    deliverySequence, assignedAt, WorkOrderSummary.unavailable());
        }

        private Assignment(
                String assignmentId,
                String workOrderId,
                String projectId,
                String workflowVersionId,
                String mode,
                String status,
                long deliverySequence,
                String assignedAt,
                WorkOrderSummary workOrder
        ) {
            this.assignmentId = assignmentId;
            this.workOrderId = workOrderId;
            this.projectId = projectId;
            this.workflowVersionId = workflowVersionId;
            this.mode = mode;
            this.status = status;
            this.deliverySequence = deliverySequence;
            this.assignedAt = assignedAt;
            this.workOrder = workOrder;
        }

        public String assignmentId() { return assignmentId; }
        public String workOrderId() { return workOrderId; }
        public String projectId() { return projectId; }
        public String workflowVersionId() { return workflowVersionId; }
        public String mode() { return mode; }
        public String status() { return status; }
        public long deliverySequence() { return deliverySequence; }
        public String assignedAt() { return assignedAt; }
        public WorkOrderSummary workOrder() { return workOrder; }
    }

    public static final class AssignmentPage {
        private final List<Assignment> items;
        private final long nextSequence;

        AssignmentPage(List<Assignment> items, long nextSequence) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextSequence = nextSequence;
        }

        public List<Assignment> items() { return items; }
        public long nextSequence() { return nextSequence; }
    }

    private final DeviceSyncConfiguration configuration;
    private final DeviceAccessTokenProvider tokenProvider;
    private final int appVersionCode;
    private final int schemaVersion;
    private final String capabilityHeader;
    private final HttpConnectionFactory connectionFactory;
    private final AtomicBoolean evidenceUploadCancellationRequested = new AtomicBoolean();

    public WorkflowDeviceHttpClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            int appVersionCode,
            int schemaVersion,
            List<String> capabilities
    ) {
        this(configuration, tokenProvider, appVersionCode, schemaVersion, capabilities,
                HttpConnectionFactory.DEFAULT);
    }

    WorkflowDeviceHttpClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            int appVersionCode,
            int schemaVersion,
            List<String> capabilities,
            HttpConnectionFactory connectionFactory
    ) {
        this.configuration = configuration;
        this.tokenProvider = tokenProvider;
        this.appVersionCode = appVersionCode;
        this.schemaVersion = schemaVersion;
        this.capabilityHeader = capabilityHeader(capabilities);
        this.connectionFactory = connectionFactory;
        if (configuration == null
                || tokenProvider == null
                || connectionFactory == null
                || appVersionCode < 9000
                || schemaVersion < 1
                || this.capabilityHeader.isEmpty()) {
            throw new IllegalArgumentException("workflow device client configuration is invalid");
        }
    }

    @Override
    public AssignmentPage listAssignments(long afterSequence, int limit) throws IOException {
        if (afterSequence < 0L || afterSequence > MAX_SAFE_INTEGER || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("workflow assignment cursor is invalid");
        }
        String endpoint = configuration.workflowEndpoint()
                + "/assignments?afterSequence=" + afterSequence + "&limit=" + limit;
        JSONObject response = request("GET", endpoint, null, false);
        JSONArray values = response.optJSONArray("items");
        Object rawNextSequence = response.opt("nextSequence");
        long nextSequence = exactNonNegativeLong(rawNextSequence);
        if (values == null || nextSequence < 0L) {
            throw new IOException("workflow_assignment_response_invalid");
        }
        if (nextSequence < afterSequence || values.length() > limit) {
            throw new IOException("workflow_assignment_response_invalid");
        }
        List<Assignment> items = new ArrayList<>();
        long previous = afterSequence;
        for (int index = 0; index < values.length(); index += 1) {
            JSONObject value = values.optJSONObject(index);
            Assignment item = parseAssignment(value);
            if (item == null || item.deliverySequence() <= previous
                    || item.deliverySequence() > nextSequence) {
                throw new IOException("workflow_assignment_response_invalid");
            }
            items.add(item);
            previous = item.deliverySequence();
        }
        return new AssignmentPage(items, nextSequence);
    }

    @Override
    public JSONObject fetchPackage(String assignmentId) throws IOException {
        String id = requiredIdentifier(assignmentId, "workflow assignment identifier is invalid");
        JSONObject response = request(
                "GET",
                configuration.workflowEndpoint() + "/assignments/" + id + "/package",
                null,
                true);
        if (!id.equals(clean(response.optString("assignmentId", "")))
                || response.optJSONObject("executionPackage") == null) {
            throw new IOException("workflow_package_response_invalid");
        }
        return copy(response);
    }

    @Override
    public void send(TaskSyncEvent event) throws IOException {
        if (event == null) throw new IllegalArgumentException("workflow event is required");
        JSONObject body;
        try {
            body = new JSONObject(event.payload());
        } catch (JSONException exception) {
            throw new IOException("workflow_event_payload_invalid", exception);
        }
        if (!event.idempotencyKey().equals(clean(body.optString("idempotencyKey", "")))) {
            throw new IOException("workflow_event_idempotency_mismatch");
        }
        String endpoint;
        switch (event.eventType()) {
            case "workflow_assignment_status":
                String assignmentId = requiredIdentifier(
                        body.optString("assignmentId", ""),
                        "workflow assignment identifier is invalid");
                body.remove("assignmentId");
                endpoint = configuration.workflowEndpoint()
                        + "/assignments/" + assignmentId + "/status";
                break;
            case "workflow_execution_start":
                String requestedExecutionId = requiredIdentifier(
                        body.optString("executionId", ""),
                        "workflow execution identifier is invalid");
                endpoint = configuration.workflowEndpoint() + "/executions";
                JSONObject execution = request("POST", endpoint, body, false);
                if (!requestedExecutionId.equals(clean(execution.optString("executionId", "")))) {
                    throw new IOException("workflow_execution_response_mismatch");
                }
                return;
            case "workflow_step_event":
                String executionId = requiredIdentifier(
                        body.optString("executionId", ""),
                        "workflow execution identifier is invalid");
                body.remove("executionId");
                endpoint = configuration.workflowEndpoint()
                        + "/executions/" + executionId + "/steps";
                break;
            default:
                throw new IllegalArgumentException("workflow event type is unsupported");
        }
        request("POST", endpoint, body, false);
    }

    public String uploadEvidence(
            String assignmentId,
            String executionId,
            String localEvidenceId,
            String nodeId,
            String evidenceKey,
            String kind,
            String contentType,
            int durationSeconds,
            byte[] bytes,
            String capturedAt
    ) throws IOException {
        String assignment = requiredUuid(
                assignmentId, "workflow assignment identifier is invalid");
        String execution = requiredUuid(
                executionId, "workflow execution identifier is invalid");
        String localId = requiredShortIdentifier(
                localEvidenceId, "workflow local evidence identifier is invalid");
        String node = requiredShortIdentifier(
                nodeId, "workflow node identifier is invalid");
        String key = requiredShortIdentifier(
                evidenceKey, "workflow evidence key is invalid");
        String mediaKind = clean(kind);
        String mediaContentType = clean(contentType);
        boolean photo = "photo".equals(mediaKind)
                && "image/jpeg".equals(mediaContentType)
                && durationSeconds == 0;
        boolean video = "video".equals(mediaKind)
                && "video/mp4".equals(mediaContentType)
                && durationSeconds >= 1 && durationSeconds <= 15;
        int maximumBytes = video ? MAX_WORKFLOW_VIDEO_BYTES : MAX_WORKFLOW_PHOTO_BYTES;
        String captured = clean(capturedAt);
        if ((!photo && !video)
                || bytes == null || bytes.length < 1 || bytes.length > maximumBytes
                || captured.isEmpty() || captured.length() > 100) {
            throw new IllegalArgumentException("workflow evidence payload is invalid");
        }
        String digest = sha256(bytes);
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("assignmentId", assignment)
                    .put("executionId", execution)
                    .put("localEvidenceId", localId)
                    .put("nodeId", node)
                    .put("evidenceKey", key)
                    .put("kind", mediaKind)
                    .put("contentType", mediaContentType)
                    .put("durationSeconds", durationSeconds)
                    .put("byteSize", bytes.length)
                    .put("sha256", digest)
                    .put("dataBase64", Base64.getEncoder().encodeToString(bytes))
                    .put("capturedAt", captured);
        } catch (JSONException exception) {
            throw new IOException("workflow_evidence_payload_invalid", exception);
        }
        JSONObject response = request(
                "POST",
                configuration.workflowEndpoint() + "/evidence",
                body,
                false);
        String assetId = clean(response.optString("assetId", ""));
        String uploadStatus = clean(response.optString("uploadStatus", ""));
        String remoteDigest = clean(response.optString("sha256", ""));
        long byteSize = exactNonNegativeLong(response.opt("byteSize"));
        long remoteDuration = response.has("durationSeconds")
                ? exactNonNegativeLong(response.opt("durationSeconds"))
                : (photo ? 0L : -1L);
        if (!validUuid(assetId) || !"synced".equals(uploadStatus)
                || byteSize != bytes.length || remoteDuration != durationSeconds
                || !digest.equals(remoteDigest)) {
            throw new IOException("workflow_evidence_response_invalid");
        }
        return assetId;
    }

    @Override
    public String upload(
            WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
            byte[] bytes,
            String capturedAt
    ) throws IOException {
        if (evidence == null) {
            throw new IllegalArgumentException("workflow evidence is required");
        }
        return uploadEvidence(
                evidence.assignmentId(),
                evidence.executionId(),
                evidence.localEvidenceId(),
                evidence.nodeId(),
                evidence.evidenceKey(),
                evidence.kind(),
                evidence.contentType(),
                evidence.durationSeconds(),
                bytes,
                capturedAt);
    }

    @Override
    public String uploadResumable(
            WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
            File file,
            String capturedAt
    ) throws IOException {
        if (evidence == null || file == null || !file.isFile()) {
            throw new IllegalArgumentException("workflow evidence file is invalid");
        }
        long length = file.length();
        if (length < 1L || length > evidence.maximumBytes() || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("workflow evidence file size is invalid");
        }
        String captured = clean(capturedAt);
        if (captured.isEmpty() || captured.length() > 100) {
            throw new IllegalArgumentException("workflow evidence timestamp is invalid");
        }
        int byteSize = (int) length;
        String digest = sha256File(file, byteSize);
        int chunkCount = (byteSize + WORKFLOW_CHUNK_SIZE - 1) / WORKFLOW_CHUNK_SIZE;
        JSONObject sessionBody;
        try {
            sessionBody = new JSONObject()
                    .put("assignmentId", requiredUuid(
                            evidence.assignmentId(), "workflow assignment identifier is invalid"))
                    .put("executionId", requiredUuid(
                            evidence.executionId(), "workflow execution identifier is invalid"))
                    .put("localEvidenceId", requiredShortIdentifier(
                            evidence.localEvidenceId(), "workflow local evidence identifier is invalid"))
                    .put("nodeId", requiredShortIdentifier(
                            evidence.nodeId(), "workflow node identifier is invalid"))
                    .put("evidenceKey", requiredShortIdentifier(
                            evidence.evidenceKey(), "workflow evidence key is invalid"))
                    .put("kind", evidence.kind())
                    .put("contentType", evidence.contentType())
                    .put("durationSeconds", evidence.durationSeconds())
                    .put("byteSize", byteSize)
                    .put("sha256", digest)
                    .put("capturedAt", captured)
                    .put("chunkSize", WORKFLOW_CHUNK_SIZE)
                    .put("chunkCount", chunkCount);
        } catch (JSONException exception) {
            throw new IOException("workflow_evidence_session_payload_invalid", exception);
        }
        JSONObject session = request(
                "POST",
                configuration.workflowEndpoint() + "/evidence/session",
                sessionBody,
                false);
        String uploadId = requiredUuid(
                clean(session.optString("uploadId", "")),
                "workflow evidence upload identifier is invalid");
        String assetId = requiredUuid(
                clean(session.optString("assetId", "")),
                "workflow evidence asset identifier is invalid");
        cancelIfRequested(uploadId, assetId);
        int remoteChunkSize = exactPositiveInt(session.opt("chunkSize"));
        int remoteChunkCount = exactPositiveInt(session.opt("chunkCount"));
        if (remoteChunkSize > MAX_WORKFLOW_CHUNK_SIZE
                || remoteChunkCount != (byteSize + remoteChunkSize - 1) / remoteChunkSize) {
            throw new IOException("workflow_evidence_session_response_invalid");
        }
        String uploadStatus = clean(session.optString("uploadStatus", ""));
        if ("synced".equals(uploadStatus)) {
            validateResumableEvidenceResponse(session, assetId, digest, byteSize,
                    evidence.durationSeconds());
            return assetId;
        }
        if (!"uploading".equals(uploadStatus)) {
            throw new IOException("workflow_evidence_session_response_invalid");
        }
        Set<Integer> received = parseReceivedChunks(
                session.optJSONArray("receivedChunks"), remoteChunkCount);
        for (int index = 0; index < remoteChunkCount; index += 1) {
            cancelIfRequested(uploadId, assetId);
            if (received.contains(index)) continue;
            byte[] chunk = readChunk(file, (long) index * remoteChunkSize, remoteChunkSize);
            String chunkDigest = sha256(chunk);
            JSONObject chunkBody;
            try {
                chunkBody = new JSONObject()
                        .put("uploadId", uploadId)
                        .put("chunkIndex", index)
                        .put("chunkCount", remoteChunkCount)
                        .put("chunkByteSize", chunk.length)
                        .put("chunkSha256", chunkDigest)
                        .put("dataBase64", Base64.getEncoder().encodeToString(chunk));
            } catch (JSONException exception) {
                throw new IOException("workflow_evidence_chunk_payload_invalid", exception);
            }
            JSONObject chunkResponse = request(
                    "POST",
                    configuration.workflowEndpoint() + "/evidence/chunk",
                    chunkBody,
                    false);
            if (!uploadId.equals(clean(chunkResponse.optString("uploadId", "")))) {
                throw new IOException("workflow_evidence_chunk_response_invalid");
            }
            cancelIfRequested(uploadId, assetId);
        }
        cancelIfRequested(uploadId, assetId);
        JSONObject completeBody;
        try {
            completeBody = new JSONObject()
                    .put("uploadId", uploadId)
                    .put("chunkCount", remoteChunkCount)
                    .put("sha256", digest);
        } catch (JSONException exception) {
            throw new IOException("workflow_evidence_complete_payload_invalid", exception);
        }
        JSONObject completed = request(
                "POST",
                configuration.workflowEndpoint() + "/evidence/complete",
                completeBody,
                false);
        validateResumableEvidenceResponse(
                completed, assetId, digest, byteSize, evidence.durationSeconds());
        return assetId;
    }

    @Override
    public void cancelActiveUpload() {
        evidenceUploadCancellationRequested.set(true);
    }

    public void cancelEvidenceUpload(String uploadId, String assetId) throws IOException {
        String acceptedUploadId = requiredUuid(
                uploadId, "workflow evidence upload identifier is invalid");
        String acceptedAssetId = requiredUuid(
                assetId, "workflow evidence asset identifier is invalid");
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("uploadId", acceptedUploadId)
                    .put("assetId", acceptedAssetId);
        } catch (JSONException exception) {
            throw new IOException("workflow_evidence_cancel_payload_invalid", exception);
        }
        JSONObject response = request(
                "POST",
                configuration.workflowEndpoint() + "/evidence/cancel",
                body,
                false);
        if (!acceptedUploadId.equals(clean(response.optString("uploadId", "")))
                || !acceptedAssetId.equals(clean(response.optString("assetId", "")))
                || !"cancelled".equals(clean(response.optString("uploadStatus", "")))) {
            throw new IOException("workflow_evidence_cancel_response_invalid");
        }
    }

    private void cancelIfRequested(String uploadId, String assetId) throws IOException {
        if (!evidenceUploadCancellationRequested.compareAndSet(true, false)) return;
        try {
            cancelEvidenceUpload(uploadId, assetId);
        } catch (IOException exception) {
            throw new WorkflowEvidenceUploadCoordinator.UploadCancelledException(exception);
        }
        throw new WorkflowEvidenceUploadCoordinator.UploadCancelledException();
    }

    private static void validateResumableEvidenceResponse(
            JSONObject response,
            String assetId,
            String digest,
            int byteSize,
            int durationSeconds
    ) throws IOException {
        String remoteAssetId = clean(response.optString("assetId", ""));
        String uploadStatus = clean(response.optString("uploadStatus", ""));
        String remoteDigest = clean(response.optString("sha256", ""));
        long remoteByteSize = exactNonNegativeLong(response.opt("byteSize"));
        long remoteDuration = response.has("durationSeconds")
                ? exactNonNegativeLong(response.opt("durationSeconds"))
                : durationSeconds;
        if (!assetId.equals(remoteAssetId) || !validUuid(remoteAssetId)
                || !"synced".equals(uploadStatus)
                || remoteByteSize != byteSize
                || remoteDuration != durationSeconds
                || !digest.equals(remoteDigest)) {
            throw new IOException("workflow_evidence_response_invalid");
        }
    }

    private static Set<Integer> parseReceivedChunks(JSONArray values, int chunkCount)
            throws IOException {
        if (values == null || values.length() > chunkCount) {
            throw new IOException("workflow_evidence_session_response_invalid");
        }
        Set<Integer> received = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index += 1) {
            int chunk = exactPositiveOrZeroInt(values.opt(index));
            if (chunk >= chunkCount || !received.add(chunk)) {
                throw new IOException("workflow_evidence_session_response_invalid");
            }
        }
        return received;
    }

    private static byte[] readChunk(File file, long offset, int maximumBytes) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (offset < 0L || offset >= input.length()) {
                throw new IOException("workflow evidence chunk offset is invalid");
            }
            input.seek(offset);
            int remaining = (int) Math.min(maximumBytes, input.length() - offset);
            byte[] bytes = new byte[remaining];
            int cursor = 0;
            while (cursor < bytes.length) {
                int count = input.read(bytes, cursor, bytes.length - cursor);
                if (count < 0) break;
                cursor += count;
            }
            if (cursor != bytes.length) {
                throw new IOException("workflow evidence file changed during upload");
            }
            return bytes;
        }
    }

    private static String sha256File(File file, int expectedLength) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IOException("workflow evidence digest unavailable", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        try (InputStream input = new java.io.FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > expectedLength) {
                    throw new IOException("workflow evidence file changed during upload");
                }
                digest.update(buffer, 0, count);
            }
        }
        if (total != expectedLength) {
            throw new IOException("workflow evidence file changed during upload");
        }
        return toHex(digest.digest());
    }

    private JSONObject request(
            String method,
            String endpoint,
            JSONObject body,
            boolean declareCapabilities
    ) throws IOException {
        HttpURLConnection connection = connectionFactory.open(endpoint);
        OutputStream output = null;
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Authorization", "Bearer " + tokenProvider.accessToken());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (declareCapabilities) {
                connection.setRequestProperty("X-App-Version-Code", String.valueOf(appVersionCode));
                connection.setRequestProperty("X-Workflow-Schema-Version", String.valueOf(schemaVersion));
                connection.setRequestProperty("X-Workflow-Capabilities", capabilityHeader);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                output = connection.getOutputStream();
                output.write(bytes);
                output.close();
                output = null;
            }
            int status = connection.getResponseCode();
            String responseBody = readAll(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            JSONObject response = parseObject(responseBody);
            if (status < 200 || status >= 300) {
                String code = safeErrorCode(response.optString("error", "request_failed"));
                throw new IOException("workflow_http_" + status + ":" + code);
            }
            return response;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("workflow_response_invalid", exception);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            connection.disconnect();
        }
    }

    private static Assignment parseAssignment(JSONObject value) {
        if (value == null) return null;
        String assignmentId = clean(value.optString("assignmentId", ""));
        String workOrderId = clean(value.optString("workOrderId", ""));
        String projectId = nullableText(value, "projectId");
        String workflowVersionId = nullableText(value, "workflowVersionId");
        String mode = clean(value.optString("mode", ""));
        String status = clean(value.optString("status", ""));
        String assignedAt = clean(value.optString("assignedAt", ""));
        WorkOrderSummary workOrder = parseWorkOrder(value.optJSONObject("workOrder"));
        Object sequence = value.opt("deliverySequence");
        long deliverySequence = exactNonNegativeLong(sequence);
        if (!validIdentifier(assignmentId)
                || !validIdentifier(workOrderId)
                || (!projectId.isEmpty() && !validIdentifier(projectId))
                || (!workflowVersionId.isEmpty() && !validIdentifier(workflowVersionId))
                || !ASSIGNMENT_MODES.contains(mode)
                || !ASSIGNMENT_STATUSES.contains(status)
                || deliverySequence < 1L
                || assignedAt.isEmpty()
                || workOrder == null
                || ("none".equals(mode) && !workflowVersionId.isEmpty())
                || (!"none".equals(mode) && workflowVersionId.isEmpty())) {
            return null;
        }
        return new Assignment(
                assignmentId,
                workOrderId,
                projectId,
                workflowVersionId,
                mode,
                status,
                deliverySequence,
                assignedAt,
                workOrder);
    }

    private static WorkOrderSummary parseWorkOrder(JSONObject value) {
        if (value == null) return null;
        String title = boundedText(value, "title", 240, false);
        String status = boundedText(value, "status", 40, false);
        String receivedAt = boundedText(value, "receivedAt", 100, false);
        if (title == null || status == null || receivedAt == null
                || !WORK_ORDER_STATUSES.contains(status)) {
            return null;
        }
        String externalId = boundedText(value, "externalWorkOrderId", 200, true);
        String description = boundedText(value, "description", 2000, true);
        String customerId = boundedText(value, "customerId", 160, true);
        String workOrderType = boundedText(value, "workOrderType", 160, true);
        String assetId = boundedText(value, "assetId", 160, true);
        String assetCategory = boundedText(value, "assetCategory", 160, true);
        String assetBrand = boundedText(value, "assetBrand", 160, true);
        String assetModel = boundedText(value, "assetModel", 160, true);
        String priority = boundedText(value, "priority", 80, true);
        String riskLevel = boundedText(value, "riskLevel", 80, true);
        String dueAt = boundedText(value, "dueAt", 100, true);
        if (externalId == null || description == null || customerId == null
                || workOrderType == null || assetId == null || assetCategory == null
                || assetBrand == null || assetModel == null || priority == null
                || riskLevel == null || dueAt == null) {
            return null;
        }
        return new WorkOrderSummary(
                externalId, title, description, customerId, workOrderType, assetId,
                assetCategory, assetBrand, assetModel, priority, riskLevel, status,
                dueAt, receivedAt);
    }

    private static String boundedText(
            JSONObject value,
            String key,
            int maxLength,
            boolean optional
    ) {
        Object raw = value.opt(key);
        if (raw == null || raw == JSONObject.NULL) return optional ? "" : null;
        if (!(raw instanceof String)) return null;
        String text = clean((String) raw);
        if (text.length() > maxLength || (!optional && text.isEmpty())) return null;
        for (int index = 0; index < text.length(); index += 1) {
            if (Character.isISOControl(text.charAt(index))) return null;
        }
        return text;
    }

    private static String nullableText(JSONObject value, String key) {
        Object raw = value.opt(key);
        return raw == null || raw == JSONObject.NULL ? "" : clean(String.valueOf(raw));
    }

    private static JSONObject parseObject(String value) throws IOException {
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            throw new IOException("workflow_response_invalid", exception);
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow JSON is invalid", exception);
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("workflow_response_too_large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String capabilityHeader(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty() || capabilities.size() > 100) return "";
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        for (String capability : capabilities) {
            String value = clean(capability);
            if (!value.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$") || !accepted.add(value)) {
                return "";
            }
        }
        StringBuilder header = new StringBuilder();
        for (String value : accepted) {
            if (header.length() > 0) header.append(',');
            header.append(value);
        }
        return header.toString();
    }

    private static String requiredIdentifier(String value, String message) {
        String result = clean(value);
        if (!validIdentifier(result)) throw new IllegalArgumentException(message);
        return result;
    }

    private static String requiredShortIdentifier(String value, String message) {
        String result = clean(value);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static String requiredUuid(String value, String message) {
        String result = clean(value).toLowerCase(java.util.Locale.ROOT);
        if (!validUuid(result)) throw new IllegalArgumentException(message);
        return result;
    }

    private static boolean validUuid(String value) {
        return value != null && value.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (Exception exception) {
            throw new IOException("workflow_evidence_digest_failed", exception);
        }
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$");
    }

    private static String safeErrorCode(String value) {
        String code = clean(value);
        return code.matches("^[a-z0-9_]{1,120}$") ? code : "request_failed";
    }

    private static long exactNonNegativeLong(Object raw) {
        if (!(raw instanceof Number)) return -1L;
        Number number = (Number) raw;
        double decimal = number.doubleValue();
        long integer = number.longValue();
        return Double.isFinite(decimal)
                && decimal == (double) integer
                && integer >= 0L
                && integer <= MAX_SAFE_INTEGER
                ? integer : -1L;
    }

    private static int exactPositiveInt(Object raw) throws IOException {
        long value = exactNonNegativeLong(raw);
        if (value < 1L || value > Integer.MAX_VALUE) {
            throw new IOException("workflow_evidence_session_response_invalid");
        }
        return (int) value;
    }

    private static int exactPositiveOrZeroInt(Object raw) throws IOException {
        long value = exactNonNegativeLong(raw);
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IOException("workflow_evidence_session_response_invalid");
        }
        return (int) value;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static Set<String> set(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
