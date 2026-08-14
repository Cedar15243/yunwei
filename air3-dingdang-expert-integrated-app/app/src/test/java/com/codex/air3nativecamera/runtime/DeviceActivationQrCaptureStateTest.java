package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceActivationQrCaptureStateTest {
    @Test
    public void pauseKeepsThePendingScanAcrossTheCameraPermissionDialog() {
        DeviceActivationQrCaptureState state = new DeviceActivationQrCaptureState();

        state.start();
        state.onPause();

        assertTrue(state.isPending());
    }

    @Test
    public void permissionDenialEndsTheScanButPermissionGrantKeepsItPending() {
        DeviceActivationQrCaptureState state = new DeviceActivationQrCaptureState();

        state.start();
        assertFalse(state.onCameraPermissionResult(true));
        assertTrue(state.isPending());

        assertTrue(state.onCameraPermissionResult(false));
        assertFalse(state.isPending());
    }

    @Test
    public void explicitCancelAndPhotoConsumptionEndTheScan() {
        DeviceActivationQrCaptureState state = new DeviceActivationQrCaptureState();

        state.start();
        assertTrue(state.consumePhoto());
        assertFalse(state.isPending());
        assertFalse(state.consumePhoto());

        state.start();
        state.cancel();
        assertFalse(state.isPending());
    }
}
