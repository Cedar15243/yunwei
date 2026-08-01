package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

public final class WorkflowEvidenceReference {
    private static final String IDENTIFIER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$";
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private final String localEvidenceId;
    private final String nodeId;
    private final String evidenceKey;
    private final WorkflowStepContext.EvidenceType type;
    private final String localReference;
    private final String remoteAssetId;
    private final int durationSeconds;

    public WorkflowEvidenceReference(
            String localEvidenceId,
            String nodeId,
            String evidenceKey,
            WorkflowStepContext.EvidenceType type,
            String localReference,
            String remoteAssetId,
            int durationSeconds
    ) {
        this.localEvidenceId = clean(localEvidenceId);
        this.nodeId = clean(nodeId);
        this.evidenceKey = clean(evidenceKey);
        this.type = type;
        this.localReference = clean(localReference);
        this.remoteAssetId = clean(remoteAssetId);
        this.durationSeconds = durationSeconds;
        if (!this.localEvidenceId.matches(IDENTIFIER_PATTERN)
                || !this.nodeId.matches(IDENTIFIER_PATTERN)
                || !this.evidenceKey.matches(IDENTIFIER_PATTERN)
                || type == null
                || !validLocalReference(this.localReference)
                || (!this.remoteAssetId.isEmpty() && !this.remoteAssetId.matches(UUID_PATTERN))
                || durationSeconds < 0
                || durationSeconds > 86_400) {
            throw new IllegalArgumentException("workflow evidence reference is invalid");
        }
    }

    public String localEvidenceId() {
        return localEvidenceId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String evidenceKey() {
        return evidenceKey;
    }

    public WorkflowStepContext.EvidenceType type() {
        return type;
    }

    public String localReference() {
        return localReference;
    }

    public String remoteAssetId() {
        return remoteAssetId;
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("local_evidence_id", localEvidenceId)
                    .put("node_id", nodeId)
                    .put("evidence_key", evidenceKey)
                    .put("type", type.name())
                    .put("local_reference", localReference)
                    .put("remote_asset_id", remoteAssetId)
                    .put("duration_seconds", durationSeconds);
        } catch (JSONException exception) {
            throw new IllegalStateException("unable to serialize workflow evidence", exception);
        }
    }

    static WorkflowEvidenceReference fromJson(JSONObject value) {
        if (value == null) throw new IllegalArgumentException("workflow evidence is required");
        WorkflowStepContext.EvidenceType type;
        try {
            type = WorkflowStepContext.EvidenceType.valueOf(value.optString("type", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("workflow evidence type is invalid", exception);
        }
        return new WorkflowEvidenceReference(
                value.optString("local_evidence_id", ""),
                value.optString("node_id", ""),
                value.optString("evidence_key", ""),
                type,
                value.optString("local_reference", ""),
                value.optString("remote_asset_id", ""),
                value.optInt("duration_seconds", -1));
    }

    private static boolean validLocalReference(String value) {
        return !value.isEmpty()
                && value.length() <= 1024
                && !value.startsWith("/")
                && !value.contains("\\")
                && !value.contains(":")
                && !value.contains("..")
                && value.matches("^[A-Za-z0-9][A-Za-z0-9_./-]*$");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
