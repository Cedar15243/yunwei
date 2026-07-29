package com.k2fsa.sherpa.onnx;

public final class OnlineStream {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    public OnlineStream() { this(0L); }
    public OnlineStream(long ptr) { this.ptr = ptr; }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);
    private native void delete(long ptr);
    private native String getOption(long ptr, String key);
    private native void inputFinished(long ptr);
    private native void setOption(long ptr, String key, String value);

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(ptr, samples, sampleRate);
    }
    public String getOption(String key) { return getOption(ptr, key); }
    public long getPtr() { return ptr; }
    public void inputFinished() { inputFinished(ptr); }
    public void setOption(String key, String value) { setOption(ptr, key, value); }
    public void setPtr(long value) { ptr = value; }
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
