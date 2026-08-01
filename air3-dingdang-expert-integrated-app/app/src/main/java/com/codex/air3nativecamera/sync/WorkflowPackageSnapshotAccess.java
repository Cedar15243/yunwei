package com.codex.air3nativecamera.sync;

import com.codex.air3nativecamera.workflow.WorkflowExecutionCoordinator;
import com.codex.air3nativecamera.workflow.WorkflowPackageStore;
import com.codex.air3nativecamera.workflow.WorkflowRuntimeState;
import com.codex.air3nativecamera.workflow.WorkflowSnapshot;
import com.codex.air3nativecamera.workflow.WorkflowStoreResult;

import org.json.JSONObject;

/** Reuses the verified package cache as the execution coordinator's atomic snapshot store. */
public final class WorkflowPackageSnapshotAccess
        implements WorkflowExecutionCoordinator.SnapshotAccess {
    private final VerifiedWorkflowPackageCache.StoreProvider stores;

    public WorkflowPackageSnapshotAccess(
            VerifiedWorkflowPackageCache.StoreProvider stores
    ) {
        if (stores == null) {
            throw new IllegalArgumentException("workflow snapshot stores are required");
        }
        this.stores = stores;
    }

    @Override
    public WorkflowSnapshot load(String assignmentId) {
        try {
            WorkflowStoreResult result = store(assignmentId).load();
            return result.succeeded() ? result.snapshot() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public boolean save(
            String assignmentId,
            JSONObject envelope,
            WorkflowRuntimeState state
    ) {
        if (envelope == null || state == null) return false;
        try {
            return store(assignmentId).save(envelope, state).succeeded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private WorkflowPackageStore store(String assignmentId) {
        WorkflowPackageStore store = stores.storeFor(assignmentId);
        if (store == null) {
            throw new IllegalStateException("workflow package store is unavailable");
        }
        return store;
    }
}
