package com.k2fsa.sherpa.onnx;

public final class OnlineNeMoCtcModelConfig {
    private String model;
    public OnlineNeMoCtcModelConfig() { this(""); }
    public OnlineNeMoCtcModelConfig(String model) { this.model = model; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
}
