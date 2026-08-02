package com.codex.air3nativecamera.workflow;

import org.json.JSONObject;

/** Validated Air3 recording limits for one workflow voice input node. */
public final class WorkflowVoiceInputPlan {
    private static final String IDENTIFIER_PATTERN =
            "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$";

    private final String nodeId;
    private final String fieldKey;
    private final int maximumDurationSeconds;
    private final boolean required;

    private WorkflowVoiceInputPlan(
            String nodeId,
            String fieldKey,
            int maximumDurationSeconds,
            boolean required
    ) {
        this.nodeId = nodeId;
        this.fieldKey = fieldKey;
        this.maximumDurationSeconds = maximumDurationSeconds;
        this.required = required;
    }

    public static WorkflowVoiceInputPlan from(
            WorkflowPackage.Node node,
            int deviceMaximumDurationSeconds
    ) {
        if (node == null || !"voice_input".equals(node.type())
                || deviceMaximumDurationSeconds < 1) {
            throw new IllegalArgumentException("workflow voice node is invalid");
        }
        JSONObject config = node.config();
        String fieldKey = text(config.optString("fieldKey", ""));
        int maximumDuration = exactInteger(config.opt("maxDurationSeconds"));
        Object requiredValue = config.opt("required");
        if (!node.nodeId().matches(IDENTIFIER_PATTERN)
                || !fieldKey.matches(IDENTIFIER_PATTERN)
                || maximumDuration < 1
                || maximumDuration > deviceMaximumDurationSeconds
                || !(requiredValue instanceof Boolean)) {
            throw new IllegalArgumentException("workflow voice limits are invalid");
        }
        return new WorkflowVoiceInputPlan(
                node.nodeId(), fieldKey, maximumDuration, (Boolean) requiredValue);
    }

    public String nodeId() { return nodeId; }
    public String fieldKey() { return fieldKey; }
    public int maximumDurationSeconds() { return maximumDurationSeconds; }
    public long maximumDurationMillis() { return maximumDurationSeconds * 1000L; }
    public boolean required() { return required; }

    private static int exactInteger(Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("workflow voice integer is invalid");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("workflow voice integer is invalid");
        }
        return (int) number;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
