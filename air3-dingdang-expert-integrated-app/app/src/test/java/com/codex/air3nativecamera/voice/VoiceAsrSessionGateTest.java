package com.codex.air3nativecamera.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VoiceAsrSessionGateTest {
    @Test
    public void invalidatedSessionCannotDeliverLateAsrCallbacks() {
        VoiceAsrSessionGate gate = new VoiceAsrSessionGate();
        long first = gate.begin();

        assertTrue(gate.accepts(first));
        gate.invalidate();

        assertFalse(gate.accepts(first));
        assertTrue(gate.accepts(gate.begin()));
    }
}
