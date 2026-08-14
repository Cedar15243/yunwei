package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void exposesDurableQueueErrorsToTheHost() throws Exception {
        java.io.File queueFile = temporaryFolder.newFile("corrupt-events.json");
        java.nio.file.Files.write(queueFile.toPath(), "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TaskSyncClient client = new TaskSyncClient(queueFile, event -> { });
        TaskSyncReporter reporter = new TaskSyncReporter(client, Runnable::run);

        assertTrue(reporter.persistenceError().contains("queue"));
    }

    @Test
    public void criticalTaskStartIsPersistedBeforeBackgroundDeliveryRuns() throws Exception {
        List<Runnable> jobs = new ArrayList<>();
        AtomicReference<TaskSyncEvent> delivered = new AtomicReference<>();
        TaskSyncClient client = new TaskSyncClient(
                temporaryFolder.newFile("critical-events.json"), delivered::set);
        TaskSyncReporter reporter = new TaskSyncReporter(client, jobs::add);

        reporter.recordCritical(event("task-a:task_started"));

        assertEquals(1, client.pendingCount());
        assertEquals(1, jobs.size());
        jobs.remove(0).run();
        assertEquals("task-a:task_started", delivered.get().idempotencyKey());
        assertEquals(0, client.pendingCount());
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

    @Test
    public void normalizesLongMultilineTitlesWithoutDroppingTheFullProblem() throws Exception {
        String problem = "服务器无法启动。\n" + repeat("需要核对电源、日志和诊断码。", 30);
        String projectTitle = "值班交接\n" + repeat("现场项目", 40);
        TaskSession session = new TaskSessionManager().startNew("project-local-long", problem);

        TaskSyncEvent event = TaskSyncEventFactory.create(
                session,
                projectTitle,
                "task_started",
                new JSONObject().put("problem", problem),
                1722400000000L,
                "event-long-title");

        assertTrue(event.projectTitle().length() <= 240);
        assertTrue(event.taskTitle().length() <= 240);
        assertFalse(event.projectTitle().contains("\n"));
        assertFalse(event.taskTitle().contains("\n"));
        assertEquals(problem, new JSONObject(event.payload()).getString("problem"));
        assertEquals(problem, session.maintenanceTask().initialProblem());
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

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
