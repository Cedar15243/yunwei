package com.codex.air3nativecamera.sync;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Builds the device event payload without exposing server-derived identity fields. */
public final class TaskSyncRequest {
    private TaskSyncRequest() {}

    public static String body(TaskSyncEvent event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        try {
            JSONObject payload = new JSONObject(event.payload());
            return new JSONObject()
                    .put("localProjectId", event.projectId())
                    .put("projectTitle", event.projectTitle())
                    .put("localTaskId", event.taskId())
                    .put("taskTitle", event.taskTitle())
                    .put("eventType", event.eventType())
                    .put("payload", payload)
                    .put("occurredAt", isoTimestamp(event.createdAt()))
                    .put("idempotencyKey", event.idempotencyKey())
                    .toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("event payload must be JSON", exception);
        }
    }

    private static String isoTimestamp(long timestamp) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(new Date(timestamp));
    }
}
