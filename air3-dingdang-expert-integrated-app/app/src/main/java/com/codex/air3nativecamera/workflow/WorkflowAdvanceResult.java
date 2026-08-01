package com.codex.air3nativecamera.workflow;

public final class WorkflowAdvanceResult {
    private final WorkflowRuntimeState state;
    private final boolean advanced;
    private final String code;

    private WorkflowAdvanceResult(WorkflowRuntimeState state, boolean advanced, String code) {
        this.state = state;
        this.advanced = advanced;
        this.code = code == null ? "" : code;
    }

    static WorkflowAdvanceResult advanced(WorkflowRuntimeState state) {
        return new WorkflowAdvanceResult(state, true, "ok");
    }

    static WorkflowAdvanceResult blocked(WorkflowRuntimeState state, String code) {
        return new WorkflowAdvanceResult(state, false, code);
    }

    public WorkflowRuntimeState state() {
        return state;
    }

    public boolean advanced() {
        return advanced;
    }

    public String code() {
        return code;
    }
}
