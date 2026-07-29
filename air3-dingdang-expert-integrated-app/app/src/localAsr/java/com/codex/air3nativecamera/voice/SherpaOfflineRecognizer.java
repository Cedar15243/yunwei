package com.codex.air3nativecamera.voice;

import android.content.Context;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.onnx.OnlineLMConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

public final class SherpaOfflineRecognizer {
    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_DIR =
            "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23";

    private final OnlineRecognizer recognizer;
    private OnlineStream stream;

    public SherpaOfflineRecognizer(Context context) {
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
                MODEL_DIR + "/encoder-epoch-99-avg-1.int8.onnx",
                MODEL_DIR + "/decoder-epoch-99-avg-1.onnx",
                MODEL_DIR + "/joiner-epoch-99-avg-1.int8.onnx");
        OnlineModelConfig model = new OnlineModelConfig(
                transducer,
                new OnlineParaformerModelConfig(),
                new OnlineZipformer2CtcModelConfig(),
                new OnlineNeMoCtcModelConfig(),
                new OnlineToneCtcModelConfig(),
                MODEL_DIR + "/tokens.txt",
                2,
                false,
                "cpu",
                "zipformer",
                "",
                "");
        OnlineRecognizerConfig config = new OnlineRecognizerConfig(
                new FeatureConfig(SAMPLE_RATE, 80, 0.0f),
                model,
                new OnlineLMConfig(),
                new OnlineCtcFstDecoderConfig(),
                new HomophoneReplacerConfig(),
                new EndpointConfig(),
                false,
                "greedy_search",
                4,
                "",
                1.5f,
                "itn_zh_number.fst",
                "",
                0.0f);
        recognizer = new OnlineRecognizer(context.getAssets(), config);
    }

    public void start() {
        releaseSession();
        stream = recognizer.createStream("");
    }

    public String acceptPcm16(byte[] pcm, int length) {
        if (stream == null) {
            start();
        }
        float[] samples = pcm16ToFloat(pcm, length);
        if (samples.length == 0) {
            return currentText();
        }
        stream.acceptWaveform(samples, SAMPLE_RATE);
        decodeReadyFrames();
        return currentText();
    }

    public String finish() {
        if (stream == null) {
            return "";
        }
        stream.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
        stream.inputFinished();
        decodeReadyFrames();
        return currentText();
    }

    public void releaseSession() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }

    public void release() {
        releaseSession();
        recognizer.release();
    }

    private void decodeReadyFrames() {
        int guard = 0;
        while (stream != null && recognizer.isReady(stream) && guard++ < 1000) {
            recognizer.decode(stream);
        }
    }

    private String currentText() {
        if (stream == null) {
            return "";
        }
        OnlineRecognizerResult result = recognizer.getResult(stream);
        return result == null || result.getText() == null ? "" : result.getText().trim();
    }

    static float[] pcm16ToFloat(byte[] pcm, int length) {
        if (pcm == null || length <= 1) {
            return new float[0];
        }
        int safeLength = Math.min(length, pcm.length) & ~1;
        float[] samples = new float[safeLength / 2];
        for (int i = 0, sampleIndex = 0; i < safeLength; i += 2, sampleIndex++) {
            int low = pcm[i] & 0xff;
            int high = pcm[i + 1];
            short sample = (short) ((high << 8) | low);
            samples[sampleIndex] = sample / 32768.0f;
        }
        return samples;
    }
}
