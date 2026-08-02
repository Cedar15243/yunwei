package com.codex.air3nativecamera.workflow;

import org.json.JSONObject;

/** Validated Air3 recording limits for one workflow video node. */
public final class WorkflowVideoCapturePlan {
    private static final String IDENTIFIER_PATTERN =
            "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$";

    private final String nodeId;
    private final String evidenceKey;
    private final int minimumCount;
    private final int minimumDurationSeconds;
    private final int maximumDurationSeconds;

    private WorkflowVideoCapturePlan(
            String nodeId,
            String evidenceKey,
            int minimumCount,
            int minimumDurationSeconds,
            int maximumDurationSeconds
    ) {
        this.nodeId = nodeId;
        this.evidenceKey = evidenceKey;
        this.minimumCount = minimumCount;
        this.minimumDurationSeconds = minimumDurationSeconds;
        this.maximumDurationSeconds = maximumDurationSeconds;
    }

    public static WorkflowVideoCapturePlan from(
            WorkflowPackage.Node node,
            int deviceMaximumDurationSeconds
    ) {
        if (node == null || !"video_capture".equals(node.type())
                || deviceMaximumDurationSeconds < 1) {
            throw new IllegalArgumentException("workflow video node is invalid");
        }
        JSONObject config = node.config();
        String evidenceKey = text(config.optString("evidenceKey", node.nodeId()));
        int minimumCount = exactInteger(config.opt("minCount"), 1);
        int minimumDuration = exactInteger(config.opt("minDurationSeconds"), 1);
        int maximumDuration = exactInteger(
                config.opt("maxDurationSeconds"), deviceMaximumDurationSeconds);
        if (!node.nodeId().matches(IDENTIFIER_PATTERN)
                || !evidenceKey.matches(IDENTIFIER_PATTERN)
                || minimumCount < 1 || minimumCount > 10
                || minimumDuration < 1
                || maximumDuration < minimumDuration
                || maximumDuration > deviceMaximumDurationSeconds) {
            throw new IllegalArgumentException("workflow video limits are invalid");
        }
        return new WorkflowVideoCapturePlan(
                node.nodeId(), evidenceKey, minimumCount,
                minimumDuration, maximumDuration);
    }

    public String nodeId() { return nodeId; }
    public String evidenceKey() { return evidenceKey; }
    public int minimumCount() { return minimumCount; }
    public int minimumDurationSeconds() { return minimumDurationSeconds; }
    public int maximumDurationSeconds() { return maximumDurationSeconds; }
    public long maximumDurationMillis() { return maximumDurationSeconds * 1000L; }

    public int completedDurationSeconds(long startedAtMillis, long completedAtMillis) {
        long elapsed = Math.max(0L, completedAtMillis - startedAtMillis);
        long seconds = elapsed / 1000L;
        return (int) Math.min(maximumDurationSeconds, seconds);
    }

    private static int exactInteger(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("workflow video integer is invalid");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("workflow video integer is invalid");
        }
        return (int) number;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
