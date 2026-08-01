package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.sync.TaskSyncEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WorkflowSyncEventFactory {
    private static final Set<String> ASSIGNMENT_STATUSES = set(
            "delivered", "verified", "ready", "active", "completed", "failed");
    private static final Set<String> STEP_STATUSES = set(
            "pending", "active", "draft_saved", "waiting_upload",
            "waiting_server", "completed", "skipped", "failed");

    private WorkflowSyncEventFactory() {
    }

    public static TaskSyncEvent assignmentStatus(
            String projectId,
            String taskId,
            String assignmentId,
            String status,
            String failureStage,
            String failureReason,
            long createdAt,
            String idempotencyKey
    ) {
        String acceptedStatus = required(status, 40, "workflow assignment status is invalid");
        String acceptedFailureReason = optional(failureReason, 1000);
        if (!ASSIGNMENT_STATUSES.contains(acceptedStatus)
                || ("failed".equals(acceptedStatus) && acceptedFailureReason.isEmpty())) {
            throw new IllegalArgumentException("workflow assignment status is invalid");
        }
        JSONObject payload = object(
                "assignmentId", required(assignmentId, 200, "workflow assignment is invalid"),
                "status", acceptedStatus,
                "idempotencyKey", required(idempotencyKey, 200, "workflow idempotency key is invalid"),
                "failureStage", nullable(optional(failureStage, 160)),
                "failureReason", nullable(acceptedFailureReason));
        return event(projectId, taskId, "workflow_assignment_status", payload, createdAt, idempotencyKey);
    }

    public static TaskSyncEvent executionStart(
            String projectId,
            String taskId,
            String assignmentId,
            String initialNodeId,
            WorkflowRuntimeState state,
            long createdAt,
            String idempotencyKey
    ) {
        if (state == null) throw new IllegalArgumentException("workflow runtime state is required");
        JSONObject payload = object(
                "assignmentId", required(assignmentId, 200, "workflow assignment is invalid"),
                "projectId", required(projectId, 200, "workflow project is invalid"),
                "taskId", required(taskId, 200, "workflow task is invalid"),
                "initialNodeId", required(initialNodeId, 160, "workflow initial node is invalid"),
                "runtimeSnapshot", state.toJson(),
                "idempotencyKey", required(idempotencyKey, 200, "workflow idempotency key is invalid"));
        return event(projectId, taskId, "workflow_execution_start", payload, createdAt, idempotencyKey);
    }

    public static TaskSyncEvent step(
            String projectId,
            String taskId,
            String executionId,
            String nodeId,
            int attemptNumber,
            String status,
            JSONObject inputData,
            JSONObject outputData,
            List<WorkflowEvidenceReference> evidenceReferences,
            JSONObject transitionResult,
            String failureCode,
            String failureReason,
            String nextNodeId,
            WorkflowRuntimeState state,
            long createdAt,
            String idempotencyKey
    ) {
        String acceptedNodeId = required(nodeId, 160, "workflow step node is invalid");
        String acceptedStatus = required(status, 40, "workflow step status is invalid");
        String acceptedFailureReason = optional(failureReason, 1000);
        if (attemptNumber < 1
                || !STEP_STATUSES.contains(acceptedStatus)
                || inputData == null
                || outputData == null
                || transitionResult == null
                || state == null
                || ("failed".equals(acceptedStatus) && acceptedFailureReason.isEmpty())) {
            throw new IllegalArgumentException("workflow step command is invalid");
        }
        JSONArray remoteEvidence = new JSONArray();
        LinkedHashSet<String> remoteIds = new LinkedHashSet<>();
        boolean localOnlyEvidence = false;
        if (evidenceReferences != null) {
            for (WorkflowEvidenceReference evidence : evidenceReferences) {
                if (evidence == null || !acceptedNodeId.equals(evidence.nodeId())) continue;
                if (evidence.remoteAssetId().isEmpty()) {
                    localOnlyEvidence = true;
                } else if (!remoteIds.add(evidence.remoteAssetId())) {
                    throw new IllegalArgumentException("workflow evidence asset is duplicated");
                }
            }
        }
        if (("completed".equals(acceptedStatus) || "skipped".equals(acceptedStatus))
                && localOnlyEvidence) {
            throw new IllegalStateException("workflow_evidence_not_uploaded");
        }
        for (String remoteId : remoteIds) remoteEvidence.put(remoteId);

        JSONObject payload = object(
                "executionId", required(executionId, 200, "workflow execution is invalid"),
                "nodeId", acceptedNodeId,
                "attemptNumber", attemptNumber,
                "status", acceptedStatus,
                "idempotencyKey", required(idempotencyKey, 200, "workflow idempotency key is invalid"),
                "inputData", copy(inputData),
                "outputData", copy(outputData),
                "evidenceAssetIds", remoteEvidence,
                "transitionResult", copy(transitionResult),
                "failureCode", nullable(optional(failureCode, 160)),
                "failureReason", nullable(acceptedFailureReason),
                "nextNodeId", nullable(optional(nextNodeId, 160)),
                "runtimeSnapshot", state.toJson());
        return event(projectId, taskId, "workflow_step_event", payload, createdAt, idempotencyKey);
    }

    private static TaskSyncEvent event(
            String projectId,
            String taskId,
            String eventType,
            JSONObject payload,
            long createdAt,
            String idempotencyKey
    ) {
        String key = required(idempotencyKey, 200, "workflow idempotency key is invalid");
        if (createdAt < 0L || !key.equals(payload.optString("idempotencyKey", ""))) {
            throw new IllegalArgumentException("workflow sync event is invalid");
        }
        return new TaskSyncEvent(
                required(projectId, 200, "workflow project is invalid"),
                required(taskId, 200, "workflow task is invalid"),
                eventType,
                payload.toString(),
                createdAt,
                key);
    }

    private static JSONObject object(Object... values) {
        try {
            JSONObject result = new JSONObject();
            for (int index = 0; index + 1 < values.length; index += 2) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
            return result;
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow sync payload is invalid", exception);
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow sync payload is invalid", exception);
        }
    }

    private static Object nullable(String value) {
        return value.isEmpty() ? JSONObject.NULL : value;
    }

    private static String required(String value, int maxLength, String message) {
        String text = optional(value, maxLength);
        if (text.isEmpty()) throw new IllegalArgumentException(message);
        return text;
    }

    private static String optional(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() > maxLength || containsControl(text)) {
            throw new IllegalArgumentException("workflow sync text is invalid");
        }
        return text;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index += 1) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static Set<String> set(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
