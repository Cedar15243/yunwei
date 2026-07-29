package com.codex.air3nativecamera.voice;

public interface LocalAsrEngine {
    interface Callback {
        void onPartial(String text);
        void onFinal(String text);
        void onUnclear(String diagnosticCode);
        void onError(Exception error);
    }

    void start(Callback callback);
    void acceptPcm(byte[] pcm, int length);
    void finish(String stopReason);
    void cancel();

    final class Unavailable implements LocalAsrEngine {
        @Override
        public void start(Callback callback) {
            if (callback != null) {
                callback.onUnclear("local_asr_unavailable");
            }
        }

        @Override
        public void acceptPcm(byte[] pcm, int length) {
        }

        @Override
        public void finish(String stopReason) {
        }

        @Override
        public void cancel() {
        }
    }
}
