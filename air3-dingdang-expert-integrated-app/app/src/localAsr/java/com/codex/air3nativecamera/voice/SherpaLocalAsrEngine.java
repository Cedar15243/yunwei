package com.codex.air3nativecamera.voice;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SherpaLocalAsrEngine implements LocalAsrEngine {
    private static final String TAG = "DingdangLocalAsr";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SherpaOfflineRecognizer recognizer;
    private Callback callback;
    private long generation;
    private String lastPartial = "";

    public SherpaLocalAsrEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized void start(Callback callback) {
        generation++;
        final long session = generation;
        this.callback = callback;
        lastPartial = "";
        executor.execute(() -> {
            try {
                ensureRecognizer();
                if (!isCurrent(session)) {
                    return;
                }
                recognizer.start();
                Log.i(TAG, "local_asr_start session=" + session);
            } catch (Exception error) {
                notifyError(session, error);
            }
        });
    }

    @Override
    public synchronized void acceptPcm(byte[] pcm, int length) {
        if (pcm == null || length <= 0) {
            return;
        }
        final long session = generation;
        final byte[] chunk = Arrays.copyOf(pcm, Math.min(length, pcm.length));
        executor.execute(() -> {
            if (!isCurrent(session)) {
                return;
            }
            try {
                ensureRecognizer();
                String partial = recognizer.acceptPcm16(chunk, chunk.length);
                notifyPartial(session, partial);
            } catch (Exception error) {
                notifyError(session, error);
            }
        });
    }

    @Override
    public synchronized void finish(String stopReason) {
        final long session = generation;
        executor.execute(() -> {
            if (!isCurrent(session)) {
                return;
            }
            try {
                ensureRecognizer();
                String finalText = recognizer.finish();
                if (finalText.length() == 0) {
                    notifyUnclear(session, "local_asr_empty");
                } else {
                    Log.i(TAG, "local_asr_final session=" + session
                            + " textChars=" + finalText.length());
                    notifyFinal(session, finalText);
                }
            } catch (Exception error) {
                notifyError(session, error);
            }
        });
    }

    @Override
    public synchronized void cancel() {
        generation++;
        callback = null;
        lastPartial = "";
        executor.execute(() -> {
            if (recognizer != null) {
                recognizer.releaseSession();
            }
        });
    }

    private void ensureRecognizer() {
        if (recognizer == null) {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            recognizer = new SherpaOfflineRecognizer(context);
            Log.i(TAG, "local_asr_model_ready elapsedMs="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt));
        }
    }

    private synchronized boolean isCurrent(long session) {
        return generation == session && callback != null;
    }

    private synchronized void notifyPartial(long session, String text) {
        String partial = text == null ? "" : text.trim();
        if (!isCurrent(session) || partial.length() == 0 || partial.equals(lastPartial)) {
            return;
        }
        lastPartial = partial;
        callback.onPartial(partial);
    }

    private synchronized void notifyFinal(long session, String text) {
        if (isCurrent(session)) {
            callback.onFinal(text);
        }
    }

    private synchronized void notifyUnclear(long session, String code) {
        if (isCurrent(session)) {
            callback.onUnclear(code);
        }
    }

    private synchronized void notifyError(long session, Exception error) {
        Log.e(TAG, "local_asr_error session=" + session, error);
        if (isCurrent(session)) {
            callback.onError(error);
        }
    }
}
