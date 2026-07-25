package com.codex.air3nativecamera.voice;

/** Keeps repeated UI scheduling from cycling an already active wake session. */
public final class WakeListeningSchedulePolicy {
    public enum Action {
        START,
        KEEP_RUNNING,
        STOP
    }

    private WakeListeningSchedulePolicy() {
    }

    public static Action decide(boolean enabled, boolean shouldListen, boolean running) {
        if (!enabled || !shouldListen) {
            return Action.STOP;
        }
        return running ? Action.KEEP_RUNNING : Action.START;
    }
}
