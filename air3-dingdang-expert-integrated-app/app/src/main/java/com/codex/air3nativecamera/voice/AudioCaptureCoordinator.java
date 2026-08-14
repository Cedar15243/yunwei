package com.codex.air3nativecamera.voice;

/** Owns microphone mode and exclusive media transitions without touching Android APIs. */
public final class AudioCaptureCoordinator {
    public enum VoiceMode {
        WAKE,
        VOICEPRINT,
        PASSIVE
    }

    public enum Owner {
        NONE,
        WAKE,
        VOICEPRINT_CAPTURE,
        VOICEPRINT_VERIFY,
        VOICEPRINT_ENROLLMENT,
        MANUAL_ASR,
        WORKFLOW_ASR,
        CAMERA,
        VIDEO,
        EXPERT
    }

    public enum AcquireResult {
        ACQUIRED,
        ALREADY_OWNED,
        BUSY,
        DISABLED
    }

    public enum ToggleResult {
        ENABLED,
        DISABLED,
        BUSY
    }

    public static final class Preemption {
        private final Owner preemptedOwner;
        private final boolean voiceprintDisabled;

        private Preemption(Owner preemptedOwner, boolean voiceprintDisabled) {
            this.preemptedOwner = preemptedOwner;
            this.voiceprintDisabled = voiceprintDisabled;
        }

        public Owner preemptedOwner() {
            return preemptedOwner;
        }

        public boolean voiceprintDisabled() {
            return voiceprintDisabled;
        }
    }

    private VoiceMode voiceMode = VoiceMode.WAKE;
    private Owner owner = Owner.NONE;

    public synchronized VoiceMode voiceMode() {
        return voiceMode;
    }

    public synchronized Owner owner() {
        return owner;
    }

    public synchronized boolean shouldStartWake() {
        return voiceMode == VoiceMode.WAKE && owner == Owner.NONE;
    }

    public synchronized boolean shouldStartVoiceprint() {
        return voiceMode == VoiceMode.VOICEPRINT && owner == Owner.NONE;
    }

    public synchronized ToggleResult toggleVoiceprint() {
        if (isExclusive(owner) || owner == Owner.VOICEPRINT_ENROLLMENT
                || owner == Owner.MANUAL_ASR || owner == Owner.WORKFLOW_ASR) {
            return ToggleResult.BUSY;
        }
        if (voiceMode == VoiceMode.VOICEPRINT) {
            voiceMode = VoiceMode.PASSIVE;
            owner = Owner.NONE;
            return ToggleResult.DISABLED;
        }
        voiceMode = VoiceMode.VOICEPRINT;
        owner = Owner.NONE;
        return ToggleResult.ENABLED;
    }

    public synchronized void selectWakeMode() {
        if (isExclusive(owner)) {
            return;
        }
        voiceMode = VoiceMode.WAKE;
        owner = Owner.NONE;
    }

    public synchronized void selectPassiveMode() {
        if (isExclusive(owner)) {
            return;
        }
        voiceMode = VoiceMode.PASSIVE;
        owner = Owner.NONE;
    }

    public synchronized AcquireResult acquire(Owner requested) {
        if (requested == null || requested == Owner.NONE || isExclusive(requested)) {
            throw new IllegalArgumentException("invalid_audio_owner");
        }
        if (requested == Owner.WAKE && voiceMode != VoiceMode.WAKE) {
            return AcquireResult.DISABLED;
        }
        if ((requested == Owner.VOICEPRINT_CAPTURE || requested == Owner.VOICEPRINT_VERIFY)
                && voiceMode != VoiceMode.VOICEPRINT) {
            return AcquireResult.DISABLED;
        }
        if (owner == requested) {
            return AcquireResult.ALREADY_OWNED;
        }
        if (owner != Owner.NONE) {
            return AcquireResult.BUSY;
        }
        owner = requested;
        return AcquireResult.ACQUIRED;
    }

    public synchronized AcquireResult acquireAsr(boolean workflowInput) {
        return acquire(workflowInput ? Owner.WORKFLOW_ASR : Owner.MANUAL_ASR);
    }

    public synchronized AcquireResult acquireVoiceprintEnrollment() {
        return acquire(Owner.VOICEPRINT_ENROLLMENT);
    }

    public synchronized Preemption acquireExclusive(Owner requested) {
        if (!isExclusive(requested)) {
            throw new IllegalArgumentException("non_exclusive_audio_owner");
        }
        Owner preempted = owner;
        boolean disabled = voiceMode == VoiceMode.VOICEPRINT;
        if (disabled) {
            voiceMode = VoiceMode.PASSIVE;
        }
        owner = requested;
        return new Preemption(preempted, disabled);
    }

    public synchronized void release(Owner released) {
        if (released != null && owner == released) {
            owner = Owner.NONE;
        }
    }

    public synchronized void releaseAsr() {
        if (owner == Owner.MANUAL_ASR || owner == Owner.WORKFLOW_ASR) {
            owner = Owner.NONE;
        }
    }

    public synchronized void releaseVoiceprintEnrollment() {
        release(Owner.VOICEPRINT_ENROLLMENT);
    }

    public synchronized void releaseAfterVoiceprintFailure() {
        if (owner == Owner.VOICEPRINT_CAPTURE || owner == Owner.VOICEPRINT_VERIFY) {
            owner = Owner.NONE;
        }
    }

    private static boolean isExclusive(Owner value) {
        return value == Owner.CAMERA || value == Owner.VIDEO || value == Owner.EXPERT;
    }
}
