package com.k2fsa.sherpa.onnx;

public final class OnlineTransducerModelConfig {
    private String encoder;
    private String decoder;
    private String joiner;

    public OnlineTransducerModelConfig() {
        this("", "", "");
    }

    public OnlineTransducerModelConfig(String encoder, String decoder, String joiner) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
    }

    public String getEncoder() { return encoder; }
    public String getDecoder() { return decoder; }
    public String getJoiner() { return joiner; }
    public void setEncoder(String value) { encoder = value; }
    public void setDecoder(String value) { decoder = value; }
    public void setJoiner(String value) { joiner = value; }
}
