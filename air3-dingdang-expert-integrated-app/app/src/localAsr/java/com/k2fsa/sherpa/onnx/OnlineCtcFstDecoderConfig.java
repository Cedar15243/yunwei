package com.k2fsa.sherpa.onnx;

public final class OnlineCtcFstDecoderConfig {
    private String graph;
    private int maxActive;
    public OnlineCtcFstDecoderConfig() { this("", 3000); }
    public OnlineCtcFstDecoderConfig(String graph, int maxActive) {
        this.graph = graph;
        this.maxActive = maxActive;
    }
    public String getGraph() { return graph; }
    public int getMaxActive() { return maxActive; }
    public void setGraph(String value) { graph = value; }
    public void setMaxActive(int value) { maxActive = value; }
}
