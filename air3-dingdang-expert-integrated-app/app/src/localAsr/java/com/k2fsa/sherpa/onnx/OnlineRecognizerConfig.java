package com.k2fsa.sherpa.onnx;

public final class OnlineRecognizerConfig {
    private FeatureConfig featConfig;
    private OnlineModelConfig modelConfig;
    private OnlineLMConfig lmConfig;
    private OnlineCtcFstDecoderConfig ctcFstDecoderConfig;
    private HomophoneReplacerConfig hr;
    private EndpointConfig endpointConfig;
    private boolean enableEndpoint;
    private String decodingMethod;
    private int maxActivePaths;
    private String hotwordsFile;
    private float hotwordsScore;
    private String ruleFsts;
    private String ruleFars;
    private float blankPenalty;

    public OnlineRecognizerConfig() {
        this(new FeatureConfig(), new OnlineModelConfig(), new OnlineLMConfig(),
                new OnlineCtcFstDecoderConfig(), new HomophoneReplacerConfig(),
                new EndpointConfig(), true, "greedy_search", 4, "", 1.5f, "", "", 0.0f);
    }

    public OnlineRecognizerConfig(FeatureConfig featConfig, OnlineModelConfig modelConfig,
            OnlineLMConfig lmConfig, OnlineCtcFstDecoderConfig ctcFstDecoderConfig,
            HomophoneReplacerConfig hr, EndpointConfig endpointConfig,
            boolean enableEndpoint, String decodingMethod, int maxActivePaths,
            String hotwordsFile, float hotwordsScore, String ruleFsts,
            String ruleFars, float blankPenalty) {
        this.featConfig = featConfig;
        this.modelConfig = modelConfig;
        this.lmConfig = lmConfig;
        this.ctcFstDecoderConfig = ctcFstDecoderConfig;
        this.hr = hr;
        this.endpointConfig = endpointConfig;
        this.enableEndpoint = enableEndpoint;
        this.decodingMethod = decodingMethod;
        this.maxActivePaths = maxActivePaths;
        this.hotwordsFile = hotwordsFile;
        this.hotwordsScore = hotwordsScore;
        this.ruleFsts = ruleFsts;
        this.ruleFars = ruleFars;
        this.blankPenalty = blankPenalty;
    }

    public FeatureConfig getFeatConfig() { return featConfig; }
    public OnlineModelConfig getModelConfig() { return modelConfig; }
    public OnlineLMConfig getLmConfig() { return lmConfig; }
    public OnlineCtcFstDecoderConfig getCtcFstDecoderConfig() { return ctcFstDecoderConfig; }
    public HomophoneReplacerConfig getHr() { return hr; }
    public EndpointConfig getEndpointConfig() { return endpointConfig; }
    public boolean getEnableEndpoint() { return enableEndpoint; }
    public String getDecodingMethod() { return decodingMethod; }
    public int getMaxActivePaths() { return maxActivePaths; }
    public String getHotwordsFile() { return hotwordsFile; }
    public float getHotwordsScore() { return hotwordsScore; }
    public String getRuleFsts() { return ruleFsts; }
    public String getRuleFars() { return ruleFars; }
    public float getBlankPenalty() { return blankPenalty; }
    public void setFeatConfig(FeatureConfig value) { featConfig = value; }
    public void setModelConfig(OnlineModelConfig value) { modelConfig = value; }
    public void setLmConfig(OnlineLMConfig value) { lmConfig = value; }
    public void setCtcFstDecoderConfig(OnlineCtcFstDecoderConfig value) { ctcFstDecoderConfig = value; }
    public void setHr(HomophoneReplacerConfig value) { hr = value; }
    public void setEndpointConfig(EndpointConfig value) { endpointConfig = value; }
    public void setEnableEndpoint(boolean value) { enableEndpoint = value; }
    public void setDecodingMethod(String value) { decodingMethod = value; }
    public void setMaxActivePaths(int value) { maxActivePaths = value; }
    public void setHotwordsFile(String value) { hotwordsFile = value; }
    public void setHotwordsScore(float value) { hotwordsScore = value; }
    public void setRuleFsts(String value) { ruleFsts = value; }
    public void setRuleFars(String value) { ruleFars = value; }
    public void setBlankPenalty(float value) { blankPenalty = value; }
}
