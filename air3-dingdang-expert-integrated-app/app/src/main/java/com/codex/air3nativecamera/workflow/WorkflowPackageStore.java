package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WorkflowPackageStore {
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private final WorkflowSnapshotStorage storage;
    private final WorkflowPackageVerifier verifier;

    public WorkflowPackageStore(WorkflowSnapshotStorage storage, WorkflowPackageVerifier verifier) {
        if (storage == null || verifier == null) {
            throw new IllegalArgumentException("workflow store configuration is invalid");
        }
        this.storage = storage;
        this.verifier = verifier;
    }

    public synchronized WorkflowStoreResult save(JSONObject envelope, WorkflowRuntimeState state) {
        WorkflowPackageVerification verification = verifier.verify(envelope);
        if (!verification.isAccepted()) {
            return WorkflowStoreResult.failure(
                    WorkflowStoreResult.Code.PACKAGE_REJECTED,
                    verification.error().name() + ":" + verification.detail());
        }
        WorkflowPackage workflowPackage = verification.workflowPackage();
        if (!stateMatches(workflowPackage, state)) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.STATE_INVALID, "runtime_state_mismatch");
        }
        JSONObject snapshot;
        try {
            snapshot = new JSONObject()
                    .put("snapshot_schema_version", SNAPSHOT_SCHEMA_VERSION)
                    .put("envelope", copy(envelope))
                    .put("runtime_state", state.toJson());
        } catch (JSONException exception) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.SNAPSHOT_CORRUPT, "snapshot_serialization_failed");
        }
        try {
            storage.writeAtomically(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            return WorkflowStoreResult.success(new WorkflowSnapshot(envelope, workflowPackage, state));
        } catch (IOException exception) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.WRITE_FAILED, "atomic_write_failed");
        }
    }

    public synchronized WorkflowStoreResult load() {
        byte[] bytes;
        try {
            bytes = storage.read();
        } catch (IOException exception) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.READ_FAILED, "snapshot_read_failed");
        }
        if (bytes == null || bytes.length == 0) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.EMPTY, "snapshot_missing");
        }
        try {
            JSONObject snapshot = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (snapshot.optInt("snapshot_schema_version", -1) != SNAPSHOT_SCHEMA_VERSION) {
                return WorkflowStoreResult.failure(WorkflowStoreResult.Code.SNAPSHOT_CORRUPT, "snapshot_schema_invalid");
            }
            JSONObject envelope = snapshot.optJSONObject("envelope");
            JSONObject stateValue = snapshot.optJSONObject("runtime_state");
            if (envelope == null || stateValue == null) {
                return WorkflowStoreResult.failure(WorkflowStoreResult.Code.SNAPSHOT_CORRUPT, "snapshot_shape_invalid");
            }
            WorkflowPackageVerification verification = verifier.verify(envelope);
            if (!verification.isAccepted()) {
                return WorkflowStoreResult.failure(
                        WorkflowStoreResult.Code.PACKAGE_REJECTED,
                        verification.error().name() + ":" + verification.detail());
            }
            WorkflowRuntimeState state = WorkflowRuntimeState.fromJson(stateValue);
            if (!stateMatches(verification.workflowPackage(), state)) {
                return WorkflowStoreResult.failure(WorkflowStoreResult.Code.STATE_INVALID, "runtime_state_mismatch");
            }
            return WorkflowStoreResult.success(
                    new WorkflowSnapshot(envelope, verification.workflowPackage(), state));
        } catch (JSONException | IllegalArgumentException exception) {
            return WorkflowStoreResult.failure(WorkflowStoreResult.Code.SNAPSHOT_CORRUPT, "snapshot_parse_failed");
        }
    }

    private boolean stateMatches(WorkflowPackage workflowPackage, WorkflowRuntimeState state) {
        return state != null
                && workflowPackage.workflowVersionId().equals(state.workflowVersionId())
                && workflowPackage.node(state.currentNodeId()) != null;
    }

    private JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow envelope is invalid", exception);
        }
    }
}
