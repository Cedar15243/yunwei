package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MvsWorkOrderEvidenceCaptureStateTest {
    @Test
    public void acceptsOnlyTheActiveOrderAndConsumesCaptureOnce() {
        MvsWorkOrderEvidenceCaptureState state = new MvsWorkOrderEvidenceCaptureState();

        assertFalse(state.begin("42", "43"));
        assertTrue(state.begin("42", "42"));
        assertEquals("42", state.pendingOrderId());
        assertEquals("42", state.consume("42"));
        assertEquals("", state.pendingOrderId());
        assertEquals("", state.consume("42"));
    }

    @Test
    public void cancelClearsPendingCaptureWithoutAffectingAnotherOrder() {
        MvsWorkOrderEvidenceCaptureState state = new MvsWorkOrderEvidenceCaptureState();

        assertTrue(state.begin("42", "42"));
        state.cancel();
        assertEquals("", state.pendingOrderId());
        assertFalse(state.begin("", "42"));
        assertTrue(state.begin("43", "43"));
        assertEquals("43", state.pendingOrderId());
    }
}
