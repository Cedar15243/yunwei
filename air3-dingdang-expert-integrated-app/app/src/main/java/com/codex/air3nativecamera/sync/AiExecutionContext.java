package com.codex.air3nativecamera.sync;

/** Real local project/task ownership sent to the managed V9 AI gateway. */
public final class AiExecutionContext {
    private final String localProjectId;
    private final String localTaskId;

    public AiExecutionContext(String localProjectId, String localTaskId) {
        this.localProjectId = requireIdentifier(localProjectId, "local project identifier");
        this.localTaskId = requireIdentifier(localTaskId, "local task identifier");
    }

    public String localProjectId() {
        return localProjectId;
    }

    public String localTaskId() {
        return localTaskId;
    }

    private static String requireIdentifier(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return clean;
    }
}
