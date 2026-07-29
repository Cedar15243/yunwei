package com.k2fsa.sherpa.onnx;

public final class OnlineModelConfig {
    private OnlineTransducerModelConfig transducer;
    private OnlineParaformerModelConfig paraformer;
    private OnlineZipformer2CtcModelConfig zipformer2Ctc;
    private OnlineNeMoCtcModelConfig neMoCtc;
    private OnlineToneCtcModelConfig toneCtc;
    private String tokens;
    private int numThreads;
    private boolean debug;
    private String provider;
    private String modelType;
    private String modelingUnit;
    private String bpeVocab;

    public OnlineModelConfig() {
        this(new OnlineTransducerModelConfig(), new OnlineParaformerModelConfig(),
                new OnlineZipformer2CtcModelConfig(), new OnlineNeMoCtcModelConfig(),
                new OnlineToneCtcModelConfig(), "", 1, false, "cpu", "", "", "");
    }

    public OnlineModelConfig(OnlineTransducerModelConfig transducer,
            OnlineParaformerModelConfig paraformer,
            OnlineZipformer2CtcModelConfig zipformer2Ctc,
            OnlineNeMoCtcModelConfig neMoCtc,
            OnlineToneCtcModelConfig toneCtc,
            String tokens, int numThreads, boolean debug, String provider,
            String modelType, String modelingUnit, String bpeVocab) {
        this.transducer = transducer;
        this.paraformer = paraformer;
        this.zipformer2Ctc = zipformer2Ctc;
        this.neMoCtc = neMoCtc;
        this.toneCtc = toneCtc;
        this.tokens = tokens;
        this.numThreads = numThreads;
        this.debug = debug;
        this.provider = provider;
        this.modelType = modelType;
        this.modelingUnit = modelingUnit;
        this.bpeVocab = bpeVocab;
    }

    public OnlineTransducerModelConfig getTransducer() { return transducer; }
    public OnlineParaformerModelConfig getParaformer() { return paraformer; }
    public OnlineZipformer2CtcModelConfig getZipformer2Ctc() { return zipformer2Ctc; }
    public OnlineNeMoCtcModelConfig getNeMoCtc() { return neMoCtc; }
    public OnlineToneCtcModelConfig getToneCtc() { return toneCtc; }
    public String getTokens() { return tokens; }
    public int getNumThreads() { return numThreads; }
    public boolean getDebug() { return debug; }
    public String getProvider() { return provider; }
    public String getModelType() { return modelType; }
    public String getModelingUnit() { return modelingUnit; }
    public String getBpeVocab() { return bpeVocab; }
    public void setTransducer(OnlineTransducerModelConfig value) { transducer = value; }
    public void setParaformer(OnlineParaformerModelConfig value) { paraformer = value; }
    public void setZipformer2Ctc(OnlineZipformer2CtcModelConfig value) { zipformer2Ctc = value; }
    public void setNeMoCtc(OnlineNeMoCtcModelConfig value) { neMoCtc = value; }
    public void setToneCtc(OnlineToneCtcModelConfig value) { toneCtc = value; }
    public void setTokens(String value) { tokens = value; }
    public void setNumThreads(int value) { numThreads = value; }
    public void setDebug(boolean value) { debug = value; }
    public void setProvider(String value) { provider = value; }
    public void setModelType(String value) { modelType = value; }
    public void setModelingUnit(String value) { modelingUnit = value; }
    public void setBpeVocab(String value) { bpeVocab = value; }
}
