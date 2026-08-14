package com.codex.air3nativecamera.voice;

/** Defers the existing send-key action just long enough to distinguish a double click. */
public final class VoiceModeDoubleClickPolicy {
    public enum Action {
        DEFER_SINGLE,
        DOUBLE_CLICK
    }

    private final long windowMillis;
    private long firstPressAtMillis = -1L;

    public VoiceModeDoubleClickPolicy(long windowMillis) {
        if (windowMillis < 150L || windowMillis > 500L) {
            throw new IllegalArgumentException("unsafe_double_click_window");
        }
        this.windowMillis = windowMillis;
    }

    public synchronized Action onPress(long nowMillis) {
        if (firstPressAtMillis >= 0L
                && nowMillis >= firstPressAtMillis
                && nowMillis - firstPressAtMillis <= windowMillis) {
            firstPressAtMillis = -1L;
            return Action.DOUBLE_CLICK;
        }
        firstPressAtMillis = nowMillis;
        return Action.DEFER_SINGLE;
    }

    public synchronized boolean hasPendingSingle() {
        return firstPressAtMillis >= 0L;
    }

    public synchronized boolean isSingleDue(long nowMillis) {
        return firstPressAtMillis >= 0L
                && nowMillis >= firstPressAtMillis + windowMillis;
    }

    public synchronized boolean consumeSingleIfDue(long nowMillis) {
        if (!isSingleDue(nowMillis)) {
            return false;
        }
        firstPressAtMillis = -1L;
        return true;
    }

    public synchronized void cancel() {
        firstPressAtMillis = -1L;
    }
}
