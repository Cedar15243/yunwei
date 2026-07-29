package com.k2fsa.sherpa.onnx;

public final class HomophoneReplacerConfig {
    private String dictDir;
    private String lexicon;
    private String ruleFsts;
    public HomophoneReplacerConfig() { this("", "", ""); }
    public HomophoneReplacerConfig(String dictDir, String lexicon, String ruleFsts) {
        this.dictDir = dictDir;
        this.lexicon = lexicon;
        this.ruleFsts = ruleFsts;
    }
    public String getDictDir() { return dictDir; }
    public String getLexicon() { return lexicon; }
    public String getRuleFsts() { return ruleFsts; }
    public void setDictDir(String value) { dictDir = value; }
    public void setLexicon(String value) { lexicon = value; }
    public void setRuleFsts(String value) { ruleFsts = value; }
}
