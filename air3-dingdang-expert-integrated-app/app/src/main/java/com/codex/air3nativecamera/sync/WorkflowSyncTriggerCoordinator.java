package com.codex.air3nativecamera.sync;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

public final class WorkflowSyncTriggerCoordinator {
    private static final int MAX_IMMEDIATE_PASSES = 8;

    private final WorkflowSyncOperation operation;
    private final Executor executor;
    private final LongSupplier cursor;
    private boolean running;
    private long generation;
    private long highestHint;
    private int completedPasses;

    public WorkflowSyncTriggerCoordinator(
            WorkflowSyncOperation operation,
            Executor executor,
            LongSupplier cursor
    ) {
        if (operation == null || executor == null || cursor == null) {
            throw new IllegalArgumentException("workflow sync trigger configuration is invalid");
        }
        this.operation = operation;
        this.executor = executor;
        this.cursor = cursor;
    }

    public boolean request(long hintedSequence) {
        if (hintedSequence < 0L) {
            throw new IllegalArgumentException("workflow sync hint is invalid");
        }
        synchronized (this) {
            generation += 1L;
            highestHint = Math.max(highestHint, hintedSequence);
            if (running) return false;
            running = true;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
            return true;
        } catch (RuntimeException exception) {
            synchronized (this) {
                running = false;
            }
            return false;
        }
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized long highestHint() {
        return highestHint;
    }

    public synchronized int completedPasses() {
        return completedPasses;
    }

    public synchronized boolean behindHint() {
        return cursor.getAsLong() < highestHint;
    }

    private void drain() {
        int immediatePasses = 0;
        while (true) {
            long observedGeneration;
            synchronized (this) {
                observedGeneration = generation;
            }
            WorkflowAssignmentSyncCoordinator.Result result;
            try {
                result = operation.syncOnce();
            } catch (RuntimeException exception) {
                synchronized (this) {
                    completedPasses += 1;
                    running = false;
                }
                return;
            }
            immediatePasses += 1;
            synchronized (this) {
                completedPasses += 1;
                boolean triggeredDuringPass = generation != observedGeneration;
                boolean drainBoundedWork = result != null
                        && result.code() == WorkflowAssignmentSyncCoordinator.Code.SUCCESS
                        && result.moreWork()
                        && immediatePasses < MAX_IMMEDIATE_PASSES;
                if (triggeredDuringPass || drainBoundedWork) continue;
                running = false;
                return;
            }
        }
    }
}
