package com.codex.air3nativecamera.sync;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * File-backed delivery coordinator. The transport is deliberately injected so credentials and
 * device identity remain outside the queue until the server-side enrollment contract is defined.
 */
public final class TaskSyncClient {
    public interface Transport {
        void send(TaskSyncEvent event) throws IOException;
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final File queueFile;
    private final Transport transport;
    private TaskSyncQueue queue;
    private boolean delivering;
    private String persistenceError;

    public TaskSyncClient(File queueFile, Transport transport) {
        this.queueFile = queueFile;
        this.transport = transport;
        LoadResult loaded = load(queueFile);
        this.queue = loaded.queue;
        this.persistenceError = loaded.error;
    }

    public synchronized boolean enqueue(TaskSyncEvent event) {
        boolean added = queue.enqueue(event);
        if (added) persist();
        return added;
    }

    public synchronized int pendingCount() {
        return queue.pending().size();
    }

    /** Returns the last durable queue error, or an empty string when storage is healthy. */
    public synchronized String persistenceError() {
        return persistenceError == null ? "" : persistenceError;
    }

    public boolean deliverThrough(String idempotencyKey, long now) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty()) return false;
        int attempts = 0;
        while (isPending(key)) {
            if (attempts++ >= 2000 || !deliverNext(now)) return false;
        }
        return true;
    }

    /** Delivers at most one event so callers can schedule without blocking application workflows. */
    public synchronized boolean deliverNext(long now) {
        if (delivering || transport == null) return false;
        TaskSyncEvent event = queue.nextReady(now);
        if (event == null) return false;
        delivering = true;
        try {
            transport.send(event);
            queue.markSucceeded(event.idempotencyKey());
            persist();
            return true;
        } catch (IOException exception) {
            queue.markFailed(event.idempotencyKey(), exception.getMessage(), now);
            persist();
            return false;
        } finally {
            delivering = false;
        }
    }

    private synchronized boolean isPending(String idempotencyKey) {
        for (TaskSyncEvent event : queue.pending()) {
            if (idempotencyKey.equals(event.idempotencyKey())) return true;
        }
        return false;
    }

    private static LoadResult load(File queueFile) {
        if (queueFile == null || !queueFile.isFile()) return new LoadResult(new TaskSyncQueue(), null);
        if (queueFile.length() == 0L) return new LoadResult(new TaskSyncQueue(), null);
        try {
            FileInputStream input = new FileInputStream(queueFile);
            try {
                byte[] bytes = new byte[(int) queueFile.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                return new LoadResult(
                        TaskSyncQueue.fromJsonStrict(new JSONArray(new String(bytes, 0, offset, UTF_8))),
                        null
                );
            } finally {
                input.close();
            }
        } catch (Exception exception) {
            String preservedPath = preserveCorruptSnapshot(queueFile);
            return new LoadResult(
                    new TaskSyncQueue(),
                    "task sync queue load failed: " + safeMessage(exception)
                            + (preservedPath.isEmpty() ? "" : " (preserved at " + preservedPath + ")")
            );
        }
    }

    private void persist() {
        if (queueFile == null) {
            persistenceError = "task sync queue file is unavailable";
            return;
        }
        File directory = queueFile.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            persistenceError = "task sync queue directory could not be created";
            return;
        }
        File temporary = new File(queueFile.getPath() + ".tmp");
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                output.write(queue.toJson().toString().getBytes(UTF_8));
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (queueFile.exists() && !queueFile.delete()) {
                persistenceError = "task sync queue could not replace the persisted snapshot";
                return;
            }
            if (!temporary.renameTo(queueFile)) {
                persistenceError = "task sync queue snapshot could not be committed";
                temporary.delete();
                return;
            }
            persistenceError = null;
        } catch (IOException exception) {
            persistenceError = "task sync queue persist failed: " + safeMessage(exception);
            temporary.delete();
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static String preserveCorruptSnapshot(File queueFile) {
        if (queueFile == null || !queueFile.isFile()) return "";
        File preserved = new File(queueFile.getPath() + ".corrupt-" + System.currentTimeMillis());
        try {
            FileInputStream input = new FileInputStream(queueFile);
            try {
                FileOutputStream output = new FileOutputStream(preserved);
                try {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) output.write(buffer, 0, read);
                    }
                    output.getFD().sync();
                } finally {
                    output.close();
                }
            } finally {
                input.close();
            }
            return preserved.getPath();
        } catch (IOException ignored) {
            preserved.delete();
            return "";
        }
    }

    private static final class LoadResult {
        private final TaskSyncQueue queue;
        private final String error;

        private LoadResult(TaskSyncQueue queue, String error) {
            this.queue = queue;
            this.error = error;
        }
    }
}
