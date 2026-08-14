package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceprintAsyncRequestGateTest {
    @Test
    public void invalidationRejectsALateVoiceprintResponse() {
        VoiceprintAsyncRequestGate gate = new VoiceprintAsyncRequestGate();
        long request = gate.begin();

        assertTrue(gate.isCurrent(request));

        gate.invalidate();

        assertFalse(gate.isCurrent(request));
        assertTrue(gate.isCurrent(gate.begin()));
    }

    @Test
    public void closingRejectsCurrentAndFutureVoiceprintResponses() {
        VoiceprintAsyncRequestGate gate = new VoiceprintAsyncRequestGate();
        long request = gate.begin();

        gate.close();

        assertFalse(gate.isCurrent(request));
        assertFalse(gate.isCurrent(gate.begin()));
    }
}
