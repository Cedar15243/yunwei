package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.codex.air3nativecamera.sync.TaskSyncEvent;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class WorkflowSyncEventFactoryTest {
    private static final String EXECUTION_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void buildsACompletedStepWithTheRuntimeSnapshotAndUploadedEvidenceOnly() throws Exception {
        WorkflowRuntimeState state = state().recordEvidence(new WorkflowEvidenceReference(
                "local-photo-1",
                "photo",
                "device_photo",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg",
                "77777777-7777-4777-8777-777777777777",
                0));

        TaskSyncEvent event = WorkflowSyncEventFactory.step(
                "project-a",
                "task-a",
                "execution-a",
                "photo",
                1,
                "completed",
                new JSONObject().put("requiredCount", 1),
                new JSONObject().put("capturedCount", 1),
                state.evidenceReferences(),
                new JSONObject().put("matchedTransitionId", "photo-complete"),
                null,
                null,
                "complete",
                state,
                1000L,
                "execution-a:photo:1:completed");

        JSONObject payload = new JSONObject(event.payload());
        assertEquals("workflow_step_event", event.eventType());
        assertEquals("77777777-7777-4777-8777-777777777777",
                payload.getJSONArray("evidenceAssetIds").getString(0));
        assertEquals("photo", payload.getJSONObject("runtimeSnapshot")
                .getString("current_node_id"));
        assertEquals(event.idempotencyKey(), payload.getString("idempotencyKey"));
    }

    @Test
    public void refusesToCompleteWithLocalOnlyEvidenceButAllowsWaitingUpload() throws Exception {
        WorkflowRuntimeState state = state().recordEvidence(new WorkflowEvidenceReference(
                "local-photo-1",
                "photo",
                "device_photo",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg",
                "",
                0));

        assertThrows(IllegalStateException.class, () -> WorkflowSyncEventFactory.step(
                "project-a", "task-a", "execution-a", "photo", 1, "completed",
                new JSONObject(), new JSONObject(), state.evidenceReferences(),
                new JSONObject(), null, null, "complete", state, 1000L,
                "execution-a:photo:1:completed"));

        TaskSyncEvent waiting = WorkflowSyncEventFactory.step(
                "project-a", "task-a", "execution-a", "photo", 1, "waiting_upload",
                new JSONObject(), new JSONObject(), state.evidenceReferences(),
                new JSONObject(), null, null, null, state, 1000L,
                "execution-a:photo:1:waiting_upload");
        assertEquals(0, new JSONObject(waiting.payload())
                .getJSONArray("evidenceAssetIds").length());
    }

    @Test
    public void createsAssignmentAndExecutionEventsWithStableRoutingData() throws Exception {
        TaskSyncEvent status = WorkflowSyncEventFactory.assignmentStatus(
                "project-a", "task-a", "assignment-a", "verified",
                null, null, 1000L, "assignment-a:verified");
        TaskSyncEvent start = WorkflowSyncEventFactory.executionStart(
                "project-a", "task-a", EXECUTION_ID, "assignment-a", "photo", state(),
                1001L, "assignment-a:start");

        assertEquals("workflow_assignment_status", status.eventType());
        assertEquals("assignment-a", new JSONObject(status.payload()).getString("assignmentId"));
        assertEquals("workflow_execution_start", start.eventType());
        assertEquals("task-a", start.taskId());
        assertEquals("task-a", new JSONObject(start.payload()).getString("localTaskId"));
        assertEquals(EXECUTION_ID, new JSONObject(start.payload()).getString("executionId"));
    }

    @Test
    public void refusesToQueueDirectAssignmentCompletion() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowSyncEventFactory.assignmentStatus(
                "project-a", "task-a", "assignment-a", "completed",
                null, null, 1000L, "assignment-a:completed"));
    }

    private WorkflowRuntimeState state() {
        return new WorkflowRuntimeState(
                "33333333-3333-4333-8333-333333333333",
                "photo",
                WorkflowRuntimeState.Status.ACTIVE,
                1,
                new JSONObject());
    }
}
