package com.codex.air3nativecamera.sync;

import com.codex.air3nativecamera.task.TaskSession;

import org.json.JSONObject;

/** Builds the stable device-event envelope from a locally persisted task session. */
public final class TaskSyncEventFactory {
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
                projectTitle,
                session.id(),
                session.maintenanceTask().initialProblem(),
                eventType,
                payload == null ? "{}" : payload.toString(),
                occurredAt,
                idempotencyKey);
    }
}
