package com.codex.air3nativecamera.sync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Moves task-event persistence and delivery away from the Android UI thread. */
public final class TaskSyncReporter implements AutoCloseable {
    private static final long RETRY_INTERVAL_SECONDS = 30L;
    private final TaskSyncClient client;
    private final Executor executor;
    private final ScheduledExecutorService ownedExecutor;

    public TaskSyncReporter(TaskSyncClient client) {
        this.client = client;
        this.ownedExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "task-sync");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor = ownedExecutor;
        ownedExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                deliverReadyEvents();
            }
        }, 0L, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    TaskSyncReporter(TaskSyncClient client, Executor executor) {
        this.client = client;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    public void record(final TaskSyncEvent event) {
        if (client == null || event == null) return;
        execute(new Runnable() {
            @Override
            public void run() {
                client.enqueue(event);
                deliverReadyEvents();
            }
        });
    }

    public void recordCritical(final TaskSyncEvent event) {
        if (client == null || event == null) return;
        client.enqueue(event);
        execute(new Runnable() {
            @Override
            public void run() {
                deliverReadyEvents();
            }
        });
    }

    public void flush() {
        if (client == null) return;
        execute(new Runnable() {
            @Override
            public void run() {
                deliverReadyEvents();
            }
        });
    }

    /** Makes durable queue corruption visible to the host HUD instead of hiding it in the outbox. */
    public String persistenceError() {
        return client == null ? "" : client.persistenceError();
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private void deliverReadyEvents() {
        while (client.pendingCount() > 0 && client.deliverNext(System.currentTimeMillis())) {
            // Drain successful events in order. A failure remains persisted for the next retry.
        }
    }

    private void execute(Runnable work) {
        try {
            executor.execute(work);
        } catch (RejectedExecutionException ignored) {
            // The Activity is already shutting down. A previously persisted event remains queued.
        }
    }
}
