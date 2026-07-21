package com.codex.air3nativecamera.voice;

/** Prevents cancelled or superseded ASR sessions from changing the active event. */
public final class VoiceAsrSessionGate {
    private long currentSession;

    public long begin() {
        return ++currentSession;
    }

    public void invalidate() {
        ++currentSession;
    }

    public boolean accepts(long session) {
        return session == currentSession;
    }
}
