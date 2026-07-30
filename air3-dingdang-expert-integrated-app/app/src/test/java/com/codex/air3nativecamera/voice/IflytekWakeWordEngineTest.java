package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IflytekWakeWordEngineTest {
    @Test
    public void usesTheBalancedSingleUtteranceWakeThreshold() {
        assertEquals("0 0:850", IflytekWakeWordEngine.wakeThresholdParameter());
    }

    @Test
    public void pcmDiagnosticsReportSilenceAndSignalWithoutStoringAudio() {
        byte[] silence = new byte[1280];
        assertEquals(-120, IflytekWakeWordEngine.pcmRmsDbfs(silence, silence.length));
        assertEquals(0, IflytekWakeWordEngine.pcmPeak(silence, silence.length));

        byte[] signal = new byte[]{0, 64, 0, -64};
        assertTrue(IflytekWakeWordEngine.pcmRmsDbfs(signal, signal.length) > -20);
        assertEquals(16384, IflytekWakeWordEngine.pcmPeak(signal, signal.length));
    }

    @Test
    public void restartBackoffIsBoundedAndResetsAfterAHealthySession() {
        assertEquals(250L, IflytekWakeWordEngine.restartDelayMillis(0));
        assertEquals(500L, IflytekWakeWordEngine.restartDelayMillis(1));
        assertEquals(8_000L, IflytekWakeWordEngine.restartDelayMillis(20));
    }

    @Test
    public void permanentAudioReadErrorsAbortTheCurrentSession() {
        assertTrue(IflytekWakeWordEngine.shouldAbortAudioRead(-2, 0));
        assertTrue(IflytekWakeWordEngine.shouldAbortAudioRead(-3, 0));
        assertTrue(IflytekWakeWordEngine.shouldAbortAudioRead(-6, 0));
        assertTrue(IflytekWakeWordEngine.shouldAbortAudioRead(0, 3));
    }
}
