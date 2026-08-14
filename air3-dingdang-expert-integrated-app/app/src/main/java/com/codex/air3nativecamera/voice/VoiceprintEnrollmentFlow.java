package com.codex.air3nativecamera.voice;

/** Maps normalized gateway profile results into the enrollment stages shown on the glasses. */
public final class VoiceprintEnrollmentFlow {
    public enum Stage {
        CONSENT_REQUIRED,
        ENROLLING,
        VERIFICATION_REQUIRED,
        ACTIVE,
        LOCKED,
        UNAVAILABLE
    }

    private Stage stage = Stage.UNAVAILABLE;
    private int sampleCount;
    private String error = "voiceprint_profile_unavailable";

    public synchronized void apply(
            boolean ok,
            String status,
            String error,
            int sampleCount,
            boolean verified) {
        String safeStatus = clean(status);
        String safeError = clean(error);
        this.sampleCount = Math.max(0, Math.min(3, sampleCount));
        this.error = safeError;

        if (!ok) {
            if ("voiceprint_not_enrolled".equals(safeError)
                    || "voiceprint_consent_required".equals(safeError)) {
                stage = Stage.CONSENT_REQUIRED;
                this.sampleCount = 0;
                return;
            }
            if ("voiceprint_locked".equals(safeError) || "locked".equals(safeStatus)) {
                stage = Stage.LOCKED;
                return;
            }
            stage = Stage.UNAVAILABLE;
            return;
        }

        this.error = "";
        if (verified || "active".equals(safeStatus)) {
            stage = Stage.ACTIVE;
        } else if ("locked".equals(safeStatus)) {
            stage = Stage.LOCKED;
        } else if ("pending_verification".equals(safeStatus)) {
            stage = Stage.VERIFICATION_REQUIRED;
        } else if ("enrolling".equals(safeStatus)) {
            stage = Stage.ENROLLING;
        } else if ("new".equals(safeStatus)
                || "deleted".equals(safeStatus)
                || "revoked".equals(safeStatus)) {
            stage = Stage.CONSENT_REQUIRED;
            this.sampleCount = 0;
        } else {
            stage = Stage.UNAVAILABLE;
            this.error = "voiceprint_profile_state_invalid";
        }
    }

    public synchronized Stage stage() {
        return stage;
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    public synchronized int nextSampleIndex() {
        return canCaptureEnrollmentSample() ? sampleCount + 1 : 0;
    }

    public synchronized boolean canCaptureEnrollmentSample() {
        return stage == Stage.ENROLLING && sampleCount < 3;
    }

    public synchronized boolean canVerify() {
        return stage == Stage.VERIFICATION_REQUIRED;
    }

    public synchronized boolean isActive() {
        return stage == Stage.ACTIVE;
    }

    public synchronized String error() {
        return error;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
