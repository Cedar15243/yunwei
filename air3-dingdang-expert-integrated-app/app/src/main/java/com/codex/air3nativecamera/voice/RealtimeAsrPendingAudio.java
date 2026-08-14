package com.codex.air3nativecamera.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded FIFO for PCM captured before a realtime ASR socket is writable. */
public final class RealtimeAsrPendingAudio {
    private final int maxBytes;
    private final ArrayList<byte[]> chunks = new ArrayList<>();
    private int byteCount;

    public RealtimeAsrPendingAudio(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized boolean offer(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return true;
        }
        if (pcm.length > maxBytes - byteCount) {
            return false;
        }
        chunks.add(pcm);
        byteCount += pcm.length;
        return true;
    }

    public synchronized List<byte[]> drain() {
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<byte[]> drained = new ArrayList<>(chunks);
        chunks.clear();
        byteCount = 0;
        return drained;
    }

    public synchronized int byteCount() {
        return byteCount;
    }
}
