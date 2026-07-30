package com.codex.expertcollab;

final class CollabReconnectPolicy {
    private static final long[] DELAYS_MS = {500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L};
    private int attempt;

    synchronized long nextDelayMs() {
        int index = Math.min(attempt, DELAYS_MS.length - 1);
        attempt++;
        return DELAYS_MS[index];
    }

    synchronized void reset() {
        attempt = 0;
    }
}
