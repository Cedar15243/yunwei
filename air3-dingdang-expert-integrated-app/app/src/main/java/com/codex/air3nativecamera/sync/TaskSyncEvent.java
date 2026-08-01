package com.codex.air3nativecamera.sync;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable task event that can be safely persisted before network delivery. */
public final class TaskSyncEvent {
    private final String projectId;
    private final String projectTitle;
    private final String taskId;
    private final String taskTitle;
    private final String eventType;
    private final String payload;
    private final long createdAt;
    private final String idempotencyKey;
    private final int failureCount;
    private final long nextAttemptAt;
    private final String lastFailure;

    public TaskSyncEvent(
            String projectId,
            String taskId,
            String eventType,
            String payload,
            long createdAt,
            String idempotencyKey
    ) {
        this(projectId, "", taskId, "", eventType, payload, createdAt, idempotencyKey);
    }

    public TaskSyncEvent(
            String projectId,
            String projectTitle,
            String taskId,
            String taskTitle,
            String eventType,
            String payload,
            long createdAt,
            String idempotencyKey
    ) {
        this(projectId, projectTitle, taskId, taskTitle, eventType, payload, createdAt,
                idempotencyKey, 0, createdAt, "");
    }

    private TaskSyncEvent(
            String projectId,
            String projectTitle,
            String taskId,
            String taskTitle,
            String eventType,
            String payload,
            long createdAt,
            String idempotencyKey,
            int failureCount,
            long nextAttemptAt,
            String lastFailure
    ) {
        this.projectId = value(projectId);
        this.projectTitle = value(projectTitle);
        this.taskId = value(taskId);
        this.taskTitle = value(taskTitle);
        this.eventType = value(eventType);
        this.payload = payload == null || payload.trim().isEmpty() ? "{}" : payload;
        this.createdAt = createdAt;
        this.idempotencyKey = value(idempotencyKey);
        this.failureCount = Math.max(0, failureCount);
        this.nextAttemptAt = nextAttemptAt;
        this.lastFailure = value(lastFailure);
    }

    public String projectId() { return projectId; }
    public String projectTitle() { return projectTitle; }
    public String taskId() { return taskId; }
    public String taskTitle() { return taskTitle; }
    public String eventType() { return eventType; }
    public String payload() { return payload; }
    public long createdAt() { return createdAt; }
    public String idempotencyKey() { return idempotencyKey; }
    public int failureCount() { return failureCount; }
    public long nextAttemptAt() { return nextAttemptAt; }
    public String lastFailure() { return lastFailure; }

    TaskSyncEvent failedAt(long now, String reason) {
        int nextFailureCount = failureCount + 1;
        return new TaskSyncEvent(
                projectId,
                projectTitle,
                taskId,
                taskTitle,
                eventType,
                payload,
                createdAt,
                idempotencyKey,
                nextFailureCount,
                now + retryDelayMillis(nextFailureCount),
                reason
        );
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("project_id", projectId)
                    .put("project_title", projectTitle)
                    .put("task_id", taskId)
                    .put("task_title", taskTitle)
                    .put("event_type", eventType)
                    .put("payload", payload)
                    .put("created_at", createdAt)
                    .put("idempotency_key", idempotencyKey)
                    .put("failure_count", failureCount)
                    .put("next_attempt_at", nextAttemptAt)
                    .put("last_failure", lastFailure);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to serialize task sync event", exception);
        }
    }

    static TaskSyncEvent fromJson(JSONObject json) {
        return new TaskSyncEvent(
                json.optString("project_id", ""),
                json.optString("project_title", ""),
                json.optString("task_id", ""),
                json.optString("task_title", ""),
                json.optString("event_type", ""),
                json.optString("payload", "{}"),
                json.optLong("created_at", 0L),
                json.optString("idempotency_key", ""),
                json.optInt("failure_count", 0),
                json.optLong("next_attempt_at", 0L),
                json.optString("last_failure", "")
        );
    }

    static TaskSyncEvent fromJsonStrict(JSONObject json) {
        if (json == null
                || !validRequiredText(json.optString("event_type", ""), 160)
                || !validRequiredText(json.optString("idempotency_key", ""), 240)
                || !(json.opt("created_at") instanceof Number)
                || json.optLong("created_at", -1L) < 0L
                || !(json.opt("failure_count") instanceof Number)
                || json.optInt("failure_count", -1) < 0
                || !(json.opt("next_attempt_at") instanceof Number)
                || json.optLong("next_attempt_at", -1L) < 0L
                || json.optString("last_failure", "").length() > 1000) {
            throw new IllegalArgumentException("task sync event snapshot is invalid");
        }
        String payload = json.optString("payload", "");
        try {
            if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262_144) {
                throw new IllegalArgumentException("task sync event payload is too large");
            }
            new JSONObject(payload);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("task sync event payload is invalid", exception);
        }
        return fromJson(json);
    }

    private static long retryDelayMillis(int failureCount) {
        // Allow one immediate retry, then back off without ever exceeding one minute.
        if (failureCount <= 1) return 0L;
        int exponent = Math.min(16, failureCount - 2);
        return Math.min(60_000L, 1_000L << exponent);
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }

    private static boolean validRequiredText(String input, int maxLength) {
        String text = value(input);
        if (text.isEmpty() || text.length() > maxLength) return false;
        for (int index = 0; index < text.length(); index += 1) {
            if (Character.isISOControl(text.charAt(index))) return false;
        }
        return true;
    }
}
