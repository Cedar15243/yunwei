package com.codex.air3nativecamera.features;

public interface FeatureEntry {
    String id();

    String title();

    boolean isAvailable();

    void enter(FeatureHost host);

    void release();

    interface FeatureHost {
        void openFeature(String id, String title, boolean available);
    }
}
