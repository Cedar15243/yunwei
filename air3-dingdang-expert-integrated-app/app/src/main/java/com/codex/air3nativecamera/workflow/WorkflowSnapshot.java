package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

public final class WorkflowSnapshot {
    private final JSONObject envelope;
    private final WorkflowPackage workflowPackage;
    private final WorkflowRuntimeState runtimeState;

    WorkflowSnapshot(JSONObject envelope, WorkflowPackage workflowPackage, WorkflowRuntimeState runtimeState) {
        this.envelope = copy(envelope);
        this.workflowPackage = workflowPackage;
        this.runtimeState = runtimeState;
    }

    public JSONObject envelope() {
        return copy(envelope);
    }

    public WorkflowPackage workflowPackage() {
        return workflowPackage;
    }

    public WorkflowRuntimeState runtimeState() {
        return runtimeState;
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow envelope is invalid", exception);
        }
    }
}
