package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class TaskStartRegistrationGateTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void deliversThePersistedTaskStartBeforeTheFirstAiRequest() throws Exception {
        List<String> delivered = new ArrayList<>();
        TaskSyncClient client = new TaskSyncClient(
                temporaryFolder.newFile("events.json"),
                event -> delivered.add(event.idempotencyKey()));
        TaskSession task = new TaskSessionManager().startNew("project-a", "检查控制器");
        client.enqueue(event(task, "task_started", task.id() + ":task_started"));
        client.enqueue(event(task, "user_message", "message-a"));

        new TaskStartRegistrationGate(client).ensureRegistered(
                new AiExecutionContext(task.projectId(), task.id()));

        assertEquals(1, delivered.size());
        assertEquals(task.id() + ":task_started", delivered.get(0));
        assertEquals(1, client.pendingCount());
    }

    @Test(expected = IOException.class)
    public void offlineRegistrationStopsTheAiRequestAndKeepsTheOutbox() throws Exception {
        TaskSyncClient client = new TaskSyncClient(
                temporaryFolder.newFile("offline-events.json"),
                event -> { throw new IOException("offline"); });
        TaskSession task = new TaskSessionManager().startNew("project-a", "检查控制器");
        client.enqueue(event(task, "task_started", task.id() + ":task_started"));

        try {
            new TaskStartRegistrationGate(client).ensureRegistered(
                    new AiExecutionContext(task.projectId(), task.id()));
        } finally {
            assertEquals(1, client.pendingCount());
        }
    }

    private static TaskSyncEvent event(
            TaskSession task,
            String eventType,
            String idempotencyKey
    ) {
        return new TaskSyncEvent(
                task.projectId(),
                "冷站维护",
                task.id(),
                task.maintenanceTask().initialProblem(),
                eventType,
                "{}",
                1722400000000L,
                idempotencyKey);
    }
}
