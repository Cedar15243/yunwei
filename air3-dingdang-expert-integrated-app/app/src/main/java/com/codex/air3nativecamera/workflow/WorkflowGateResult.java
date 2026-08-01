package com.codex.air3nativecamera.workflow;

final class WorkflowGateResult {
    private final boolean allowed;
    private final String code;
    private final boolean waitingNetwork;

    private WorkflowGateResult(boolean allowed, String code, boolean waitingNetwork) {
        this.allowed = allowed;
        this.code = code;
        this.waitingNetwork = waitingNetwork;
    }

    static WorkflowGateResult allow() {
        return new WorkflowGateResult(true, "ok", false);
    }

    static WorkflowGateResult block(String code) {
        return new WorkflowGateResult(false, code, false);
    }

    static WorkflowGateResult waitForNetwork() {
        return new WorkflowGateResult(false, "network_required", true);
    }

    boolean allowed() {
        return allowed;
    }

    String code() {
        return code;
    }

    boolean waitingNetwork() {
        return waitingNetwork;
    }
}
