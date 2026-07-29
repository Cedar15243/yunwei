package com.k2fsa.sherpa.onnx;

public final class EndpointConfig {
    private EndpointRule rule1;
    private EndpointRule rule2;
    private EndpointRule rule3;
    public EndpointConfig() {
        this(new EndpointRule(false, 2.4f, 0.0f),
                new EndpointRule(true, 1.4f, 0.0f),
                new EndpointRule(false, 0.0f, 20.0f));
    }
    public EndpointConfig(EndpointRule rule1, EndpointRule rule2, EndpointRule rule3) {
        this.rule1 = rule1;
        this.rule2 = rule2;
        this.rule3 = rule3;
    }
    public EndpointRule getRule1() { return rule1; }
    public EndpointRule getRule2() { return rule2; }
    public EndpointRule getRule3() { return rule3; }
    public void setRule1(EndpointRule value) { rule1 = value; }
    public void setRule2(EndpointRule value) { rule2 = value; }
    public void setRule3(EndpointRule value) { rule3 = value; }
}
