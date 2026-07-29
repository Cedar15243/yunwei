package com.k2fsa.sherpa.onnx;

public final class OnlineParaformerModelConfig {
    private String encoder;
    private String decoder;

    public OnlineParaformerModelConfig() { this("", ""); }
    public OnlineParaformerModelConfig(String encoder, String decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
    }
    public String getEncoder() { return encoder; }
    public String getDecoder() { return decoder; }
    public void setEncoder(String value) { encoder = value; }
    public void setDecoder(String value) { decoder = value; }
}
