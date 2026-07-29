package com.k2fsa.sherpa.onnx;

public final class OnlineZipformer2CtcModelConfig {
    private String model;
    public OnlineZipformer2CtcModelConfig() { this(""); }
    public OnlineZipformer2CtcModelConfig(String model) { this.model = model; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
}
