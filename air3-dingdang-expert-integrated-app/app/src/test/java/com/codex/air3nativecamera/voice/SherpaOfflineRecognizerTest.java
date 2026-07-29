package com.codex.air3nativecamera.voice;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public final class SherpaOfflineRecognizerTest {
    @Test
    public void pcm16LittleEndianIsConvertedToNormalizedFloats() {
        byte[] pcm = new byte[] {
                0x00, 0x00,
                (byte) 0xff, 0x7f,
                0x00, (byte) 0x80,
                0x00, 0x40
        };

        assertArrayEquals(
                new float[] {0.0f, 32767.0f / 32768.0f, -1.0f, 0.5f},
                SherpaOfflineRecognizer.pcm16ToFloat(pcm, pcm.length),
                0.00001f);
    }

    @Test
    public void oddTrailingByteIsIgnored() {
        byte[] pcm = new byte[] {0x01, 0x00, 0x7f};

        assertArrayEquals(
                new float[] {1.0f / 32768.0f},
                SherpaOfflineRecognizer.pcm16ToFloat(pcm, pcm.length),
                0.00001f);
    }
}
