package com.k2fsa.sherpa.onnx;

public final class OnlineLMConfig {
    private String model;
    private float scale;
    public OnlineLMConfig() { this("", 0.5f); }
    public OnlineLMConfig(String model, float scale) { this.model = model; this.scale = scale; }
    public String getModel() { return model; }
    public float getScale() { return scale; }
    public void setModel(String value) { model = value; }
    public void setScale(float value) { scale = value; }
}
