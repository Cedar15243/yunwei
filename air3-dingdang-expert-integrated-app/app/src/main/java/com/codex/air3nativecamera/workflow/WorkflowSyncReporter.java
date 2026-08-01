package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.sync.TaskSyncClient;
import com.codex.air3nativecamera.sync.TaskSyncEvent;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class WorkflowSyncReporter {
    public enum RecordResult {
        QUEUED,
        DUPLICATE,
        PERSIST_FAILED
    }

    public interface StatePersistence {
        boolean save(WorkflowRuntimeState state);
    }

    interface Clock {
        long now();
    }

    private final StatePersistence persistence;
    private final TaskSyncClient.Transport transport;
    private final Executor executor;
    private final Clock clock;
    private WorkflowRuntimeState state;
    private boolean delivering;

    public WorkflowSyncReporter(
            WorkflowRuntimeState state,
            StatePersistence persistence,
            TaskSyncClient.Transport transport,
            Executor executor
    ) {
        this(state, persistence, transport, executor, new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        });
    }

    WorkflowSyncReporter(
            WorkflowRuntimeState state,
            StatePersistence persistence,
            TaskSyncClient.Transport transport,
            Executor executor,
            Clock clock
    ) {
        if (state == null || persistence == null || transport == null || executor == null || clock == null) {
            throw new IllegalArgumentException("workflow sync reporter configuration is invalid");
        }
        this.state = copy(state);
        this.persistence = persistence;
        this.transport = transport;
        this.executor = executor;
        this.clock = clock;
    }

    public RecordResult record(TaskSyncEvent event) {
        return recordStateAndEvent(state(), event);
    }

    public RecordResult recordStateAndEvent(
            WorkflowRuntimeState nextState,
            TaskSyncEvent event
    ) {
        synchronized (this) {
            if (nextState == null || event == null) {
                throw new IllegalArgumentException("workflow sync checkpoint is required");
            }
            validateTransition(nextState);
            if (nextState.hasPendingEvent(event.idempotencyKey())) return RecordResult.DUPLICATE;
            WorkflowRuntimeState next = nextState.enqueuePendingEvent(event);
            if (!persist(next)) return RecordResult.PERSIST_FAILED;
            state = next;
        }
        scheduleDelivery();
        return RecordResult.QUEUED;
    }

    private void validateTransition(WorkflowRuntimeState next) {
        if (!state.workflowVersionId().equals(next.workflowVersionId())
                || !state.executionId().equals(next.executionId())
                || next.sequence() < state.sequence()) {
            throw new IllegalArgumentException("workflow sync state transition is invalid");
        }
        for (TaskSyncEvent pending : state.pendingEvents()) {
            if (!next.hasPendingEvent(pending.idempotencyKey())) {
                throw new IllegalArgumentException("workflow sync state drops a pending event");
            }
        }
    }

    public synchronized WorkflowRuntimeState state() {
        return copy(state);
    }

    public void deliverNext() {
        deliverNext(clock.now());
    }

    private void scheduleDelivery() {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    deliverNext(clock.now());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The event is already durable. A later foreground or connectivity trigger can retry it.
        }
    }

    private void deliverNext(long now) {
        TaskSyncEvent event;
        synchronized (this) {
            if (delivering) return;
            event = state.nextPendingEvent(now);
            if (event == null) return;
            delivering = true;
        }
        try {
            transport.send(event);
            synchronized (this) {
                WorkflowRuntimeState next = state.markPendingEventSucceeded(event.idempotencyKey());
                if (persist(next)) state = next;
            }
        } catch (IOException exception) {
            synchronized (this) {
                WorkflowRuntimeState next = state.markPendingEventFailed(
                        event.idempotencyKey(), safeReason(exception), now);
                if (persist(next)) state = next;
            }
        } finally {
            synchronized (this) {
                delivering = false;
            }
        }
    }

    private boolean persist(WorkflowRuntimeState next) {
        try {
            return persistence.save(next);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static WorkflowRuntimeState copy(WorkflowRuntimeState value) {
        return WorkflowRuntimeState.fromJson(value.toJson());
    }

    private static String safeReason(IOException error) {
        String message = error.getMessage() == null ? "workflow_sync_failed" : error.getMessage().trim();
        if (message.isEmpty()) return "workflow_sync_failed";
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
