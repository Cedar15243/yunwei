package com.codex.air3nativecamera.sync;

import com.codex.air3nativecamera.task.TaskSession;

import org.json.JSONObject;

/** Builds the stable device-event envelope from a locally persisted task session. */
public final class TaskSyncEventFactory {
    private static final int MAX_SYNC_TITLE_LENGTH = 240;

    private TaskSyncEventFactory() {
    }

    public static TaskSyncEvent create(
            TaskSession session,
            String projectTitle,
            String eventType,
            JSONObject payload,
            long occurredAt,
            String idempotencyKey
    ) {
        if (session == null) {
            throw new IllegalArgumentException("task session is required");
        }
        return new TaskSyncEvent(
                session.projectId(),
                contractTitle(projectTitle),
                session.id(),
                contractTitle(session.maintenanceTask().initialProblem()),
                eventType,
                payload == null ? "{}" : payload.toString(),
                occurredAt,
                idempotencyKey);
    }

    private static String contractTitle(String value) {
        String source = value == null ? "" : value.trim();
        StringBuilder normalized = new StringBuilder(
                Math.min(source.length(), MAX_SYNC_TITLE_LENGTH));
        boolean pendingSpace = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character < 32 || Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace && normalized.length() < MAX_SYNC_TITLE_LENGTH) {
                normalized.append(' ');
            }
            pendingSpace = false;
            if (normalized.length() >= MAX_SYNC_TITLE_LENGTH) break;
            normalized.append(character);
        }
        if (normalized.length() > MAX_SYNC_TITLE_LENGTH) {
            normalized.setLength(MAX_SYNC_TITLE_LENGTH);
        }
        if (normalized.length() > 0
                && Character.isHighSurrogate(normalized.charAt(normalized.length() - 1))) {
            normalized.setLength(normalized.length() - 1);
        }
        return normalized.toString().trim();
    }
}
