package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.sync.TaskSyncEvent;
import com.codex.air3nativecamera.sync.TaskSyncQueue;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowRuntimeState {
    public enum Status {
        ACTIVE,
        WAITING_NETWORK,
        COMPLETED,
        CANCELLED
    }

    private final String workflowVersionId;
    private final String currentNodeId;
    private final Status status;
    private final int sequence;
    private final JSONObject variables;
    private final List<WorkflowEvidenceReference> evidenceReferences;
    private final Map<String, Integer> stepAttempts;
    private final TaskSyncQueue pendingEvents;

    WorkflowRuntimeState(
            String workflowVersionId,
            String currentNodeId,
            Status status,
            int sequence,
            JSONObject variables
    ) {
        this(workflowVersionId, currentNodeId, status, sequence, variables,
                Collections.<WorkflowEvidenceReference>emptyList(),
                Collections.<String, Integer>emptyMap(),
                new TaskSyncQueue());
    }

    private WorkflowRuntimeState(
            String workflowVersionId,
            String currentNodeId,
            Status status,
            int sequence,
            JSONObject variables,
            List<WorkflowEvidenceReference> evidenceReferences,
            Map<String, Integer> stepAttempts,
            TaskSyncQueue pendingEvents
    ) {
        this.workflowVersionId = clean(workflowVersionId);
        this.currentNodeId = clean(currentNodeId);
        this.status = status;
        this.sequence = sequence;
        this.variables = copy(variables);
        this.evidenceReferences = Collections.unmodifiableList(new ArrayList<>(evidenceReferences));
        this.stepAttempts = Collections.unmodifiableMap(new LinkedHashMap<>(stepAttempts));
        this.pendingEvents = copyQueue(pendingEvents);
        if (this.workflowVersionId.isEmpty() || this.currentNodeId.isEmpty() || status == null || sequence < 0) {
            throw new IllegalArgumentException("workflow runtime state is invalid");
        }
        if (this.evidenceReferences.size() > 2000
                || this.stepAttempts.size() > 1000
                || this.pendingEvents.pending().size() > 2000) {
            throw new IllegalArgumentException("workflow recovery state is too large");
        }
    }

    WorkflowRuntimeState moveTo(String nodeId, Status nextStatus, JSONObject nextVariables) {
        return new WorkflowRuntimeState(
                workflowVersionId,
                nodeId,
                nextStatus,
                sequence + 1,
                nextVariables,
                evidenceReferences,
                stepAttempts,
                pendingEvents);
    }

    WorkflowRuntimeState retain(Status nextStatus, JSONObject nextVariables) {
        return new WorkflowRuntimeState(
                workflowVersionId,
                currentNodeId,
                nextStatus,
                sequence,
                nextVariables,
                evidenceReferences,
                stepAttempts,
                pendingEvents);
    }

    public String workflowVersionId() {
        return workflowVersionId;
    }

    public String currentNodeId() {
        return currentNodeId;
    }

    public Status status() {
        return status;
    }

    public int sequence() {
        return sequence;
    }

    public JSONObject variables() {
        return copy(variables);
    }

    public List<WorkflowEvidenceReference> evidenceReferences() {
        return evidenceReferences;
    }

    public int stepAttempt(String nodeId) {
        Integer attempt = stepAttempts.get(clean(nodeId));
        return attempt == null ? 0 : attempt;
    }

    public List<TaskSyncEvent> pendingEvents() {
        return pendingEvents.pending();
    }

    public boolean hasPendingEvent(String idempotencyKey) {
        String key = clean(idempotencyKey);
        for (TaskSyncEvent event : pendingEvents.pending()) {
            if (event.idempotencyKey().equals(key)) return true;
        }
        return false;
    }

    public TaskSyncEvent nextPendingEvent(long now) {
        return pendingEvents.nextReady(now);
    }

    public WorkflowRuntimeState recordEvidence(WorkflowEvidenceReference evidence) {
        if (evidence == null) throw new IllegalArgumentException("workflow evidence is required");
        List<WorkflowEvidenceReference> next = new ArrayList<>(evidenceReferences);
        for (WorkflowEvidenceReference existing : evidenceReferences) {
            if (!existing.localEvidenceId().equals(evidence.localEvidenceId())) continue;
            if (!existing.toJson().toString().equals(evidence.toJson().toString())) {
                throw new IllegalArgumentException("workflow evidence identifier conflict");
            }
            return this;
        }
        next.add(evidence);
        return rebuild(next, stepAttempts, pendingEvents);
    }

    public WorkflowRuntimeState recordStepAttempt(String nodeId, int attempt) {
        String key = clean(nodeId);
        int current = stepAttempt(key);
        if (key.isEmpty() || key.length() > 160 || attempt < 1 || attempt < current) {
            throw new IllegalArgumentException("workflow step attempt is invalid");
        }
        if (attempt == current) return this;
        Map<String, Integer> next = new LinkedHashMap<>(stepAttempts);
        next.put(key, attempt);
        return rebuild(evidenceReferences, next, pendingEvents);
    }

    public WorkflowRuntimeState enqueuePendingEvent(TaskSyncEvent event) {
        if (event == null) throw new IllegalArgumentException("workflow pending event is required");
        TaskSyncQueue next = copyQueue(pendingEvents);
        for (TaskSyncEvent existing : pendingEvents.pending()) {
            if (!existing.idempotencyKey().equals(event.idempotencyKey())) continue;
            if (!sameEvent(existing, event)) {
                throw new IllegalArgumentException("workflow pending event identifier conflict");
            }
            return this;
        }
        if (!next.enqueue(event)) throw new IllegalArgumentException("workflow pending event is invalid");
        return rebuild(evidenceReferences, stepAttempts, next);
    }

    public WorkflowRuntimeState markPendingEventSucceeded(String idempotencyKey) {
        String key = clean(idempotencyKey);
        TaskSyncQueue next = copyQueue(pendingEvents);
        return next.markSucceeded(key)
                ? rebuild(evidenceReferences, stepAttempts, next)
                : this;
    }

    public WorkflowRuntimeState markPendingEventFailed(
            String idempotencyKey,
            String reason,
            long now
    ) {
        TaskSyncQueue next = copyQueue(pendingEvents);
        return next.markFailed(clean(idempotencyKey), clean(reason), now)
                ? rebuild(evidenceReferences, stepAttempts, next)
                : this;
    }

    public JSONObject toJson() {
        try {
            JSONArray evidence = new JSONArray();
            for (WorkflowEvidenceReference item : evidenceReferences) evidence.put(item.toJson());
            JSONObject attempts = new JSONObject();
            for (Map.Entry<String, Integer> item : stepAttempts.entrySet()) {
                attempts.put(item.getKey(), item.getValue());
            }
            return new JSONObject()
                    .put("workflow_version_id", workflowVersionId)
                    .put("current_node_id", currentNodeId)
                    .put("status", status.name())
                    .put("sequence", sequence)
                    .put("variables", copy(variables))
                    .put("evidence_references", evidence)
                    .put("step_attempts", attempts)
                    .put("pending_events", pendingEvents.toJson());
        } catch (JSONException exception) {
            throw new IllegalStateException("unable to serialize workflow state", exception);
        }
    }

    public static WorkflowRuntimeState fromJson(JSONObject value) {
        if (value == null) throw new IllegalArgumentException("workflow state is required");
        Status status;
        try {
            status = Status.valueOf(value.optString("status", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("workflow state status is invalid", exception);
        }
        JSONObject variables = value.optJSONObject("variables");
        int sequence = value.optInt("sequence", -1);
        if (variables == null) throw new IllegalArgumentException("workflow state variables are invalid");
        if ((value.has("evidence_references") && value.optJSONArray("evidence_references") == null)
                || (value.has("step_attempts") && value.optJSONObject("step_attempts") == null)
                || (value.has("pending_events") && value.optJSONArray("pending_events") == null)) {
            throw new IllegalArgumentException("workflow recovery snapshot shape is invalid");
        }
        List<WorkflowEvidenceReference> evidence = parseEvidence(value.optJSONArray("evidence_references"));
        Map<String, Integer> attempts = parseAttempts(value.optJSONObject("step_attempts"));
        TaskSyncQueue events = TaskSyncQueue.fromJsonStrict(value.optJSONArray("pending_events"));
        return new WorkflowRuntimeState(
                value.optString("workflow_version_id", ""),
                value.optString("current_node_id", ""),
                status,
                sequence,
                variables,
                evidence,
                attempts,
                events);
    }

    private WorkflowRuntimeState rebuild(
            List<WorkflowEvidenceReference> evidence,
            Map<String, Integer> attempts,
            TaskSyncQueue events
    ) {
        return new WorkflowRuntimeState(
                workflowVersionId,
                currentNodeId,
                status,
                sequence,
                variables,
                evidence,
                attempts,
                events);
    }

    private static List<WorkflowEvidenceReference> parseEvidence(JSONArray values) {
        List<WorkflowEvidenceReference> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index += 1) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) throw new IllegalArgumentException("workflow evidence snapshot is invalid");
            WorkflowEvidenceReference evidence = WorkflowEvidenceReference.fromJson(item);
            for (WorkflowEvidenceReference existing : result) {
                if (existing.localEvidenceId().equals(evidence.localEvidenceId())) {
                    throw new IllegalArgumentException("workflow evidence snapshot is duplicated");
                }
            }
            result.add(evidence);
        }
        return result;
    }

    private static Map<String, Integer> parseAttempts(JSONObject values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (values == null) return result;
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = clean(keys.next());
            int attempt = values.optInt(key, -1);
            if (key.isEmpty() || key.length() > 160 || attempt < 1) {
                throw new IllegalArgumentException("workflow attempt snapshot is invalid");
            }
            result.put(key, attempt);
        }
        return result;
    }

    private static TaskSyncQueue copyQueue(TaskSyncQueue value) {
        return TaskSyncQueue.fromJsonStrict(value == null ? null : value.toJson());
    }

    private static boolean sameEvent(TaskSyncEvent first, TaskSyncEvent second) {
        return first.projectId().equals(second.projectId())
                && first.projectTitle().equals(second.projectTitle())
                && first.taskId().equals(second.taskId())
                && first.taskTitle().equals(second.taskTitle())
                && first.eventType().equals(second.eventType())
                && first.payload().equals(second.payload())
                && first.createdAt() == second.createdAt()
                && first.failureCount() == second.failureCount()
                && first.nextAttemptAt() == second.nextAttemptAt()
                && first.lastFailure().equals(second.lastFailure());
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow variables are invalid", exception);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
