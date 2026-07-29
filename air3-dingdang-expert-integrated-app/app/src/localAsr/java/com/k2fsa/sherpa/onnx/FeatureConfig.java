package com.k2fsa.sherpa.onnx;

public final class FeatureConfig {
    private int sampleRate;
    private int featureDim;
    private float dither;

    public FeatureConfig() {
        this(16000, 80, 0.0f);
    }

    public FeatureConfig(int sampleRate, int featureDim, float dither) {
        this.sampleRate = sampleRate;
        this.featureDim = featureDim;
        this.dither = dither;
    }

    public int getSampleRate() { return sampleRate; }
    public int getFeatureDim() { return featureDim; }
    public float getDither() { return dither; }
    public void setSampleRate(int value) { sampleRate = value; }
    public void setFeatureDim(int value) { featureDim = value; }
    public void setDither(float value) { dither = value; }
}
