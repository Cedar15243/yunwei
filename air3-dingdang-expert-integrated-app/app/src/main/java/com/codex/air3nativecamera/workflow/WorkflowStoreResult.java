package com.codex.air3nativecamera.workflow;

public final class WorkflowStoreResult {
    public enum Code {
        NONE,
        EMPTY,
        PACKAGE_REJECTED,
        STATE_INVALID,
        WRITE_FAILED,
        READ_FAILED,
        SNAPSHOT_CORRUPT
    }

    private final Code code;
    private final String detail;
    private final WorkflowSnapshot snapshot;

    private WorkflowStoreResult(Code code, String detail, WorkflowSnapshot snapshot) {
        this.code = code;
        this.detail = detail == null ? "" : detail;
        this.snapshot = snapshot;
    }

    static WorkflowStoreResult success(WorkflowSnapshot snapshot) {
        return new WorkflowStoreResult(Code.NONE, "", snapshot);
    }

    static WorkflowStoreResult failure(Code code, String detail) {
        return new WorkflowStoreResult(code, detail, null);
    }

    public boolean succeeded() {
        return code == Code.NONE;
    }

    public Code code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public WorkflowSnapshot snapshot() {
        return snapshot;
    }
}
