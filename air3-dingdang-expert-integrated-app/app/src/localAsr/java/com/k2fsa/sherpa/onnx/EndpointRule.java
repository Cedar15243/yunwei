package com.k2fsa.sherpa.onnx;

public final class EndpointRule {
    private boolean mustContainNonSilence;
    private float minTrailingSilence;
    private float minUtteranceLength;
    public EndpointRule(boolean mustContainNonSilence, float minTrailingSilence,
            float minUtteranceLength) {
        this.mustContainNonSilence = mustContainNonSilence;
        this.minTrailingSilence = minTrailingSilence;
        this.minUtteranceLength = minUtteranceLength;
    }
    public boolean getMustContainNonSilence() { return mustContainNonSilence; }
    public float getMinTrailingSilence() { return minTrailingSilence; }
    public float getMinUtteranceLength() { return minUtteranceLength; }
    public void setMustContainNonSilence(boolean value) { mustContainNonSilence = value; }
    public void setMinTrailingSilence(float value) { minTrailingSilence = value; }
    public void setMinUtteranceLength(float value) { minUtteranceLength = value; }
}
