package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AudioCaptureCoordinatorTest {
    @Test
    public void defaultsToWakeModeAndAllowsOnlyOneMicrophoneOwner() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();

        assertEquals(AudioCaptureCoordinator.VoiceMode.WAKE, coordinator.voiceMode());
        assertTrue(coordinator.shouldStartWake());
        assertEquals(
                AudioCaptureCoordinator.AcquireResult.ACQUIRED,
                coordinator.acquire(AudioCaptureCoordinator.Owner.WAKE));
        assertEquals(
                AudioCaptureCoordinator.AcquireResult.BUSY,
                coordinator.acquire(AudioCaptureCoordinator.Owner.MANUAL_ASR));
    }

    @Test
    public void enablingVoiceprintStopsWakeAndDisablingItEntersPassiveMode() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.acquire(AudioCaptureCoordinator.Owner.WAKE);

        assertEquals(
                AudioCaptureCoordinator.ToggleResult.ENABLED,
                coordinator.toggleVoiceprint());
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.shouldStartWake());
        assertTrue(coordinator.shouldStartVoiceprint());

        assertEquals(
                AudioCaptureCoordinator.ToggleResult.DISABLED,
                coordinator.toggleVoiceprint());
        assertEquals(AudioCaptureCoordinator.VoiceMode.PASSIVE, coordinator.voiceMode());
        assertFalse(coordinator.shouldStartWake());
        assertFalse(coordinator.shouldStartVoiceprint());
    }

    @Test
    public void exclusiveMediaPreemptionDisablesVoiceprintWithoutFallingBackToWake() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.toggleVoiceprint();
        coordinator.acquire(AudioCaptureCoordinator.Owner.VOICEPRINT_CAPTURE);

        AudioCaptureCoordinator.Preemption preemption = coordinator.acquireExclusive(
                AudioCaptureCoordinator.Owner.EXPERT);

        assertEquals(AudioCaptureCoordinator.Owner.VOICEPRINT_CAPTURE, preemption.preemptedOwner());
        assertTrue(preemption.voiceprintDisabled());
        assertEquals(AudioCaptureCoordinator.VoiceMode.PASSIVE, coordinator.voiceMode());
        assertEquals(AudioCaptureCoordinator.Owner.EXPERT, coordinator.owner());

        coordinator.release(AudioCaptureCoordinator.Owner.EXPERT);

        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.shouldStartWake());
        assertFalse(coordinator.shouldStartVoiceprint());
    }

    @Test
    public void voiceprintFailureKeepsVoiceprintSelectedAndNeverSelectsWake() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.toggleVoiceprint();
        coordinator.acquire(AudioCaptureCoordinator.Owner.VOICEPRINT_VERIFY);

        coordinator.releaseAfterVoiceprintFailure();

        assertEquals(AudioCaptureCoordinator.VoiceMode.VOICEPRINT, coordinator.voiceMode());
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.shouldStartWake());
        assertTrue(coordinator.shouldStartVoiceprint());
    }

    @Test
    public void selectingWakeIsExplicitAfterPassiveMode() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.toggleVoiceprint();
        coordinator.toggleVoiceprint();

        coordinator.selectWakeMode();

        assertEquals(AudioCaptureCoordinator.VoiceMode.WAKE, coordinator.voiceMode());
        assertTrue(coordinator.shouldStartWake());
    }

    @Test
    public void manualAndWorkflowAsrUseTheSharedAsrOwnershipContract() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();

        assertEquals(
                AudioCaptureCoordinator.AcquireResult.ACQUIRED,
                coordinator.acquireAsr(false));
        assertEquals(AudioCaptureCoordinator.Owner.MANUAL_ASR, coordinator.owner());
        coordinator.releaseAsr();
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());

        assertEquals(
                AudioCaptureCoordinator.AcquireResult.ACQUIRED,
                coordinator.acquireAsr(true));
        assertEquals(AudioCaptureCoordinator.Owner.WORKFLOW_ASR, coordinator.owner());
        coordinator.releaseAsr();
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
    }

    @Test
    public void mediaPreemptionOfVerifiedVoiceprintAsrStaysPassiveAfterRelease() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.toggleVoiceprint();
        coordinator.acquireAsr(false);

        AudioCaptureCoordinator.Preemption preemption = coordinator.acquireExclusive(
                AudioCaptureCoordinator.Owner.CAMERA);

        assertEquals(AudioCaptureCoordinator.Owner.MANUAL_ASR, preemption.preemptedOwner());
        assertTrue(preemption.voiceprintDisabled());
        coordinator.release(AudioCaptureCoordinator.Owner.CAMERA);
        coordinator.releaseAsr();

        assertEquals(AudioCaptureCoordinator.VoiceMode.PASSIVE, coordinator.voiceMode());
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.shouldStartWake());
        assertFalse(coordinator.shouldStartVoiceprint());
    }

    @Test
    public void voiceprintEnrollmentHasDedicatedMicrophoneOwnershipOutsideListeningMode() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();

        assertEquals(
                AudioCaptureCoordinator.AcquireResult.ACQUIRED,
                coordinator.acquireVoiceprintEnrollment());
        assertEquals(
                AudioCaptureCoordinator.Owner.VOICEPRINT_ENROLLMENT,
                coordinator.owner());
        assertEquals(
                AudioCaptureCoordinator.AcquireResult.BUSY,
                coordinator.acquireAsr(false));
        assertEquals(
                AudioCaptureCoordinator.ToggleResult.BUSY,
                coordinator.toggleVoiceprint());

        coordinator.releaseVoiceprintEnrollment();
        assertEquals(AudioCaptureCoordinator.Owner.NONE, coordinator.owner());
        assertTrue(coordinator.shouldStartWake());
    }
}
