package com.k2fsa.sherpa.onnx;

public final class OnlineToneCtcModelConfig {
    private String model;
    public OnlineToneCtcModelConfig() { this(""); }
    public OnlineToneCtcModelConfig(String model) { this.model = model; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
}
