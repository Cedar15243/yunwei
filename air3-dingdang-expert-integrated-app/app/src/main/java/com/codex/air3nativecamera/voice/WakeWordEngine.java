package com.codex.air3nativecamera.voice;

/**
 * Local wake-word boundary. Implementations must not stream standby microphone audio to a server.
 * The 629 preview uses explicit interaction entry until an on-device engine is selected and validated.
 */
public interface WakeWordEngine {
    interface Listener {
        void onWakeWordDetected();

        default void onEngineUnavailable(String reason) {
        }
    }

    void start(Listener listener);

    void stop();

    boolean isRunning();
}
