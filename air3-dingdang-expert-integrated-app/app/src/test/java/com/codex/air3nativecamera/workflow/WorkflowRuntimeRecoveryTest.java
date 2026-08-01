package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.codex.air3nativecamera.sync.TaskSyncEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowRuntimeRecoveryTest {
    private static final String EXECUTION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_EXECUTION_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void preservesEvidenceAttemptsAndPendingEventsAcrossMovesAndRestart() throws Exception {
        WorkflowRuntimeState initial = new WorkflowRuntimeState(
                "33333333-3333-4333-8333-333333333333",
                "photo",
                WorkflowRuntimeState.Status.ACTIVE,
                1,
                new JSONObject());
        WorkflowEvidenceReference evidence = new WorkflowEvidenceReference(
                "local-photo-1",
                "photo",
                "device_photo",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg",
                "77777777-7777-4777-8777-777777777777",
                0);
        TaskSyncEvent pending = new TaskSyncEvent(
                "project-a",
                "task-a",
                "workflow_step_event",
                new JSONObject().put("executionId", "execution-a")
                        .put("nodeId", "photo")
                        .put("attemptNumber", 1).toString(),
                1000L,
                "execution-a:photo:1:completed");

        WorkflowRuntimeState checkpointed = initial
                .withExecutionId(EXECUTION_ID)
                .recordEvidence(evidence)
                .recordStepAttempt("photo", 1)
                .enqueuePendingEvent(pending);
        WorkflowRuntimeState moved = checkpointed.moveTo(
                "complete",
                WorkflowRuntimeState.Status.ACTIVE,
                checkpointed.variables());
        WorkflowRuntimeState restored = WorkflowRuntimeState.fromJson(moved.toJson());

        assertEquals(1, restored.evidenceReferences().size());
        assertEquals(EXECUTION_ID, restored.executionId());
        assertEquals("local-photo-1", restored.evidenceReferences().get(0).localEvidenceId());
        assertEquals("77777777-7777-4777-8777-777777777777",
                restored.evidenceReferences().get(0).remoteAssetId());
        assertEquals(1, restored.stepAttempt("photo"));
        assertEquals(1, restored.pendingEvents().size());
        assertEquals("execution-a:photo:1:completed",
                restored.pendingEvents().get(0).idempotencyKey());

        WorkflowRuntimeState acknowledged = restored.markPendingEventSucceeded(
                "execution-a:photo:1:completed");
        assertFalse(acknowledged.hasPendingEvent("execution-a:photo:1:completed"));
        assertEquals(1, acknowledged.evidenceReferences().size());
        assertThrows(IllegalStateException.class,
                () -> acknowledged.withExecutionId(OTHER_EXECUTION_ID));
    }

    @Test
    public void rejectsAttemptRegressionAndMalformedRecoveryData() throws Exception {
        WorkflowRuntimeState state = new WorkflowRuntimeState(
                "33333333-3333-4333-8333-333333333333",
                "photo",
                WorkflowRuntimeState.Status.ACTIVE,
                1,
                new JSONObject()).recordStepAttempt("photo", 2);

        assertThrows(IllegalArgumentException.class, () -> state.recordStepAttempt("photo", 1));

        JSONObject malformed = state.toJson()
                .put("pending_events", new JSONArray().put(new JSONObject()
                        .put("idempotency_key", "")
                        .put("event_type", "workflow_step_event")
                        .put("payload", "{}")
                        .put("created_at", 1000L)));
        assertThrows(IllegalArgumentException.class, () -> WorkflowRuntimeState.fromJson(malformed));
    }
}
