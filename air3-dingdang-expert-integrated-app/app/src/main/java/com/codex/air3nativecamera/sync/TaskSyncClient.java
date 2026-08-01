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

    public TaskSyncClient(File queueFile, Transport transport) {
        this.queueFile = queueFile;
        this.transport = transport;
        this.queue = load(queueFile);
    }

    public synchronized boolean enqueue(TaskSyncEvent event) {
        boolean added = queue.enqueue(event);
        if (added) persist();
        return added;
    }

    public synchronized int pendingCount() {
        return queue.pending().size();
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

    private static TaskSyncQueue load(File queueFile) {
        if (queueFile == null || !queueFile.isFile()) return new TaskSyncQueue();
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
                return TaskSyncQueue.fromJson(new JSONArray(new String(bytes, 0, offset, UTF_8)));
            } finally {
                input.close();
            }
        } catch (Exception ignored) {
            return new TaskSyncQueue();
        }
    }

    private void persist() {
        if (queueFile == null) return;
        File directory = queueFile.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs()) return;
        File temporary = new File(queueFile.getPath() + ".tmp");
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                output.write(queue.toJson().toString().getBytes(UTF_8));
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (queueFile.exists() && !queueFile.delete()) return;
            if (!temporary.renameTo(queueFile)) temporary.delete();
        } catch (IOException ignored) {
            temporary.delete();
        }
    }
}
