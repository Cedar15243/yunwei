package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.json.JSONObject;

public final class TaskSyncQueueTest {
    @Test
    public void coalescesSameIdempotencyKey() {
        TaskSyncQueue queue = new TaskSyncQueue();
        queue.enqueue(event("task-a", "key-1"));
        queue.enqueue(event("task-a", "key-1"));

        assertEquals(1, queue.pending().size());
    }

    @Test
    public void leavesFailedEventReadyForRetry() {
        TaskSyncQueue queue = new TaskSyncQueue();
        queue.enqueue(event("task-a", "key-1"));
        queue.markFailed("key-1", "timeout", 1000L);

        assertNotNull(queue.nextReady(1000L));
        assertEquals("key-1", queue.nextReady(1000L).idempotencyKey());
    }

    @Test
    public void neverSkipsABackedOffHeadEventToSendALaterWorkflowEvent() {
        TaskSyncQueue queue = new TaskSyncQueue();
        queue.enqueue(event("task-a", "key-1"));
        queue.enqueue(event("task-a", "key-2"));
        queue.enqueue(event("task-b", "key-3"));
        queue.markFailed("key-1", "timeout-1", 1000L);
        queue.markFailed("key-1", "timeout-2", 1000L);

        assertEquals("key-3", queue.nextReady(1500L).idempotencyKey());
        queue.markSucceeded("key-3");
        assertNull(queue.nextReady(1500L));
        assertEquals("key-1", queue.nextReady(2000L).idempotencyKey());
    }

    @Test
    public void acceptsOnlyHttpsManagedDeviceSyncConfiguration() {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                " https://ops.example.com/functions/v1/ops-glasses/device-sync/events/ ",
                " device-token ");

        assertNotNull(configuration);
        assertEquals("https://ops.example.com/functions/v1/ops-glasses/device-sync/events",
                configuration.endpoint());
        assertEquals("https://ops.example.com/functions/v1/ops-glasses/device-sync/session",
                configuration.sessionEndpoint());
        assertEquals("device-token", configuration.bootstrapCredential());
        assertNull(DeviceSyncConfiguration.fromManagedValues("http://ops.example.com/events", "device-token"));
        assertNull(DeviceSyncConfiguration.fromManagedValues("https://ops.example.com/events", ""));
    }

    @Test
    public void serializesOnlyDeviceEventFieldsForTransmission() throws Exception {
        JSONObject body = new JSONObject(TaskSyncRequest.body(new TaskSyncEvent(
                "project-a", "机房巡检", "task-a", "温湿度异常", "ai_response", "{\"text\":\"已完成\"}", 1722400000000L, "event-a")));

        assertEquals("project-a", body.getString("localProjectId"));
        assertEquals("机房巡检", body.getString("projectTitle"));
        assertEquals("task-a", body.getString("localTaskId"));
        assertEquals("温湿度异常", body.getString("taskTitle"));
        assertEquals("ai_response", body.getString("eventType"));
        assertEquals("已完成", body.getJSONObject("payload").getString("text"));
        assertEquals("event-a", body.getString("idempotencyKey"));
        assertEquals(false, body.has("organizationId"));
        assertEquals(false, body.has("deviceToken"));
    }

    private static TaskSyncEvent event(String taskId, String key) {
        return new TaskSyncEvent("project-a", taskId, "user_message", "{}", 1000L, key);
    }
}
