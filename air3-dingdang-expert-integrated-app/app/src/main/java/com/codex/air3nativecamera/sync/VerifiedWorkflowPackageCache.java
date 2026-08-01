package com.codex.air3nativecamera.sync;

import com.codex.air3nativecamera.workflow.WorkflowPackage;
import com.codex.air3nativecamera.workflow.WorkflowPackageStore;
import com.codex.air3nativecamera.workflow.WorkflowPackageVerification;
import com.codex.air3nativecamera.workflow.WorkflowPackageVerifier;
import com.codex.air3nativecamera.workflow.WorkflowRuntimeState;
import com.codex.air3nativecamera.workflow.WorkflowSnapshot;
import com.codex.air3nativecamera.workflow.WorkflowStateMachine;
import com.codex.air3nativecamera.workflow.WorkflowStoreResult;

import org.json.JSONObject;

public final class VerifiedWorkflowPackageCache
        implements WorkflowAssignmentSyncCoordinator.PackageCache {
    public interface StoreProvider {
        WorkflowPackageStore storeFor(String assignmentId);
        boolean invalidate(String assignmentId);
    }

    private final WorkflowPackageVerifier verifier;
    private final StoreProvider stores;

    public VerifiedWorkflowPackageCache(
            WorkflowPackageVerifier verifier,
            StoreProvider stores
    ) {
        if (verifier == null || stores == null) {
            throw new IllegalArgumentException("verified workflow package cache configuration is invalid");
        }
        this.verifier = verifier;
        this.stores = stores;
    }

    @Override
    public boolean isAvailable(WorkflowAssignmentRepository.CachedAssignment assignment) {
        if (!eligible(assignment)) return false;
        try {
            WorkflowStoreResult loaded = stores.storeFor(assignment.assignmentId()).load();
            return loaded.succeeded() && matches(assignment, loaded.snapshot());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public boolean install(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            JSONObject envelope
    ) {
        if (!eligible(assignment) || envelope == null) return false;
        try {
            WorkflowPackageVerification verification = verifier.verify(envelope);
            if (!verification.isAccepted()) return false;
            WorkflowPackage workflowPackage = verification.workflowPackage();
            if (!assignment.assignmentId().equals(workflowPackage.assignmentId())
                    || !assignment.workflowVersionId().equals(workflowPackage.workflowVersionId())) {
                return false;
            }
            WorkflowRuntimeState initialState = new WorkflowStateMachine(workflowPackage).start();
            WorkflowStoreResult saved = stores.storeFor(assignment.assignmentId())
                    .save(envelope, initialState);
            return saved.succeeded() && matches(assignment, saved.snapshot());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public boolean invalidate(String assignmentId) {
        String id = clean(assignmentId);
        if (!validIdentifier(id)) return false;
        try {
            return stores.invalidate(id);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean matches(
            WorkflowAssignmentRepository.CachedAssignment assignment,
            WorkflowSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.workflowPackage() == null) return false;
        WorkflowPackage workflowPackage = snapshot.workflowPackage();
        return assignment.assignmentId().equals(workflowPackage.assignmentId())
                && assignment.workflowVersionId().equals(workflowPackage.workflowVersionId())
                && snapshot.runtimeState() != null
                && assignment.workflowVersionId().equals(
                        snapshot.runtimeState().workflowVersionId());
    }

    private static boolean eligible(WorkflowAssignmentRepository.CachedAssignment assignment) {
        return assignment != null
                && !"none".equals(assignment.mode())
                && !"failed".equals(assignment.status())
                && !"revoked".equals(assignment.status());
    }

    private static boolean validIdentifier(String value) {
        return value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
