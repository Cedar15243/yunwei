package com.codex.air3nativecamera.voice;

/** Keeps the photo and its spoken description together as one AI event. */
public final class VoiceEventStateMachine {
    public enum State {
        IDLE,
        CAPTURE_REQUESTED,
        WAITING_FOR_DESCRIPTION,
        AI_READY
    }

    public enum Signal {
        NONE,
        CAPTURE_PHOTO,
        START_DESCRIPTION,
        SUBMIT_TO_AI,
        CANCEL_EVENT
    }

    private State state = State.IDLE;
    private String eventDescription = "";
    private boolean hasPhoto;

    public State state() {
        return state;
    }

    public String eventDescription() {
        return eventDescription;
    }

    public boolean hasPhoto() {
        return hasPhoto;
    }

    public Signal onCommand(VoiceCommandRouter.Command command) {
        if (command == VoiceCommandRouter.Command.PHOTO) {
            reset();
            state = State.CAPTURE_REQUESTED;
            return Signal.CAPTURE_PHOTO;
        }
        if (command == VoiceCommandRouter.Command.RETAKE && hasPhoto) {
            state = State.CAPTURE_REQUESTED;
            return Signal.CAPTURE_PHOTO;
        }
        if (command == VoiceCommandRouter.Command.CANCEL) {
            reset();
            return Signal.CANCEL_EVENT;
        }
        if ((command == VoiceCommandRouter.Command.FINISH || command == VoiceCommandRouter.Command.SUBMIT)
                && hasPhoto && eventDescription.length() > 0) {
            state = State.AI_READY;
            return Signal.SUBMIT_TO_AI;
        }
        return Signal.NONE;
    }

    public Signal onPhotoCaptured() {
        if (state != State.CAPTURE_REQUESTED) {
            return Signal.NONE;
        }
        hasPhoto = true;
        state = State.WAITING_FOR_DESCRIPTION;
        return Signal.START_DESCRIPTION;
    }

    public Signal onDescriptionFinal(String transcript) {
        if (state != State.WAITING_FOR_DESCRIPTION) {
            return Signal.NONE;
        }
        eventDescription = transcript == null ? "" : transcript.trim();
        state = State.AI_READY;
        return Signal.SUBMIT_TO_AI;
    }

    public Signal onDescriptionTimeout() {
        if (state != State.WAITING_FOR_DESCRIPTION) {
            return Signal.NONE;
        }
        state = State.AI_READY;
        return Signal.SUBMIT_TO_AI;
    }

    public void reset() {
        state = State.IDLE;
        eventDescription = "";
        hasPhoto = false;
    }
}
