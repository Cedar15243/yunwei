package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

public final class TaskSyncReporterTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsAndDeliversAnEventOnTheInjectedBackgroundExecutor() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        AtomicReference<TaskSyncEvent> delivered = new AtomicReference<>();
        TaskSyncClient client = new TaskSyncClient(
                temporaryFolder.newFile("events.json"), delivered::set);
        TaskSyncReporter reporter = new TaskSyncReporter(client, jobs::add);
        TaskSyncEvent event = event("event-a");

        reporter.record(event);

        assertEquals(1, jobs.size());
        assertEquals(0, client.pendingCount());
        jobs.remove(0).run();
        assertEquals("event-a", delivered.get().idempotencyKey());
        assertEquals(0, client.pendingCount());
    }

    @Test
    public void keepsThePersistedEventWhenDeliveryFails() throws Exception {
        Executor direct = Runnable::run;
        TaskSyncClient client = new TaskSyncClient(temporaryFolder.newFile("failed-events.json"), event -> {
            throw new IOException("offline");
        });
        TaskSyncReporter reporter = new TaskSyncReporter(client, direct);

        reporter.record(event("event-offline"));

        assertEquals(1, client.pendingCount());
    }

    @Test
    public void buildsAnEventFromThePersistedTaskIdentity() throws Exception {
        TaskSession session = new TaskSessionManager().startNew("project-local-a", "温湿度异常");

        TaskSyncEvent event = TaskSyncEventFactory.create(
                session,
                "机房巡检",
                "user_message",
                new JSONObject().put("text", "开始检查"),
                1722400000000L,
                "event-user-a");

        assertEquals("project-local-a", event.projectId());
        assertEquals("机房巡检", event.projectTitle());
        assertEquals(session.id(), event.taskId());
        assertEquals("温湿度异常", event.taskTitle());
        assertEquals("开始检查", new JSONObject(event.payload()).getString("text"));
    }

    private static TaskSyncEvent event(String idempotencyKey) {
        return new TaskSyncEvent(
                "project-a",
                "机房巡检",
                "task-a",
                "温湿度异常",
                "task_started",
                "{\"problem\":\"温湿度异常\"}",
                1722400000000L,
                idempotencyKey);
    }
}
