package com.codex.expertcollab;

public final class CollabStateMachine {
    public enum State {
        IDLE,
        CALLING,
        CONNECTING,
        IN_CALL,
        RECONNECTING,
        ENDED,
        FAILED
    }

    private State state = State.IDLE;
    private String expertId;

    public synchronized State getState() {
        return state;
    }

    public synchronized String getExpertId() {
        return expertId;
    }

    public synchronized boolean shouldReplayPendingCallOnSignalingConnected() {
        return state == State.CALLING;
    }

    public synchronized State onPrimaryAction() {
        if (state == State.IDLE || state == State.ENDED || state == State.FAILED) {
            expertId = null;
            state = State.CALLING;
            return state;
        }
        state = State.ENDED;
        return state;
    }

    public synchronized State onAccepted(String acceptedExpertId) {
        requireState(State.CALLING, "accept a call");
        if (acceptedExpertId == null || acceptedExpertId.trim().isEmpty()) {
            throw new IllegalArgumentException("expertId is required");
        }
        expertId = acceptedExpertId;
        state = State.CONNECTING;
        return state;
    }

    public synchronized State onMediaConnected() {
        if (state != State.CONNECTING && state != State.RECONNECTING) {
            throw new IllegalStateException("Cannot connect media while state is " + state);
        }
        state = State.IN_CALL;
        return state;
    }

    public synchronized State onConnectionLost() {
        requireState(State.IN_CALL, "reconnect");
        state = State.RECONNECTING;
        return state;
    }

    public synchronized State onFailure() {
        state = State.FAILED;
        return state;
    }

    public synchronized State onEnded() {
        state = State.ENDED;
        return state;
    }

    private void requireState(State expected, String operation) {
        if (state != expected) {
            throw new IllegalStateException("Cannot " + operation + " while state is " + state);
        }
    }
}
