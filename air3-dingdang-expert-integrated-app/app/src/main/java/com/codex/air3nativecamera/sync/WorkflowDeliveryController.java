package com.codex.air3nativecamera.sync;

import java.util.function.LongPredicate;

/** Joins foreground, connectivity and future push signals without doing work on the UI thread. */
public final class WorkflowDeliveryController implements AutoCloseable {
    public interface NetworkMonitor extends AutoCloseable {
        void start(Runnable onNetworkAvailable);

        @Override
        void close();
    }

    private final Runnable sessionPrewarm;
    private final LongPredicate syncRequester;
    private final NetworkMonitor networkMonitor;
    private final Runnable shutdown;
    private boolean started;
    private boolean closed;

    public WorkflowDeliveryController(
            Runnable sessionPrewarm,
            LongPredicate syncRequester,
            NetworkMonitor networkMonitor,
            Runnable shutdown
    ) {
        if (sessionPrewarm == null || syncRequester == null
                || networkMonitor == null || shutdown == null) {
            throw new IllegalArgumentException("workflow delivery controller configuration is invalid");
        }
        this.sessionPrewarm = sessionPrewarm;
        this.syncRequester = syncRequester;
        this.networkMonitor = networkMonitor;
        this.shutdown = shutdown;
    }

    public void start() {
        synchronized (this) {
            if (started || closed) return;
            started = true;
        }
        try {
            networkMonitor.start(new Runnable() {
                @Override
                public void run() {
                    trigger(true, 0L);
                }
            });
        } catch (RuntimeException exception) {
            synchronized (this) {
                started = false;
            }
            throw exception;
        }
    }

    public void onForeground() {
        trigger(true, 0L);
    }

    public void onPushHint(long hintedSequence) {
        if (hintedSequence < 0L) {
            throw new IllegalArgumentException("workflow push sequence is invalid");
        }
        trigger(false, hintedSequence);
    }

    private void trigger(boolean prewarm, long hintedSequence) {
        synchronized (this) {
            if (!started || closed) return;
        }
        if (prewarm) {
            try {
                sessionPrewarm.run();
            } catch (RuntimeException ignored) {
                // The background sync obtains or reports the authoritative session result.
            }
        }
        synchronized (this) {
            if (!started || closed) return;
        }
        try {
            syncRequester.test(hintedSequence);
        } catch (RuntimeException ignored) {
            // A later foreground, network or push signal retries without crashing the UI.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            started = false;
        }
        try {
            networkMonitor.close();
        } catch (RuntimeException ignored) {
        }
        try {
            shutdown.run();
        } catch (RuntimeException ignored) {
        }
    }
}
