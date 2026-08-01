package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.sync.TaskSyncClient;
import com.codex.air3nativecamera.sync.TaskSyncEvent;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkflowSyncReporterTest {
    @Test
    public void persistsBeforeSchedulingAndRemovesOnlyAfterDeliveredStatePersists() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        List<WorkflowRuntimeState> persisted = new ArrayList<>();
        AtomicInteger sends = new AtomicInteger();
        WorkflowSyncReporter reporter = new WorkflowSyncReporter(
                state(),
                next -> {
                    persisted.add(WorkflowRuntimeState.fromJson(next.toJson()));
                    return true;
                },
                event -> sends.incrementAndGet(),
                jobs::add);

        WorkflowSyncReporter.RecordResult recorded = reporter.record(event());

        assertEquals(WorkflowSyncReporter.RecordResult.QUEUED, recorded);
        assertEquals(1, persisted.size());
        assertTrue(persisted.get(0).hasPendingEvent("execution-a:photo:1:completed"));
        assertEquals(1, jobs.size());
        assertEquals(0, sends.get());

        WorkflowRuntimeState restarted = WorkflowRuntimeState.fromJson(persisted.get(0).toJson());
        assertEquals(1, restarted.pendingEvents().size());
        jobs.remove(0).run();

        assertEquals(1, sends.get());
        assertEquals(2, persisted.size());
        assertFalse(persisted.get(1).hasPendingEvent("execution-a:photo:1:completed"));
        assertFalse(reporter.state().hasPendingEvent("execution-a:photo:1:completed"));
    }

    @Test
    public void retainsFailedDeliveryWithBackoffAndNeverSendsAnUnpersistedEvent() throws Exception {
        Executor direct = Runnable::run;
        WorkflowSyncReporter failingTransport = new WorkflowSyncReporter(
                state(),
                next -> true,
                new TaskSyncClient.Transport() {
                    @Override
                    public void send(TaskSyncEvent event) throws IOException {
                        throw new IOException("offline");
                    }
                },
                direct,
                () -> 1000L);

        assertEquals(WorkflowSyncReporter.RecordResult.QUEUED,
                failingTransport.record(event()));
        TaskSyncEvent retained = failingTransport.state().pendingEvents().get(0);
        assertEquals(1, retained.failureCount());
        assertEquals(1000L, retained.nextAttemptAt());

        AtomicInteger sends = new AtomicInteger();
        WorkflowSyncReporter rejectedPersistence = new WorkflowSyncReporter(
                state(),
                next -> false,
                value -> sends.incrementAndGet(),
                direct);
        assertEquals(WorkflowSyncReporter.RecordResult.PERSIST_FAILED,
                rejectedPersistence.record(event()));
        assertEquals(0, sends.get());
        assertEquals(0, rejectedPersistence.state().pendingEvents().size());
    }

    @Test
    public void persistsAStateTransitionAndItsSyncEventAsOneCheckpoint() throws Exception {
        List<WorkflowRuntimeState> persisted = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        WorkflowRuntimeState initial = state().withExecutionId(
                "11111111-1111-4111-8111-111111111111");
        WorkflowSyncReporter reporter = new WorkflowSyncReporter(
                initial,
                next -> {
                    persisted.add(WorkflowRuntimeState.fromJson(next.toJson()));
                    return true;
                },
                event -> { },
                jobs::add);
        WorkflowRuntimeState advanced = initial.moveTo(
                "complete", WorkflowRuntimeState.Status.ACTIVE, new JSONObject());

        assertEquals(WorkflowSyncReporter.RecordResult.QUEUED,
                reporter.recordStateAndEvent(advanced, event()));
        assertEquals("complete", persisted.get(0).currentNodeId());
        assertTrue(persisted.get(0).hasPendingEvent("execution-a:photo:1:completed"));
        assertEquals("complete", reporter.state().currentNodeId());
        assertEquals(1, jobs.size());
    }

    private WorkflowRuntimeState state() {
        return new WorkflowRuntimeState(
                "33333333-3333-4333-8333-333333333333",
                "photo",
                WorkflowRuntimeState.Status.ACTIVE,
                1,
                new JSONObject());
    }

    private TaskSyncEvent event() {
        return new TaskSyncEvent(
                "project-a",
                "task-a",
                "workflow_step_event",
                "{\"executionId\":\"execution-a\",\"idempotencyKey\":"
                        + "\"execution-a:photo:1:completed\"}",
                1000L,
                "execution-a:photo:1:completed");
    }
}
