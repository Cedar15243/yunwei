package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceprintEnrollmentFlowTest {
    @Test
    public void requiresConsentThenThreeOrderedSamplesAndIndependentVerification() {
        VoiceprintEnrollmentFlow flow = new VoiceprintEnrollmentFlow();

        flow.apply(false, "", "voiceprint_not_enrolled", 0, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.CONSENT_REQUIRED, flow.stage());

        flow.apply(true, "enrolling", "", 0, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.ENROLLING, flow.stage());
        assertEquals(1, flow.nextSampleIndex());

        flow.apply(true, "enrolling", "", 1, false);
        assertEquals(2, flow.nextSampleIndex());
        flow.apply(true, "enrolling", "", 2, false);
        assertEquals(3, flow.nextSampleIndex());

        flow.apply(true, "pending_verification", "", 3, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.VERIFICATION_REQUIRED, flow.stage());
        assertFalse(flow.canCaptureEnrollmentSample());
        assertTrue(flow.canVerify());

        flow.apply(true, "active", "", 3, true);
        assertEquals(VoiceprintEnrollmentFlow.Stage.ACTIVE, flow.stage());
        assertTrue(flow.isActive());
    }

    @Test
    public void lockedAndProviderFailureRemainExplicit() {
        VoiceprintEnrollmentFlow flow = new VoiceprintEnrollmentFlow();

        flow.apply(false, "locked", "voiceprint_locked", 3, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.LOCKED, flow.stage());
        assertFalse(flow.isActive());

        flow.apply(false, "", "voiceprint_unavailable", 0, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.UNAVAILABLE, flow.stage());
        assertEquals("voiceprint_unavailable", flow.error());
    }

    @Test
    public void deletedOrRevokedProfilesRequireFreshConsent() {
        VoiceprintEnrollmentFlow flow = new VoiceprintEnrollmentFlow();

        flow.apply(true, "deleted", "", 0, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.CONSENT_REQUIRED, flow.stage());

        flow.apply(true, "revoked", "", 0, false);
        assertEquals(VoiceprintEnrollmentFlow.Stage.CONSENT_REQUIRED, flow.stage());
    }
}
