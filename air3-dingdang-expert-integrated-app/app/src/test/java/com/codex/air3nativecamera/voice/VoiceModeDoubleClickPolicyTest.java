package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceModeDoubleClickPolicyTest {
    @Test
    public void firstPressDefersTheExistingSingleClickAction() {
        VoiceModeDoubleClickPolicy policy = new VoiceModeDoubleClickPolicy(320L);

        assertEquals(VoiceModeDoubleClickPolicy.Action.DEFER_SINGLE, policy.onPress(1_000L));
        assertFalse(policy.isSingleDue(1_319L));
        assertTrue(policy.isSingleDue(1_320L));
        assertTrue(policy.consumeSingleIfDue(1_320L));
        assertFalse(policy.hasPendingSingle());
    }

    @Test
    public void secondPressInsideTheWindowConsumesBothClicksAsDoubleClick() {
        VoiceModeDoubleClickPolicy policy = new VoiceModeDoubleClickPolicy(320L);

        policy.onPress(2_000L);
        assertEquals(VoiceModeDoubleClickPolicy.Action.DOUBLE_CLICK, policy.onPress(2_240L));
        assertFalse(policy.hasPendingSingle());
        assertFalse(policy.consumeSingleIfDue(3_000L));
    }

    @Test
    public void aThirdPressStartsANewSingleClickWindow() {
        VoiceModeDoubleClickPolicy policy = new VoiceModeDoubleClickPolicy(320L);

        policy.onPress(3_000L);
        policy.onPress(3_200L);

        assertEquals(VoiceModeDoubleClickPolicy.Action.DEFER_SINGLE, policy.onPress(3_300L));
        assertTrue(policy.hasPendingSingle());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnUnsafeDoubleClickWindow() {
        new VoiceModeDoubleClickPolicy(50L);
    }
}
