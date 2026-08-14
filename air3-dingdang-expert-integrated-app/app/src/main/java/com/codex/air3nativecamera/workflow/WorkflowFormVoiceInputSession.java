package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONObject;

/** Binds one ASR result to one server-defined field on the current form node. */
public final class WorkflowFormVoiceInputSession {
    private static final int MAX_TRANSCRIPT_CHARS = 8_192;

    private final String assignmentId;
    private final String executionId;
    private final String nodeId;
    private final String fieldKey;
    private final String fieldLabel;
    private final int maximumDurationSeconds;
    private boolean completed;

    private WorkflowFormVoiceInputSession(
            String assignmentId,
            String executionId,
            String nodeId,
            String fieldKey,
            String fieldLabel,
            int maximumDurationSeconds
    ) {
        this.assignmentId = assignmentId;
        this.executionId = executionId;
        this.nodeId = nodeId;
        this.fieldKey = fieldKey;
        this.fieldLabel = fieldLabel;
        this.maximumDurationSeconds = maximumDurationSeconds;
    }

    public static WorkflowFormVoiceInputSession from(
            String assignmentId,
            String executionId,
            WorkflowPackage.Node node,
            String fieldKey,
            int deviceMaximumDurationSeconds
    ) {
        String acceptedAssignmentId = clean(assignmentId);
        String acceptedExecutionId = clean(executionId);
        String acceptedFieldKey = clean(fieldKey);
        if (acceptedAssignmentId.isEmpty() || acceptedExecutionId.isEmpty()
                || node == null || !"form".equals(node.type())
                || acceptedFieldKey.isEmpty() || deviceMaximumDurationSeconds < 1) {
            throw new IllegalArgumentException("workflow form voice request is invalid");
        }
        JSONObject field = field(node, acceptedFieldKey);
        if (field == null || !supportsVoiceInput(field.optString("type", "text"))) {
            throw new IllegalArgumentException("workflow form voice field is invalid");
        }
        String label = clean(field.optString("label", ""));
        if (label.isEmpty()) {
            throw new IllegalArgumentException("workflow form voice label is invalid");
        }
        int maximumDurationSeconds = deviceMaximumDurationSeconds;
        if (field.has("maxDurationSeconds")) {
            int requested = field.optInt("maxDurationSeconds", 0);
            if (requested < 1) {
                throw new IllegalArgumentException("workflow form voice duration is invalid");
            }
            maximumDurationSeconds = Math.min(requested, deviceMaximumDurationSeconds);
        }
        return new WorkflowFormVoiceInputSession(
                acceptedAssignmentId,
                acceptedExecutionId,
                node.nodeId(),
                acceptedFieldKey,
                label,
                maximumDurationSeconds);
    }

    public String nodeId() { return nodeId; }

    public String fieldKey() { return fieldKey; }

    public String fieldLabel() { return fieldLabel; }

    public int maximumDurationSeconds() { return maximumDurationSeconds; }

    public long maximumDurationMillis() { return maximumDurationSeconds * 1_000L; }

    public boolean matches(
            String assignmentId,
            String executionId,
            String nodeId,
            String fieldKey
    ) {
        return this.assignmentId.equals(clean(assignmentId))
                && this.executionId.equals(clean(executionId))
                && this.nodeId.equals(clean(nodeId))
                && this.fieldKey.equals(clean(fieldKey));
    }

    public String complete(String transcript) {
        if (completed) {
            throw new IllegalStateException("workflow form voice session is already complete");
        }
        String value = clean(transcript);
        if (value.isEmpty() || value.length() > MAX_TRANSCRIPT_CHARS) {
            throw new IllegalArgumentException("workflow form voice transcript is invalid");
        }
        completed = true;
        return value;
    }

    private static JSONObject field(WorkflowPackage.Node node, String fieldKey) {
        JSONArray fields = node.config().optJSONArray("fields");
        for (int index = 0; fields != null && index < fields.length(); index += 1) {
            JSONObject field = fields.optJSONObject(index);
            if (field == null) continue;
            String key = clean(field.optString("key", ""));
            if (key.isEmpty()) key = clean(field.optString("fieldKey", ""));
            if (fieldKey.equals(key)) return field;
        }
        return null;
    }

    private static boolean supportsVoiceInput(String type) {
        String value = clean(type).toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty() || "text".equals(value) || "textarea".equals(value)
                || "string".equals(value) || "number".equals(value)
                || "decimal".equals(value) || "integer".equals(value)
                || "boolean".equals(value) || "bool".equals(value)
                || "radio".equals(value) || "single_choice".equals(value)
                || "select".equals(value) || "choice".equals(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
