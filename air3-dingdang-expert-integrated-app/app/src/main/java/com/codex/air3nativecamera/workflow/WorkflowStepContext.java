package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class WorkflowStepContext {
    public enum EvidenceType {
        PHOTO,
        VIDEO,
        AUDIO
    }

    private static final class Evidence {
        private final String key;
        private final EvidenceType type;
        private final int durationSeconds;

        private Evidence(String key, EvidenceType type, int durationSeconds) {
            this.key = key;
            this.type = type;
            this.durationSeconds = durationSeconds;
        }
    }

    private final JSONObject fields = new JSONObject();
    private final List<Evidence> evidence = new ArrayList<>();
    private boolean confirmed;
    private boolean synchronizedState;
    private boolean online = true;
    private boolean serverAcknowledged;
    private String confirmationPhrase = "";

    public WorkflowStepContext putField(String key, Object value) {
        String fieldKey = clean(key);
        if (fieldKey.isEmpty() || fieldKey.length() > 160 || value == null) {
            throw new IllegalArgumentException("workflow field is invalid");
        }
        try {
            fields.put(fieldKey, value);
            return this;
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow field is invalid", exception);
        }
    }

    public WorkflowStepContext addEvidence(String key, EvidenceType type, int durationSeconds) {
        String evidenceKey = clean(key);
        if (evidenceKey.isEmpty() || evidenceKey.length() > 160 || type == null || durationSeconds < 0) {
            throw new IllegalArgumentException("workflow evidence is invalid");
        }
        evidence.add(new Evidence(evidenceKey, type, durationSeconds));
        return this;
    }

    public WorkflowStepContext setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
        return this;
    }

    public WorkflowStepContext setSynchronized(boolean synchronizedState) {
        this.synchronizedState = synchronizedState;
        return this;
    }

    public WorkflowStepContext setOnline(boolean online) {
        this.online = online;
        return this;
    }

    public WorkflowStepContext setServerAcknowledged(boolean serverAcknowledged) {
        this.serverAcknowledged = serverAcknowledged;
        return this;
    }

    public WorkflowStepContext setConfirmationPhrase(String confirmationPhrase) {
        this.confirmationPhrase = clean(confirmationPhrase);
        return this;
    }

    JSONObject fields() {
        try {
            return new JSONObject(fields.toString());
        } catch (JSONException exception) {
            throw new IllegalStateException("workflow fields are invalid", exception);
        }
    }

    int evidenceCount(String key, EvidenceType type, int minimumDurationSeconds) {
        int count = 0;
        for (Evidence item : evidence) {
            if (item.key.equals(key) && item.type == type && item.durationSeconds >= minimumDurationSeconds) {
                count += 1;
            }
        }
        return count;
    }

    boolean confirmed() {
        return confirmed;
    }

    boolean synchronizedState() {
        return synchronizedState;
    }

    boolean online() {
        return online;
    }

    boolean serverAcknowledged() {
        return serverAcknowledged;
    }

    String confirmationPhrase() {
        return confirmationPhrase;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
