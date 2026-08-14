package com.codex.air3nativecamera.voice;

/** Rejects late voiceprint callbacks after navigation or Activity shutdown. */
public final class VoiceprintAsyncRequestGate {
    private long generation;
    private boolean closed;

    public synchronized long begin() {
        if (closed) return -1L;
        return ++generation;
    }

    public synchronized void invalidate() {
        generation++;
    }

    public synchronized void close() {
        closed = true;
        generation++;
    }

    public synchronized boolean isCurrent(long requestGeneration) {
        return !closed && requestGeneration > 0L && requestGeneration == generation;
    }
}
