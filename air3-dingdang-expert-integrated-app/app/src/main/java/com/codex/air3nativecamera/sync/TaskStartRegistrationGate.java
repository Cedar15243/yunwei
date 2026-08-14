package com.codex.air3nativecamera.sync;

import java.io.IOException;

/** Ensures the server knows the real task before the first strict-context AI request. */
public final class TaskStartRegistrationGate {
    private final TaskSyncClient client;

    public TaskStartRegistrationGate(TaskSyncClient client) {
        if (client == null) throw new IllegalArgumentException("task sync client is required");
        this.client = client;
    }

    public void ensureRegistered(AiExecutionContext context) throws IOException {
        if (context == null) throw new IOException("project_task_required");
        String idempotencyKey = context.localTaskId() + ":task_started";
        if (!client.deliverThrough(idempotencyKey, System.currentTimeMillis())) {
            throw new IOException("task_registration_pending");
        }
    }
}
