package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered, idempotent queue. Callers own persistence and perform only one delivery at a time. */
public final class TaskSyncQueue {
    private final Map<String, TaskSyncEvent> events = new LinkedHashMap<>();

    public synchronized boolean enqueue(TaskSyncEvent event) {
        if (event == null || event.idempotencyKey().isEmpty() || events.containsKey(event.idempotencyKey())) {
            return false;
        }
        events.put(event.idempotencyKey(), event);
        return true;
    }

    public synchronized List<TaskSyncEvent> pending() {
        return Collections.unmodifiableList(new ArrayList<>(events.values()));
    }

    public synchronized TaskSyncEvent nextReady(long now) {
        for (TaskSyncEvent event : events.values()) {
            if (event.nextAttemptAt() <= now) return event;
        }
        return null;
    }

    public synchronized boolean markSucceeded(String idempotencyKey) {
        return events.remove(idempotencyKey) != null;
    }

    public synchronized boolean markFailed(String idempotencyKey, String reason, long now) {
        TaskSyncEvent event = events.get(idempotencyKey);
        if (event == null) return false;
        events.put(idempotencyKey, event.failedAt(now, reason));
        return true;
    }

    public synchronized JSONArray toJson() {
        JSONArray values = new JSONArray();
        for (TaskSyncEvent event : events.values()) values.put(event.toJson());
        return values;
    }

    public static TaskSyncQueue fromJson(JSONArray values) {
        TaskSyncQueue queue = new TaskSyncQueue();
        if (values == null) return queue;
        for (int index = 0; index < values.length(); index += 1) {
            try {
                if (values.optJSONObject(index) != null) queue.enqueue(TaskSyncEvent.fromJson(values.getJSONObject(index)));
            } catch (JSONException ignored) {
                // One corrupt event must not prevent the remaining evidence from being synchronized.
            }
        }
        return queue;
    }
}
