package com.codex.air3nativecamera.workflow;

public final class WorkflowPackageVerification {
    public enum Error {
        NONE,
        INVALID_ENVELOPE,
        INCOMPATIBLE_SCHEMA,
        INCOMPATIBLE_APP_VERSION,
        MISSING_CAPABILITY,
        CONTENT_DIGEST_MISMATCH,
        UNKNOWN_SIGNATURE_KEY,
        SIGNATURE_INVALID,
        PACKAGE_INVALID
    }

    private final WorkflowPackage workflowPackage;
    private final Error error;
    private final String detail;

    private WorkflowPackageVerification(WorkflowPackage workflowPackage, Error error, String detail) {
        this.workflowPackage = workflowPackage;
        this.error = error;
        this.detail = detail == null ? "" : detail;
    }

    static WorkflowPackageVerification accepted(WorkflowPackage workflowPackage) {
        return new WorkflowPackageVerification(workflowPackage, Error.NONE, "");
    }

    static WorkflowPackageVerification rejected(Error error, String detail) {
        return new WorkflowPackageVerification(null, error, detail);
    }

    public boolean isAccepted() {
        return workflowPackage != null && error == Error.NONE;
    }

    public WorkflowPackage workflowPackage() {
        return workflowPackage;
    }

    public Error error() {
        return error;
    }

    public String detail() {
        return detail;
    }
}
