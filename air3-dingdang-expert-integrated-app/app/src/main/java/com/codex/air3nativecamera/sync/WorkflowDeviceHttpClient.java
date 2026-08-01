package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WorkflowDeviceHttpClient implements TaskSyncClient.Transport, WorkflowAssignmentGateway {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> ASSIGNMENT_MODES = set("required", "optional", "none");
    private static final Set<String> ASSIGNMENT_STATUSES = set(
            "queued", "notified", "delivered", "verified", "ready",
            "active", "completed", "failed", "revoked");

    public static final class Assignment {
        private final String assignmentId;
        private final String workOrderId;
        private final String projectId;
        private final String workflowVersionId;
        private final String mode;
        private final String status;
        private final long deliverySequence;
        private final String assignedAt;

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
            this.assignmentId = assignmentId;
            this.workOrderId = workOrderId;
            this.projectId = projectId;
            this.workflowVersionId = workflowVersionId;
            this.mode = mode;
            this.status = status;
            this.deliverySequence = deliverySequence;
            this.assignedAt = assignedAt;
        }

        public String assignmentId() { return assignmentId; }
        public String workOrderId() { return workOrderId; }
        public String projectId() { return projectId; }
        public String workflowVersionId() { return workflowVersionId; }
        public String mode() { return mode; }
        public String status() { return status; }
        public long deliverySequence() { return deliverySequence; }
        public String assignedAt() { return assignedAt; }
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
                endpoint = configuration.workflowEndpoint() + "/executions";
                break;
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
                assignedAt);
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

    private static Set<String> set(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
