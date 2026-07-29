package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public final class OnlineRecognizer {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private final OnlineRecognizerConfig config;
    private long ptr;

    public OnlineRecognizer(AssetManager assetManager, OnlineRecognizerConfig config) {
        this.config = config;
        ptr = assetManager == null ? newFromFile(config) : newFromAsset(assetManager, config);
    }

    private native long createStream(long ptr, String hotwords);
    private native void decode(long ptr, long streamPtr);
    private native void delete(long ptr);
    private native OnlineRecognizerResult getResult(long ptr, long streamPtr);
    private native boolean isEndpoint(long ptr, long streamPtr);
    private native boolean isReady(long ptr, long streamPtr);
    private native long newFromAsset(AssetManager assetManager, OnlineRecognizerConfig config);
    private native long newFromFile(OnlineRecognizerConfig config);
    private native void reset(long ptr, long streamPtr);

    public OnlineStream createStream(String hotwords) {
        return new OnlineStream(createStream(ptr, hotwords == null ? "" : hotwords));
    }
    public void decode(OnlineStream stream) { decode(ptr, stream.getPtr()); }
    public OnlineRecognizerConfig getConfig() { return config; }
    public OnlineRecognizerResult getResult(OnlineStream stream) {
        return getResult(ptr, stream.getPtr());
    }
    public boolean isEndpoint(OnlineStream stream) { return isEndpoint(ptr, stream.getPtr()); }
    public boolean isReady(OnlineStream stream) { return isReady(ptr, stream.getPtr()); }
    public void reset(OnlineStream stream) { reset(ptr, stream.getPtr()); }
    public void release() {
        if (ptr != 0L) {
            delete(ptr);
            ptr = 0L;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }
}
