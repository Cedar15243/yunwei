package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

public final class WorkflowSyncTriggerCoordinatorTest {
    @Test
    public void coalescesForegroundConnectivityAndPushTriggersIntoOneQueuedRun() {
        ManualExecutor executor = new ManualExecutor();
        FakeSyncOperation operation = new FakeSyncOperation(success(false));
        WorkflowSyncTriggerCoordinator coordinator = new WorkflowSyncTriggerCoordinator(
                operation, executor, cursor(0L));

        assertTrue(coordinator.request(0L));
        assertFalse(coordinator.request(7L));
        assertFalse(coordinator.request(7L));
        assertEquals(1, executor.pending());

        executor.runNext();

        assertEquals(1, operation.calls);
        assertFalse(coordinator.running());
        assertEquals(7L, coordinator.highestHint());
    }

    @Test
    public void aTriggerArrivingDuringDeliveryRunsOneFollowUpPass() {
        ManualExecutor executor = new ManualExecutor();
        final WorkflowSyncTriggerCoordinator[] holder = new WorkflowSyncTriggerCoordinator[1];
        WorkflowSyncOperation operation = new WorkflowSyncOperation() {
            private int calls;

            @Override
            public WorkflowAssignmentSyncCoordinator.Result syncOnce() {
                calls += 1;
                if (calls == 1) holder[0].request(9L);
                return success(false);
            }
        };
        holder[0] = new WorkflowSyncTriggerCoordinator(operation, executor, cursor(0L));

        holder[0].request(0L);
        executor.runNext();

        assertEquals(2, holder[0].completedPasses());
        assertFalse(holder[0].running());
    }

    @Test
    public void drainsBoundedPackageWorkButDoesNotSpinOnNetworkFailure() {
        ManualExecutor executor = new ManualExecutor();
        FakeSyncOperation operation = new FakeSyncOperation(
                success(true),
                success(false),
                new WorkflowAssignmentSyncCoordinator.Result(
                        WorkflowAssignmentSyncCoordinator.Code.NETWORK_FAILURE,
                        0,
                        1,
                        true));
        WorkflowSyncTriggerCoordinator coordinator = new WorkflowSyncTriggerCoordinator(
                operation, executor, cursor(0L));

        coordinator.request(0L);
        executor.runNext();
        assertEquals(2, operation.calls);

        coordinator.request(0L);
        executor.runNext();
        assertEquals(3, operation.calls);
        assertFalse(coordinator.running());
    }

    private static WorkflowAssignmentSyncCoordinator.Result success(boolean moreWork) {
        return new WorkflowAssignmentSyncCoordinator.Result(
                WorkflowAssignmentSyncCoordinator.Code.SUCCESS,
                0,
                0,
                moreWork);
    }

    private static LongSupplier cursor(final long value) {
        return new LongSupplier() {
            @Override public long getAsLong() { return value; }
        };
    }

    private static final class FakeSyncOperation implements WorkflowSyncOperation {
        private final Queue<WorkflowAssignmentSyncCoordinator.Result> results = new ArrayDeque<>();
        private int calls;

        private FakeSyncOperation(WorkflowAssignmentSyncCoordinator.Result... results) {
            for (WorkflowAssignmentSyncCoordinator.Result result : results) this.results.add(result);
        }

        @Override
        public WorkflowAssignmentSyncCoordinator.Result syncOnce() {
            calls += 1;
            return results.remove();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) { tasks.add(command); }
        private int pending() { return tasks.size(); }
        private void runNext() { tasks.remove().run(); }
    }
}
