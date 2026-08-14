package com.codex.air3nativecamera.voice;

import java.util.Arrays;

/** Captures one bounded 16 kHz PCM segment in memory before any network transcription. */
public final class VoiceprintPcmSegmenter {
    public enum Decision {
        CONTINUE,
        COMPLETE,
        NO_SPEECH_TIMEOUT
    }

    private final int sampleRate;
    private final int rmsThreshold;
    private final int minimumSamples;
    private final int silenceSamples;
    private final int maximumSamples;
    private final byte[] pcm;

    private int pcmBytes;
    private int lastSpeechSample;
    private boolean speechSeen;
    private Decision terminalDecision = Decision.CONTINUE;

    public VoiceprintPcmSegmenter(
            int sampleRate,
            int rmsThreshold,
            int minimumDurationMillis,
            int silenceDurationMillis,
            int maximumDurationMillis) {
        if (sampleRate <= 0 || rmsThreshold <= 0
                || minimumDurationMillis < 3_000
                || silenceDurationMillis < 200
                || maximumDurationMillis < minimumDurationMillis
                || maximumDurationMillis > 10_000) {
            throw new IllegalArgumentException("invalid_voiceprint_segmenter_configuration");
        }
        this.sampleRate = sampleRate;
        this.rmsThreshold = rmsThreshold;
        minimumSamples = durationSamples(minimumDurationMillis);
        silenceSamples = durationSamples(silenceDurationMillis);
        maximumSamples = durationSamples(maximumDurationMillis);
        pcm = new byte[maximumSamples * 2];
    }

    public synchronized Decision accept(byte[] input, int length) {
        if (terminalDecision != Decision.CONTINUE) {
            return terminalDecision;
        }
        if (input == null || length <= 0) {
            return Decision.CONTINUE;
        }
        int safeLength = Math.min(Math.min(length, input.length), pcm.length - pcmBytes);
        safeLength -= safeLength % 2;
        if (safeLength > 0) {
            System.arraycopy(input, 0, pcm, pcmBytes, safeLength);
            pcmBytes += safeLength;
            if (pcm16Rms(input, safeLength) >= rmsThreshold) {
                speechSeen = true;
                lastSpeechSample = pcmBytes / 2;
            }
        }

        int totalSamples = pcmBytes / 2;
        if (speechSeen
                && totalSamples >= minimumSamples
                && totalSamples - lastSpeechSample >= silenceSamples) {
            terminalDecision = Decision.COMPLETE;
        } else if (totalSamples >= maximumSamples) {
            terminalDecision = speechSeen
                    ? Decision.COMPLETE
                    : Decision.NO_SPEECH_TIMEOUT;
        }
        return terminalDecision;
    }

    public synchronized byte[] wavBytes() {
        if (terminalDecision != Decision.COMPLETE || !speechSeen || pcmBytes <= 0) {
            return new byte[0];
        }
        byte[] wav = new byte[44 + pcmBytes];
        writeAscii(wav, 0, "RIFF");
        writeInt(wav, 4, 36 + pcmBytes);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeInt(wav, 16, 16);
        writeShort(wav, 20, 1);
        writeShort(wav, 22, 1);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, sampleRate * 2);
        writeShort(wav, 32, 2);
        writeShort(wav, 34, 16);
        writeAscii(wav, 36, "data");
        writeInt(wav, 40, pcmBytes);
        System.arraycopy(pcm, 0, wav, 44, pcmBytes);
        return wav;
    }

    public synchronized byte[] pcmBytesCopy() {
        return terminalDecision == Decision.COMPLETE && speechSeen
                ? Arrays.copyOf(pcm, pcmBytes)
                : new byte[0];
    }

    public synchronized int pcmByteCount() {
        return pcmBytes;
    }

    public synchronized boolean speechSeen() {
        return speechSeen;
    }

    public synchronized void reset() {
        Arrays.fill(pcm, (byte) 0);
        pcmBytes = 0;
        lastSpeechSample = 0;
        speechSeen = false;
        terminalDecision = Decision.CONTINUE;
    }

    private int durationSamples(int durationMillis) {
        return sampleRate * durationMillis / 1_000;
    }

    private static int pcm16Rms(byte[] bytes, int length) {
        int samples = length / 2;
        if (samples <= 0) return 0;
        long sumSquares = 0L;
        for (int index = 0; index + 1 < length; index += 2) {
            int low = bytes[index] & 0xff;
            int high = bytes[index + 1];
            short sample = (short) ((high << 8) | low);
            sumSquares += (long) sample * sample;
        }
        return (int) Math.sqrt(sumSquares / (double) samples);
    }

    private static void writeAscii(byte[] output, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            output[offset + index] = (byte) value.charAt(index);
        }
    }

    private static void writeInt(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >> 8);
        output[offset + 2] = (byte) (value >> 16);
        output[offset + 3] = (byte) (value >> 24);
    }

    private static void writeShort(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >> 8);
    }
}
