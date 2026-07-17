package com.codex.air3nativecamera.mode;

public final class IntegratedModeController {
    public enum Mode {
        CHAT,
        CAMERA,
        EXPERT
    }

    public interface Hooks {
        void persistLegacyState();

        void stopLegacyVoice();

        void closeLegacyCamera();

        void showExpert();

        void releaseExpert();

        void showChat();

        void startLegacyCamera();

        void resumeLegacyVoice();
    }

    private final Hooks hooks;
    private Mode mode = Mode.CHAT;

    public IntegratedModeController(Hooks hooks) {
        if (hooks == null) {
            throw new IllegalArgumentException("mode hooks are required");
        }
        this.hooks = hooks;
    }

    public Mode mode() {
        return mode;
    }

    public void setLegacyMode(Mode legacyMode) {
        if (legacyMode == null || legacyMode == Mode.EXPERT) {
            throw new IllegalArgumentException("legacy mode must be CHAT or CAMERA");
        }
        if (mode != Mode.EXPERT) {
            mode = legacyMode;
        }
    }

    public void enterExpert() {
        if (mode == Mode.EXPERT) {
            return;
        }
        hooks.persistLegacyState();
        hooks.stopLegacyVoice();
        hooks.closeLegacyCamera();
        hooks.showExpert();
        mode = Mode.EXPERT;
    }

    public void exitExpert() {
        if (mode != Mode.EXPERT) {
            return;
        }
        hooks.releaseExpert();
        hooks.showChat();
        hooks.startLegacyCamera();
        hooks.resumeLegacyVoice();
        mode = Mode.CHAT;
    }
}
