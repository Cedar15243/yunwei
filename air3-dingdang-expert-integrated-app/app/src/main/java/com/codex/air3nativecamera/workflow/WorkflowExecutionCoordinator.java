package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.sync.TaskSyncClient;
import com.codex.air3nativecamera.sync.TaskSyncEvent;
import com.codex.air3nativecamera.sync.AiExecutionContext;
import com.codex.air3nativecamera.sync.WorkflowAssignmentRepository;
import com.codex.air3nativecamera.sync.WorkflowDeviceHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Coordinates verified workflow assignments with the fixed HUD runtime. */
public final class WorkflowExecutionCoordinator {
    public enum EntryAction {
        START_WORKFLOW,
        RESUME_WORKFLOW,
        CHOOSE_MODE,
        OPEN_STANDARD_TASK,
        WAITING_DELIVERY,
        VIEW_COMPLETED,
        UNAVAILABLE
    }

    public enum OpenCode {
        STARTED,
        RESUMED,
        COMPLETED,
        NOT_FOUND,
        CHOICE_REQUIRED,
        STANDARD_TASK_REQUIRED,
        NOT_READY,
        INVALID_STATE,
        PERSIST_FAILED
    }

    public enum ActionCode {
        RECORDED,
        ADVANCED,
        BLOCKED,
        REPLAY_QUEUED,
        EVIDENCE_UPDATED,
        NOT_FOUND,
        NOT_READY,
        INVALID_STATE,
        PERSIST_FAILED
    }

    public interface SnapshotAccess {
        WorkflowSnapshot load(String assignmentId);

        boolean save(String assignmentId, JSONObject envelope, WorkflowRuntimeState state);
    }

    interface IdGenerator {
        String next();
    }

    interface Clock {
        long now();
    }

    public static final class TaskListItem {
        private final String assignmentId;
        private final String workOrderId;
        private final String displayCode;
        private final String title;
        private final String assetLabel;
        private final String priority;
        private final String mode;
        private final String status;
        private final EntryAction entryAction;

        private TaskListItem(
                WorkflowAssignmentRepository.CachedAssignment assignment,
                EntryAction entryAction
        ) {
            WorkflowDeviceHttpClient.WorkOrderSummary summary = assignment.workOrder();
            this.assignmentId = assignment.assignmentId();
            this.workOrderId = assignment.workOrderId();
            this.displayCode = summary.externalWorkOrderId().isEmpty()
                    ? assignment.workOrderId() : summary.externalWorkOrderId();
            this.title = summary.available() ? summary.title() : "工单详情待同步";
            this.assetLabel = join(summary.assetBrand(), summary.assetModel());
            this.priority = summary.priority();
            this.mode = assignment.mode();
            this.status = assignment.status();
            this.entryAction = entryAction;
        }

        public String assignmentId() { return assignmentId; }
        public String workOrderId() { return workOrderId; }
        public String displayCode() { return displayCode; }
        public String title() { return title; }
        public String assetLabel() { return assetLabel; }
        public String priority() { return priority; }
        public String mode() { return mode; }
        public String status() { return status; }
        public EntryAction entryAction() { return entryAction; }
    }

    public static final class TaskDetail {
        private final TaskListItem listItem;
        private final String description;
        private final String customerId;
        private final String workOrderType;
        private final String riskLevel;
        private final String dueAt;
        private final String workflowTitle;
        private final String workflowVersionId;

        private TaskDetail(
                WorkflowAssignmentRepository.CachedAssignment assignment,
                EntryAction entryAction,
                WorkflowSnapshot snapshot
        ) {
            this.listItem = new TaskListItem(assignment, entryAction);
            WorkflowDeviceHttpClient.WorkOrderSummary summary = assignment.workOrder();
            this.description = summary.description();
            this.customerId = summary.customerId();
            this.workOrderType = summary.workOrderType();
            this.riskLevel = summary.riskLevel();
            this.dueAt = summary.dueAt();
            this.workflowTitle = snapshot == null || snapshot.workflowPackage() == null
                    ? "" : snapshot.workflowPackage().title();
            this.workflowVersionId = assignment.workflowVersionId();
        }

        public TaskListItem listItem() { return listItem; }
        public String description() { return description; }
        public String customerId() { return customerId; }
        public String workOrderType() { return workOrderType; }
        public String riskLevel() { return riskLevel; }
        public String dueAt() { return dueAt; }
        public String workflowTitle() { return workflowTitle; }
        public String workflowVersionId() { return workflowVersionId; }
        public String mode() { return listItem.mode(); }
        public EntryAction entryAction() { return listItem.entryAction(); }
    }

    public static final class StepHud {
        private final String nodeId;
        private final String nodeType;
        private final WorkflowCapabilityRegistry.PageTemplate pageTemplate;
        private final String tag;
        private final String title;
        private final String description;
        private final List<String> items;
        private final List<String> itemActions;
        private final String primaryAction;
        private final String primaryLabel;
        private final String secondaryAction;
        private final String secondaryLabel;

        private StepHud(
                String nodeId,
                String nodeType,
                WorkflowCapabilityRegistry.PageTemplate pageTemplate,
                String tag,
                String title,
                String description,
                List<String> items,
                List<String> itemActions,
                String primaryAction,
                String primaryLabel,
                String secondaryAction,
                String secondaryLabel
        ) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.pageTemplate = pageTemplate;
            this.tag = tag;
            this.title = title;
            this.description = description;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.itemActions = Collections.unmodifiableList(new ArrayList<>(itemActions));
            this.primaryAction = primaryAction;
            this.primaryLabel = primaryLabel;
            this.secondaryAction = secondaryAction;
            this.secondaryLabel = secondaryLabel;
        }

        public String nodeId() { return nodeId; }
        public String nodeType() { return nodeType; }
        public WorkflowCapabilityRegistry.PageTemplate pageTemplate() { return pageTemplate; }
        public String tag() { return tag; }
        public String title() { return title; }
        public String description() { return description; }
        public List<String> items() { return items; }
        public List<String> itemActions() { return itemActions; }
        public String primaryAction() { return primaryAction; }
        public String primaryLabel() { return primaryLabel; }
        public String secondaryAction() { return secondaryAction; }
        public String secondaryLabel() { return secondaryLabel; }
    }

    public static final class OpenResult {
        private final OpenCode code;
        private final WorkflowRuntimeState state;
        private final StepHud step;

        private OpenResult(OpenCode code, WorkflowRuntimeState state, StepHud step) {
            this.code = code;
            this.state = state;
            this.step = step;
        }

        public OpenCode code() { return code; }
        public WorkflowRuntimeState state() { return state; }
        public StepHud step() { return step; }
    }

    public static final class ActionResult {
        private final ActionCode code;
        private final String reason;
        private final WorkflowRuntimeState state;
        private final StepHud step;

        private ActionResult(
                ActionCode code,
                String reason,
                WorkflowRuntimeState state,
                StepHud step
        ) {
            this.code = code;
            this.reason = reason == null ? "" : reason;
            this.state = state;
            this.step = step;
        }

        public ActionCode code() { return code; }
        public String reason() { return reason; }
        public WorkflowRuntimeState state() { return state; }
        public StepHud step() { return step; }
    }

    private final WorkflowAssignmentRepository assignments;
    private final SnapshotAccess snapshots;
    private final TaskSyncClient.Transport transport;
    private final Executor executor;
    private final IdGenerator identifiers;
    private final Clock clock;
    private final Set<String> scheduledAssignments = new HashSet<>();

    public WorkflowExecutionCoordinator(
            WorkflowAssignmentRepository assignments,
            SnapshotAccess snapshots,
            TaskSyncClient.Transport transport,
            Executor executor
    ) {
        this(assignments, snapshots, transport, executor,
                () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    WorkflowExecutionCoordinator(
            WorkflowAssignmentRepository assignments,
            SnapshotAccess snapshots,
            TaskSyncClient.Transport transport,
            Executor executor,
            IdGenerator identifiers,
            Clock clock
    ) {
        if (assignments == null || snapshots == null || transport == null || executor == null
                || identifiers == null || clock == null) {
            throw new IllegalArgumentException("workflow execution configuration is invalid");
        }
        this.assignments = assignments;
        this.snapshots = snapshots;
        this.transport = transport;
        this.executor = executor;
        this.identifiers = identifiers;
        this.clock = clock;
    }

    public List<TaskListItem> tasks() {
        List<TaskListItem> result = new ArrayList<>();
        for (WorkflowAssignmentRepository.CachedAssignment assignment : assignments.assignments()) {
            if ("revoked".equals(assignment.status())) continue;
            WorkflowSnapshot snapshot = safeLoad(assignment);
            result.add(new TaskListItem(assignment, entryAction(assignment, snapshot)));
        }
        return Collections.unmodifiableList(result);
    }

    public TaskDetail detail(String assignmentId) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        if (assignment == null || "revoked".equals(assignment.status())) return null;
        WorkflowSnapshot snapshot = safeLoad(assignment);
        return new TaskDetail(assignment, entryAction(assignment, snapshot), snapshot);
    }

    /** Returns the server execution identity owned by this workflow assignment. */
    public synchronized AiExecutionContext executionContextFor(String assignmentId) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        if (assignment == null || assignment.projectId().isEmpty()) {
            throw new IllegalArgumentException("workflow_assignment_context_missing");
        }
        return new AiExecutionContext(assignment.projectId(), taskId(assignment));
    }

    public OpenResult startOrResume(String assignmentId) {
        return open(assignmentId, false);
    }

    public OpenResult startWorkflow(String assignmentId) {
        return open(assignmentId, true);
    }

    public synchronized ActionResult recordEvidence(
            String assignmentId,
            WorkflowEvidenceReference evidence
    ) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        WorkflowSnapshot snapshot = executableSnapshot(assignment);
        if (assignment == null) return action(ActionCode.NOT_FOUND, "assignment_not_found", null, null);
        if (snapshot == null) return action(ActionCode.NOT_READY, "workflow_not_ready", null, null);
        WorkflowRuntimeState state = snapshot.runtimeState();
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (state.executionId().isEmpty()
                || evidence == null
                || node == null
                || !validEvidence(node, evidence)) {
            return action(ActionCode.INVALID_STATE, "workflow_evidence_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        WorkflowRuntimeState next;
        try {
            next = state.recordEvidence(evidence);
        } catch (RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, "workflow_evidence_conflict", state,
                    step(snapshot.workflowPackage(), state));
        }
        if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), next)) {
            return action(ActionCode.PERSIST_FAILED, "workflow_evidence_persist_failed", state,
                    step(snapshot.workflowPackage(), state));
        }
        return action(ActionCode.RECORDED, "", next, step(snapshot.workflowPackage(), next));
    }

    public synchronized ActionResult recordFormField(
            String assignmentId,
            String nodeId,
            String fieldKey,
            String rawValue
    ) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        WorkflowSnapshot snapshot = executableSnapshot(assignment);
        if (assignment == null) return action(ActionCode.NOT_FOUND, "assignment_not_found", null, null);
        if (snapshot == null) return action(ActionCode.NOT_READY, "workflow_not_ready", null, null);
        WorkflowRuntimeState state = snapshot.runtimeState();
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (state.executionId().isEmpty() || node == null || !"form".equals(node.type())
                || !node.nodeId().equals(nodeId)) {
            return action(ActionCode.INVALID_STATE, "workflow_form_state_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        String value;
        try {
            value = WorkflowFormFieldPolicy.normalize(node, fieldKey, rawValue);
        } catch (RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, exception.getMessage(), state,
                    step(snapshot.workflowPackage(), state));
        }
        JSONObject variables;
        try {
            variables = new JSONObject(state.variables().toString()).put(fieldKey.trim(), value);
        } catch (JSONException | RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, "workflow_form_fields_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        WorkflowRuntimeState next = state.retain(state.status(), variables);
        if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), next)) {
            return action(ActionCode.PERSIST_FAILED, "workflow_form_persist_failed", state,
                    step(snapshot.workflowPackage(), state));
        }
        return action(ActionCode.RECORDED, "", next, step(snapshot.workflowPackage(), next));
    }

    public synchronized ActionResult advanceForm(String assignmentId) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        WorkflowSnapshot snapshot = executableSnapshot(assignment);
        if (assignment == null) return action(ActionCode.NOT_FOUND, "assignment_not_found", null, null);
        if (snapshot == null) return action(ActionCode.NOT_READY, "workflow_not_ready", null, null);
        WorkflowRuntimeState state = snapshot.runtimeState();
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (node == null || !"form".equals(node.type())) {
            return action(ActionCode.INVALID_STATE, "workflow_form_state_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        return advance(assignmentId,
                WorkflowFormFieldPolicy.contextFor(node, state.variables()),
                WorkflowFormFieldPolicy.inputDataFor(node, state.variables()),
                WorkflowFormFieldPolicy.outputDataFor(node, state.variables()));
    }

    public synchronized ActionResult advance(
            String assignmentId,
            WorkflowStepContext context,
            JSONObject inputData,
            JSONObject outputData
    ) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        WorkflowSnapshot snapshot = executableSnapshot(assignment);
        if (assignment == null) return action(ActionCode.NOT_FOUND, "assignment_not_found", null, null);
        if (snapshot == null) return action(ActionCode.NOT_READY, "workflow_not_ready", null, null);
        WorkflowRuntimeState state = snapshot.runtimeState();
        if (state.executionId().isEmpty() || context == null || inputData == null || outputData == null) {
            return action(ActionCode.INVALID_STATE, "workflow_step_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (node == null) {
            return action(ActionCode.INVALID_STATE, "workflow_node_missing", state, null);
        }

        WorkflowAdvanceResult result;
        try {
            WorkflowStepContext authoritative = authoritativeContext(state, node, context);
            result = new WorkflowStateMachine(snapshot.workflowPackage()).advance(
                    state, authoritative);
        } catch (RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, "workflow_advance_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        if (!result.advanced()) {
            WorkflowRuntimeState retained = result.state();
            if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), retained)) {
                return action(ActionCode.PERSIST_FAILED, "workflow_step_persist_failed", state,
                        step(snapshot.workflowPackage(), state));
            }
            return action(ActionCode.BLOCKED, result.code(), retained,
                    step(snapshot.workflowPackage(), retained));
        }

        int attempt = state.stepAttempt(node.nodeId()) + 1;
        WorkflowRuntimeState advanced = result.state().recordStepAttempt(node.nodeId(), attempt);
        String nextNodeId = "complete".equals(node.type()) ? "" : advanced.currentNodeId();
        JSONObject transitionResult = transitionResult(node.nodeId(), nextNodeId);
        String eventKey = stepEventKey(advanced.executionId(), node.nodeId(), attempt);
        List<String> localEvidenceIds = unresolvedEvidenceIds(advanced, node.nodeId());
        try {
            if (!advanced.deferredSteps().isEmpty() || !localEvidenceIds.isEmpty()) {
                advanced = advanced.deferStep(new WorkflowDeferredStep(
                        node.nodeId(), attempt, inputData, outputData, localEvidenceIds,
                        transitionResult, nextNodeId, advanced.toJson(), clock.now(), eventKey));
            } else {
                advanced = advanced.enqueuePendingEvent(stepEvent(
                        assignment, node.nodeId(), attempt, inputData, outputData,
                        transitionResult, nextNodeId, advanced, clock.now(), eventKey));
            }
        } catch (RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, "workflow_step_checkpoint_invalid", state,
                    step(snapshot.workflowPackage(), state));
        }
        if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), advanced)) {
            return action(ActionCode.PERSIST_FAILED, "workflow_step_persist_failed", state,
                    step(snapshot.workflowPackage(), state));
        }
        WorkflowSnapshot durable = new WorkflowSnapshot(
                snapshot.envelope(), snapshot.workflowPackage(), advanced);
        schedulePending(durable, assignment.assignmentId());
        return action(ActionCode.ADVANCED, "", advanced,
                step(snapshot.workflowPackage(), advanced));
    }

    public synchronized ActionResult onEvidenceUploaded(
            String assignmentId,
            String localEvidenceId,
            String remoteAssetId
    ) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        WorkflowSnapshot snapshot = executableSnapshot(assignment);
        if (assignment == null) return action(ActionCode.NOT_FOUND, "assignment_not_found", null, null);
        if (snapshot == null) return action(ActionCode.NOT_READY, "workflow_not_ready", null, null);
        WorkflowRuntimeState original = snapshot.runtimeState();
        WorkflowRuntimeState next;
        try {
            next = original.resolveEvidenceAsset(localEvidenceId, remoteAssetId);
            next = replayReadyDeferredSteps(assignment, next);
        } catch (RuntimeException exception) {
            return action(ActionCode.INVALID_STATE, "workflow_evidence_upload_invalid", original,
                    step(snapshot.workflowPackage(), original));
        }
        if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), next)) {
            return action(ActionCode.PERSIST_FAILED, "workflow_upload_persist_failed", original,
                    step(snapshot.workflowPackage(), original));
        }
        WorkflowSnapshot durable = new WorkflowSnapshot(
                snapshot.envelope(), snapshot.workflowPackage(), next);
        schedulePending(durable, assignment.assignmentId());
        ActionCode code = next.deferredSteps().size() < original.deferredSteps().size()
                ? ActionCode.REPLAY_QUEUED : ActionCode.EVIDENCE_UPDATED;
        return action(code, "", next, step(snapshot.workflowPackage(), next));
    }

    private synchronized OpenResult open(String assignmentId, boolean optionalConfirmed) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        if (assignment == null || "revoked".equals(assignment.status())) {
            return result(OpenCode.NOT_FOUND, null, null);
        }
        if ("none".equals(assignment.mode())) {
            return result(OpenCode.STANDARD_TASK_REQUIRED, null, null);
        }
        WorkflowSnapshot snapshot = safeLoad(assignment);
        if (snapshot == null || !assignment.packageCached()) {
            return result(OpenCode.NOT_READY, null, null);
        }
        WorkflowRuntimeState state = snapshot.runtimeState();
        if (!validSnapshot(assignment, snapshot)) {
            return result(OpenCode.INVALID_STATE, null, null);
        }
        if (!state.executionId().isEmpty()) {
            schedulePending(snapshot, assignment.assignmentId());
        }
        if (state.status() == WorkflowRuntimeState.Status.COMPLETED) {
            return result(OpenCode.COMPLETED, state, step(snapshot.workflowPackage(), state));
        }
        if (!state.executionId().isEmpty()) {
            return result(OpenCode.RESUMED, state, step(snapshot.workflowPackage(), state));
        }
        if ("optional".equals(assignment.mode()) && !optionalConfirmed) {
            return result(OpenCode.CHOICE_REQUIRED, state, step(snapshot.workflowPackage(), state));
        }
        if (assignment.projectId().isEmpty()) {
            return result(OpenCode.INVALID_STATE, state, step(snapshot.workflowPackage(), state));
        }

        WorkflowRuntimeState started;
        try {
            started = state.withExecutionId(identifiers.next());
            for (TaskSyncEvent event : startEvents(assignment, started)) {
                started = started.enqueuePendingEvent(event);
            }
        } catch (RuntimeException exception) {
            return result(OpenCode.INVALID_STATE, state, step(snapshot.workflowPackage(), state));
        }
        if (!snapshots.save(assignment.assignmentId(), snapshot.envelope(), started)) {
            return result(OpenCode.PERSIST_FAILED, state, step(snapshot.workflowPackage(), state));
        }
        WorkflowSnapshot durable = new WorkflowSnapshot(
                snapshot.envelope(), snapshot.workflowPackage(), started);
        schedulePending(durable, assignment.assignmentId());
        return result(OpenCode.STARTED, started, step(snapshot.workflowPackage(), started));
    }

    private List<TaskSyncEvent> startEvents(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            WorkflowRuntimeState state
    ) {
        String taskId = "workflow-task:" + assignment.assignmentId();
        long createdAt = clock.now();
        List<TaskSyncEvent> events = new ArrayList<>();
        for (String status : new String[]{"delivered", "verified", "ready"}) {
            events.add(WorkflowSyncEventFactory.assignmentStatus(
                    assignment.projectId(), taskId, assignment.assignmentId(), status,
                    "", "", createdAt,
                    assignment.assignmentId() + ":assignment:" + status));
        }
        events.add(WorkflowSyncEventFactory.executionStart(
                assignment.projectId(), taskId, state.executionId(), assignment.assignmentId(),
                state.currentNodeId(), state, createdAt,
                assignment.assignmentId() + ":execution:start"));
        return events;
    }

    private WorkflowRuntimeState replayReadyDeferredSteps(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            WorkflowRuntimeState state
    ) {
        WorkflowRuntimeState next = state;
        while (!next.deferredSteps().isEmpty()) {
            WorkflowDeferredStep deferred = next.deferredSteps().get(0);
            if (!allEvidenceUploaded(next, deferred.localEvidenceIds())) break;
            WorkflowRuntimeState eventState = WorkflowRuntimeState.fromJson(
                    deferred.runtimeSnapshot());
            for (WorkflowEvidenceReference evidence : next.evidenceReferences()) {
                if (evidence.remoteAssetId().isEmpty()
                        || eventState.evidenceReference(evidence.localEvidenceId()) == null) {
                    continue;
                }
                eventState = eventState.resolveEvidenceAsset(
                        evidence.localEvidenceId(), evidence.remoteAssetId());
            }
            TaskSyncEvent event = stepEvent(
                    assignment, deferred.nodeId(), deferred.attemptNumber(),
                    deferred.inputData(), deferred.outputData(), deferred.transitionResult(),
                    deferred.nextNodeId(), eventState, deferred.createdAt(),
                    deferred.idempotencyKey());
            next = next.removeDeferredStep(deferred.idempotencyKey())
                    .enqueuePendingEvent(event);
        }
        return next;
    }

    private TaskSyncEvent stepEvent(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            String nodeId,
            int attempt,
            JSONObject inputData,
            JSONObject outputData,
            JSONObject transitionResult,
            String nextNodeId,
            WorkflowRuntimeState state,
            long createdAt,
            String eventKey
    ) {
        return WorkflowSyncEventFactory.step(
                assignment.projectId(), taskId(assignment), state.executionId(),
                nodeId, attempt, "completed", inputData, outputData,
                state.evidenceReferences(), transitionResult, "", "",
                nextNodeId, state, createdAt, eventKey);
    }

    private static boolean validEvidence(
            WorkflowPackage.Node node,
            WorkflowEvidenceReference evidence
    ) {
        if (!node.nodeId().equals(evidence.nodeId())) return false;
        String expectedKey = text(node.config(), "evidenceKey", node.nodeId());
        if (!expectedKey.equals(evidence.evidenceKey())) return false;
        if ("photo_capture".equals(node.type())) {
            return evidence.type() == WorkflowStepContext.EvidenceType.PHOTO;
        }
        if ("video_capture".equals(node.type())) {
            return evidence.type() == WorkflowStepContext.EvidenceType.VIDEO;
        }
        return "voice_input".equals(node.type())
                && evidence.type() == WorkflowStepContext.EvidenceType.AUDIO;
    }

    private static WorkflowStepContext authoritativeContext(
            WorkflowRuntimeState state,
            WorkflowPackage.Node node,
            WorkflowStepContext provided
    ) {
        WorkflowStepContext result = new WorkflowStepContext();
        JSONObject fields = provided.fields();
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.putField(key, fields.opt(key));
        }
        for (WorkflowEvidenceReference evidence : state.evidenceReferences()) {
            if (node.nodeId().equals(evidence.nodeId())) {
                result.addEvidence(
                        evidence.evidenceKey(), evidence.type(), evidence.durationSeconds());
            }
        }
        return result
                .setConfirmed(provided.confirmed())
                .setSynchronized(provided.synchronizedState())
                .setOnline(provided.online())
                .setServerAcknowledged(provided.serverAcknowledged())
                .setConfirmationPhrase(provided.confirmationPhrase());
    }

    private static List<String> unresolvedEvidenceIds(
            WorkflowRuntimeState state,
            String nodeId
    ) {
        List<String> result = new ArrayList<>();
        for (WorkflowEvidenceReference evidence : state.evidenceReferences()) {
            if (nodeId.equals(evidence.nodeId()) && evidence.remoteAssetId().isEmpty()) {
                result.add(evidence.localEvidenceId());
            }
        }
        return result;
    }

    private static boolean allEvidenceUploaded(
            WorkflowRuntimeState state,
            List<String> localEvidenceIds
    ) {
        for (String evidenceId : localEvidenceIds) {
            if (!state.hasRemoteEvidenceAsset(evidenceId)) return false;
        }
        return true;
    }

    private static JSONObject transitionResult(String fromNodeId, String nextNodeId) {
        try {
            return new JSONObject()
                    .put("result", "completed")
                    .put("fromNodeId", fromNodeId)
                    .put("nextNodeId", nextNodeId.isEmpty() ? JSONObject.NULL : nextNodeId);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow transition result is invalid", exception);
        }
    }

    private static String stepEventKey(String executionId, String nodeId, int attempt) {
        return executionId + ":" + nodeId + ":" + attempt + ":completed";
    }

    private static String taskId(WorkflowAssignmentRepository.CachedAssignment assignment) {
        return "workflow-task:" + assignment.assignmentId();
    }

    private WorkflowSnapshot executableSnapshot(
            WorkflowAssignmentRepository.CachedAssignment assignment
    ) {
        if (assignment == null
                || "revoked".equals(assignment.status())
                || "none".equals(assignment.mode())) {
            return null;
        }
        return safeLoad(assignment);
    }

    private void schedulePending(WorkflowSnapshot snapshot, String assignmentId) {
        if (snapshot.runtimeState().pendingEvents().isEmpty()) return;
        synchronized (this) {
            if (!scheduledAssignments.add(assignmentId)) return;
        }
        try {
            executor.execute(() -> deliverPending(assignmentId));
        } catch (RejectedExecutionException ignored) {
            synchronized (this) {
                scheduledAssignments.remove(assignmentId);
            }
            // The complete outbox is durable and will be retried on resume/network change.
        }
    }

    private void deliverPending(String assignmentId) {
        try {
            while (true) {
                TaskSyncEvent event;
                synchronized (this) {
                    WorkflowSnapshot current = currentSnapshot(assignmentId);
                    event = current == null
                            ? null : current.runtimeState().nextPendingEvent(clock.now());
                    if (event == null) {
                        scheduledAssignments.remove(assignmentId);
                        return;
                    }
                }
                try {
                    transport.send(event);
                } catch (IOException exception) {
                    synchronized (this) {
                        WorkflowSnapshot current = currentSnapshot(assignmentId);
                        if (current != null
                                && current.runtimeState().hasPendingEvent(
                                event.idempotencyKey())) {
                            WorkflowRuntimeState failed = current.runtimeState()
                                    .markPendingEventFailed(
                                            event.idempotencyKey(),
                                            safeFailure(exception), clock.now());
                            snapshots.save(
                                    assignmentId, current.envelope(), failed);
                        }
                    }
                    return;
                }
                synchronized (this) {
                    WorkflowSnapshot current = currentSnapshot(assignmentId);
                    if (current == null) return;
                    WorkflowRuntimeState state = current.runtimeState();
                    if (!state.hasPendingEvent(event.idempotencyKey())) continue;
                    WorkflowRuntimeState acknowledged = state.markPendingEventSucceeded(
                            event.idempotencyKey());
                    if (!snapshots.save(
                            assignmentId, current.envelope(), acknowledged)) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (this) {
                scheduledAssignments.remove(assignmentId);
            }
        }
    }

    private WorkflowSnapshot currentSnapshot(String assignmentId) {
        WorkflowAssignmentRepository.CachedAssignment assignment = assignments.find(assignmentId);
        return assignment == null ? null : safeLoad(assignment);
    }

    private static String safeFailure(IOException exception) {
        String message = exception.getMessage() == null
                ? "workflow_sync_failed" : exception.getMessage().trim();
        if (message.isEmpty()) return "workflow_sync_failed";
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private WorkflowSnapshot safeLoad(
            WorkflowAssignmentRepository.CachedAssignment assignment
    ) {
        if (!assignment.packageCached() || "none".equals(assignment.mode())) return null;
        try {
            WorkflowSnapshot snapshot = snapshots.load(assignment.assignmentId());
            return validSnapshot(assignment, snapshot) ? snapshot : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean validSnapshot(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            WorkflowSnapshot snapshot
    ) {
        return snapshot != null
                && snapshot.workflowPackage() != null
                && snapshot.runtimeState() != null
                && assignment.assignmentId().equals(snapshot.workflowPackage().assignmentId())
                && assignment.workflowVersionId().equals(snapshot.workflowPackage().workflowVersionId())
                && assignment.workflowVersionId().equals(snapshot.runtimeState().workflowVersionId())
                && snapshot.workflowPackage().node(snapshot.runtimeState().currentNodeId()) != null;
    }

    private static EntryAction entryAction(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            WorkflowSnapshot snapshot
    ) {
        if ("none".equals(assignment.mode())) return EntryAction.OPEN_STANDARD_TASK;
        if ("failed".equals(assignment.status())) return EntryAction.UNAVAILABLE;
        if (!assignment.packageCached() || snapshot == null) return EntryAction.WAITING_DELIVERY;
        WorkflowRuntimeState state = snapshot.runtimeState();
        if (state.status() == WorkflowRuntimeState.Status.COMPLETED) {
            return EntryAction.VIEW_COMPLETED;
        }
        if (!state.executionId().isEmpty()) return EntryAction.RESUME_WORKFLOW;
        return "optional".equals(assignment.mode())
                ? EntryAction.CHOOSE_MODE : EntryAction.START_WORKFLOW;
    }

    private static StepHud step(WorkflowPackage workflowPackage, WorkflowRuntimeState state) {
        if (workflowPackage == null || state == null) return null;
        WorkflowPackage.Node node = workflowPackage.node(state.currentNodeId());
        if (node == null) return null;
        JSONObject config = node.config();
        String title = text(config, "title", defaultTitle(node.type()));
        String description = text(config, "description", "");
        List<String> items = new ArrayList<>();
        List<String> itemActions = new ArrayList<>();
        actionsFor(node, config, state.variables(), items, itemActions);
        String[] primary = primaryAction(node.type());
        return new StepHud(
                node.nodeId(), node.type(),
                WorkflowCapabilityRegistry.bindingForNodeType(node.type()).pageTemplate(),
                "步骤 " + Math.max(1, state.sequence()),
                title, description, items, itemActions,
                primary[0], primary[1], "workflow_back", "返回");
    }

    private static void actionsFor(
            WorkflowPackage.Node node,
            JSONObject config,
            JSONObject variables,
            List<String> items,
            List<String> itemActions
    ) {
        String type = node.type();
        if ("photo_capture".equals(type) || "video_capture".equals(type)) {
            int count = Math.max(1, config.optInt("minCount", 1));
            items.add(("photo_capture".equals(type) ? "至少拍摄 " : "至少录制 ")
                    + count + ("photo_capture".equals(type) ? " 张" : " 段"));
        } else if ("choice".equals(type)) {
            JSONArray options = config.optJSONArray("options");
            if (options != null) {
                for (int index = 0; index < options.length(); index += 1) {
                    JSONObject option = options.optJSONObject(index);
                    if (option == null) continue;
                    String value = option.optString("value", "").trim();
                    String label = option.optString("label", "").trim();
                    if (value.isEmpty() || label.isEmpty()) continue;
                    items.add(label);
                    itemActions.add("workflow_select:" + node.nodeId() + ":" + value);
                }
            }
        } else if ("form".equals(type)) {
            JSONArray fields = config.optJSONArray("fields");
            if (fields != null) {
                for (int index = 0; index < fields.length(); index += 1) {
                    JSONObject field = fields.optJSONObject(index);
                    if (field == null) continue;
                    String key = field.optString("key", "").trim();
                    String label = field.optString("label", "").trim();
                    if (key.isEmpty() || label.isEmpty()) continue;
                    String value = WorkflowFormFieldPolicy.displayValue(variables, key);
                    items.add(label + (field.optBoolean("required", false) ? "（必填）" : "")
                            + (value.isEmpty() ? "" : "：" + value));
                    itemActions.add("workflow_input:" + node.nodeId() + ":" + key);
                }
            }
        }
        String risk = text(config, "riskNotice", "");
        if (!risk.isEmpty()) items.add("风险提示：" + risk);
    }

    private static String[] primaryAction(String type) {
        if ("photo_capture".equals(type)) return new String[]{"workflow_capture_photo", "拍照"};
        if ("video_capture".equals(type)) return new String[]{"workflow_capture_video", "录像"};
        if ("voice_input".equals(type)) return new String[]{"workflow_voice_input", "语音填写"};
        if ("ai_assist".equals(type)) return new String[]{"workflow_ai_assist", "AI 分析"};
        if ("expert_call".equals(type)) return new String[]{"workflow_expert_call", "呼叫专家"};
        if ("confirmation".equals(type) || "connector_action".equals(type)) {
            return new String[]{"workflow_confirm", "确认"};
        }
        if ("complete".equals(type)) return new String[]{"workflow_complete", "完成任务"};
        return new String[]{"workflow_next", "下一步"};
    }

    private static String defaultTitle(String type) {
        if ("photo_capture".equals(type)) return "拍照取证";
        if ("video_capture".equals(type)) return "录像取证";
        if ("voice_input".equals(type)) return "语音填写";
        if ("ai_assist".equals(type)) return "AI 协助";
        if ("expert_call".equals(type)) return "专家协同";
        if ("confirmation".equals(type)) return "人工确认";
        if ("complete".equals(type)) return "任务完成";
        if ("choice".equals(type)) return "请选择";
        if ("form".equals(type)) return "填写信息";
        return "操作说明";
    }

    private static String text(JSONObject value, String key, String fallback) {
        String result = value.optString(key, "").trim();
        return result.isEmpty() ? fallback : result;
    }

    private static OpenResult result(
            OpenCode code,
            WorkflowRuntimeState state,
            StepHud step
    ) {
        return new OpenResult(code, state, step);
    }

    private static ActionResult action(
            ActionCode code,
            String reason,
            WorkflowRuntimeState state,
            StepHud step
    ) {
        return new ActionResult(code, reason, state, step);
    }

    private static String join(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + " " + right;
    }
}
