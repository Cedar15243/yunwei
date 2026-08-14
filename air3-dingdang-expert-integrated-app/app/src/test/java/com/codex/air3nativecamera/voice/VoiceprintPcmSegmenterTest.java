package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VoiceprintPcmSegmenterTest {
    private static final int SAMPLE_RATE = 16_000;

    @Test
    public void silenceTimesOutWithoutProducingBiometricAudio() {
        VoiceprintPcmSegmenter segmenter = new VoiceprintPcmSegmenter(
                SAMPLE_RATE, 500, 3_000, 800, 10_000);

        VoiceprintPcmSegmenter.Decision decision = VoiceprintPcmSegmenter.Decision.CONTINUE;
        for (int i = 0; i < 10; i++) {
            decision = segmenter.accept(pcm(1_000, 0), 2 * SAMPLE_RATE);
        }

        assertEquals(VoiceprintPcmSegmenter.Decision.NO_SPEECH_TIMEOUT, decision);
        assertEquals(0, segmenter.wavBytes().length);
    }

    @Test
    public void speechThenSilenceCompletesOnlyAfterTheThreeSecondMinimum() {
        VoiceprintPcmSegmenter segmenter = new VoiceprintPcmSegmenter(
                SAMPLE_RATE, 500, 3_000, 800, 10_000);

        assertEquals(
                VoiceprintPcmSegmenter.Decision.CONTINUE,
                segmenter.accept(pcm(1_500, 4_000), 3 * SAMPLE_RATE));
        assertEquals(
                VoiceprintPcmSegmenter.Decision.CONTINUE,
                segmenter.accept(pcm(700, 0), 1_400 * SAMPLE_RATE / 1_000));
        assertEquals(
                VoiceprintPcmSegmenter.Decision.COMPLETE,
                segmenter.accept(pcm(800, 0), 1_600 * SAMPLE_RATE / 1_000));

        byte[] wav = segmenter.wavBytes();
        assertTrue(wav.length > 44);
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals(16_000, littleEndianInt(wav, 24));
        assertEquals(1, littleEndianShort(wav, 22));
        assertEquals(16, littleEndianShort(wav, 34));
    }

    @Test
    public void maximumDurationCompletesSpeechAndCapsTheBuffer() {
        VoiceprintPcmSegmenter segmenter = new VoiceprintPcmSegmenter(
                SAMPLE_RATE, 500, 3_000, 800, 10_000);

        VoiceprintPcmSegmenter.Decision decision = segmenter.accept(
                pcm(12_000, 3_000), 24 * SAMPLE_RATE);

        assertEquals(VoiceprintPcmSegmenter.Decision.COMPLETE, decision);
        assertEquals(44 + 10 * SAMPLE_RATE * 2, segmenter.wavBytes().length);
    }

    @Test
    public void resetDropsTheCapturedBytesBeforeTheNextSegment() {
        VoiceprintPcmSegmenter segmenter = new VoiceprintPcmSegmenter(
                SAMPLE_RATE, 500, 3_000, 800, 10_000);
        segmenter.accept(pcm(3_000, 3_000), 6 * SAMPLE_RATE);

        segmenter.reset();

        assertEquals(0, segmenter.pcmByteCount());
        assertFalse(segmenter.speechSeen());
        assertEquals(0, segmenter.wavBytes().length);
    }

    private static byte[] pcm(int durationMillis, int amplitude) {
        int samples = durationMillis * SAMPLE_RATE / 1_000;
        ByteBuffer output = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            output.putShort((short) (i % 2 == 0 ? amplitude : -amplitude));
        }
        return output.array();
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }
}
