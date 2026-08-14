package com.codex.air3nativecamera.runtime;

/** Keeps device activation QR intent alive while Android is asking for camera permission. */
public final class DeviceActivationQrCaptureState {
    private boolean pending;

    public void start() {
        pending = true;
    }

    public void onPause() {
        // Permission dialogs pause the activity; the scan must survive that pause.
    }

    public boolean onCameraPermissionResult(boolean granted) {
        if (granted) return false;
        boolean wasPending = pending;
        pending = false;
        return wasPending;
    }

    public boolean consumePhoto() {
        if (!pending) return false;
        pending = false;
        return true;
    }

    public void cancel() {
        pending = false;
    }

    public boolean isPending() {
        return pending;
    }
}
