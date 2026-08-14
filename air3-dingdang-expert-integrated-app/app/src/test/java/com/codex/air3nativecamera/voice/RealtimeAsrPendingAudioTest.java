package com.codex.air3nativecamera.voice;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RealtimeAsrPendingAudioTest {
    @Test
    public void drainsEarlyAudioInCaptureOrderAfterTheBackendSocketConnects() {
        RealtimeAsrPendingAudio pending = new RealtimeAsrPendingAudio(16);

        assertTrue(pending.offer(new byte[]{1, 2}));
        assertTrue(pending.offer(new byte[]{3, 4, 5}));

        List<byte[]> drained = pending.drain();

        assertEquals(2, drained.size());
        assertArrayEquals(new byte[]{1, 2}, drained.get(0));
        assertArrayEquals(new byte[]{3, 4, 5}, drained.get(1));
        assertEquals(0, pending.byteCount());
        assertTrue(pending.drain().isEmpty());
    }

    @Test
    public void rejectsAudioBeyondTheBoundedWeakNetworkBuffer() {
        RealtimeAsrPendingAudio pending = new RealtimeAsrPendingAudio(4);

        assertTrue(pending.offer(new byte[]{1, 2, 3}));
        assertFalse(pending.offer(new byte[]{4, 5}));
        assertEquals(3, pending.byteCount());
    }
}
