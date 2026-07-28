package com.codex.air3nativecamera.voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.iflytek.aikit.core.AiHandle;
import com.iflytek.aikit.core.AiHelper;
import com.iflytek.aikit.core.AiListener;
import com.iflytek.aikit.core.AiRequest;
import com.iflytek.aikit.core.AiResponse;
import com.iflytek.aikit.core.AiStatus;
import com.iflytek.aikit.core.BaseLibrary;
import com.iflytek.aikit.core.CoreListener;
import com.iflytek.aikit.core.ErrType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** AIKit-backed local wake detector. Standby PCM never leaves this process. */
public final class IflytekWakeWordEngine implements WakeWordEngine {
    private static final String TAG = "DingdangWake";
    private static final String ABILITY_ID = "e867a88f2";
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int FRAME_BYTES = 1280;
    private static final int AUDIO_DIAGNOSTIC_FRAME_INTERVAL = 125;
    private static final String WAKE_THRESHOLD_PARAMETER = "0 0:850";

    private final Context context;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean startRequested = new AtomicBoolean(false);
    private final AtomicBoolean audioLoopRunning = new AtomicBoolean(false);
    private final AtomicBoolean wakeDelivered = new AtomicBoolean(false);
    private final AtomicBoolean unavailableDelivered = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final File workDirectory;
    private final File resourceDirectory;

    private volatile WakeWordEngine.Listener listener;
    private volatile boolean sdkReady;
    private volatile boolean sdkInitializing;
    private volatile AiHandle handle;
    private volatile AudioRecord recorder;
    private volatile Thread audioThread;

    public IflytekWakeWordEngine(Context context, String appId, String apiKey, String apiSecret) {
        this.context = context.getApplicationContext();
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.workDirectory = new File(this.context.getFilesDir(), "iflytek");
        this.resourceDirectory = new File(workDirectory, "ivw");
    }

    @Override
    public void start(WakeWordEngine.Listener listener) {
        this.listener = listener;
        if (!startRequested.getAndSet(true)) {
            unavailableDelivered.set(false);
        }
        if (sdkReady) {
            startSession();
        } else {
            initializeSdk();
        }
    }

    @Override
    public void stop() {
        startRequested.set(false);
        stopSession();
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return audioLoopRunning.get() || audioThread != null || recorder != null || handle != null;
        }
    }

    private void initializeSdk() {
        synchronized (lock) {
            if (sdkReady || sdkInitializing) {
                return;
            }
            sdkInitializing = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureResources();
                    BaseLibrary.Params params = BaseLibrary.Params.builder()
                            .appId(appId)
                            .apiKey(apiKey)
                            .apiSecret(apiSecret)
                            .workDir(workDirectory.getAbsolutePath() + File.separator)
                            .build();
                    AiHelper.getInst().registerListener(new CoreListener() {
                        @Override
                        public void onAuthStateChange(ErrType type, int code) {
                            if (type != ErrType.AUTH) {
                                return;
                            }
                            Log.i(TAG, "AIKit authorization callback code=" + code);
                            sdkInitializing = false;
                            sdkReady = code == 0;
                            if (sdkReady && startRequested.get()) {
                                startSession();
                            } else if (!sdkReady) {
                                Log.e(TAG, "AIKit authorization failed code=" + code);
                                notifyUnavailable("离线唤醒初始化失败，请检查网络后重试");
                            }
                        }
                    });
                    // initEntry may finish cached authorization immediately. Register first so
                    // the initial success callback cannot be missed on a freshly installed app.
                    AiHelper.getInst().initEntry(context, params);
                } catch (Exception error) {
                    sdkInitializing = false;
                    Log.e(TAG, "AIKit initialization failed", error);
                    notifyUnavailable("离线唤醒初始化失败，请稍后重试");
                }
            }
        }, "dingdang-aikit-init").start();
    }

    private void startSession() {
        synchronized (lock) {
            if (!startRequested.get() || audioLoopRunning.get() || audioThread != null) {
                return;
            }
            try {
                writeKeywordFile();
                AiRequest.Builder custom = AiRequest.builder();
                custom.customText("key_word", new File(resourceDirectory, "keyword.txt").getAbsolutePath(), 0);
                int code = AiHelper.getInst().loadData(ABILITY_ID, custom.build());
                if (code != 0) {
                    Log.e(TAG, "AIKit loadData failed code=" + code);
                    notifyUnavailable("离线唤醒资源加载失败，请稍后重试");
                    return;
                }
                code = AiHelper.getInst().specifyDataSet(ABILITY_ID, "key_word", new int[]{0});
                if (code != 0) {
                    Log.e(TAG, "AIKit specifyDataSet failed code=" + code);
                    notifyUnavailable("离线唤醒资源加载失败，请稍后重试");
                    return;
                }
                AiRequest.Builder parameters = AiRequest.builder();
                parameters.param("wdec_param_nCmThreshold", wakeThresholdParameter());
                parameters.param("gramLoad", true);
                AiHelper.getInst().registerListener(ABILITY_ID, new AiListener() {
                    @Override
                    public void onResult(int handleId, List<AiResponse> responses, Object userContext) {
                        if (responses == null) {
                            return;
                        }
                        for (AiResponse response : responses) {
                            byte[] value = response.getValue();
                            String payload = value == null
                                    ? ""
                                    : new String(value, StandardCharsets.UTF_8);
                            if (payload.length() > 512) {
                                payload = payload.substring(0, 512);
                            }
                            Log.i(TAG, "Wake result handle=" + handleId
                                    + " key=" + response.getKey()
                                    + " status=" + response.getStatus()
                                    + " payload=" + payload);
                            if ("func_wake_up".equals(response.getKey())
                                    && wakeDelivered.compareAndSet(false, true)) {
                                final WakeWordEngine.Listener current = listener;
                                if (current != null) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            current.onWakeWordDetected();
                                        }
                                    });
                                }
                                return;
                            }
                        }
                    }

                    @Override
                    public void onEvent(int handleId, int event, List<AiResponse> responses, Object userContext) {
                    }

                    @Override
                    public void onError(int handleId, int code, String message, Object userContext) {
                        Log.e(TAG, "AIKit wake error code=" + code + " message=" + message);
                        notifyUnavailable("离线唤醒异常，请稍后重试");
                    }
                });
                handle = AiHelper.getInst().start(ABILITY_ID, parameters.build(), null);
                if (handle == null || handle.getCode() != 0) {
                    Log.e(TAG, "AIKit start failed code=" + (handle == null ? -1 : handle.getCode()));
                    handle = null;
                    notifyUnavailable("离线唤醒启动失败，请稍后重试");
                    return;
                }
                wakeDelivered.set(false);
                Log.i(TAG, "AIKit wake session started");
                startAudioLoop();
            } catch (Exception error) {
                Log.e(TAG, "AIKit wake session failed", error);
                notifyUnavailable("离线唤醒启动失败，请稍后重试");
                stopSession();
            }
        }
    }

    private void startAudioLoop() {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(FRAME_BYTES, minimum);
        final AudioRecord currentRecorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (currentRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            currentRecorder.release();
            throw new IllegalStateException("Offline wake AudioRecord failed to initialize");
        }
        recorder = currentRecorder;
        currentRecorder.startRecording();
        Log.i(TAG, "Offline wake recorder started source=MIC sampleRate=" + SAMPLE_RATE_HZ
                + " bufferBytes=" + bufferSize
                + " sessionId=" + currentRecorder.getAudioSessionId());
        audioLoopRunning.set(true);
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AiRequest.Builder audioRequestBuilder = AiRequest.builder();
                AiStatus status = AiStatus.BEGIN;
                byte[] pcm = new byte[FRAME_BYTES];
                int diagnosticFrames = 0;
                try {
                    while (audioLoopRunning.get()) {
                        int read = currentRecorder.read(pcm, 0, pcm.length);
                        if (read <= 0) {
                            Log.w(TAG, "Offline wake recorder read code=" + read);
                            continue;
                        }
                        diagnosticFrames++;
                        if (diagnosticFrames == 1
                                || diagnosticFrames % AUDIO_DIAGNOSTIC_FRAME_INTERVAL == 0) {
                            Log.i(TAG, "Offline wake audio frames=" + diagnosticFrames
                                    + " bytes=" + read
                                    + " rmsDbfs=" + pcmRmsDbfs(pcm, read)
                                    + " peak=" + pcmPeak(pcm, read));
                        }
                        writeAudio(audioRequestBuilder, pcm, read, status);
                        status = AiStatus.CONTINUE;
                    }
                } catch (RuntimeException error) {
                    if (audioLoopRunning.get()) {
                        Log.e(TAG, "Offline wake audio loop failed", error);
                        notifyUnavailable("离线唤醒异常，请稍后重试");
                    }
                } finally {
                    releaseRecorderFromAudioThread(currentRecorder);
                    completeAudioSession();
                }
            }
        }, "dingdang-aikit-wake");
        audioThread.start();
    }

    static int pcmRmsDbfs(byte[] pcm, int length) {
        if (pcm == null || length < 2) {
            return -120;
        }
        int sampleBytes = Math.min(length, pcm.length) & ~1;
        double sumSquares = 0.0d;
        int samples = sampleBytes / 2;
        for (int offset = 0; offset < sampleBytes; offset += 2) {
            int sample = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
            sumSquares += (double) sample * sample;
        }
        if (samples == 0 || sumSquares == 0.0d) {
            return -120;
        }
        double rms = Math.sqrt(sumSquares / samples);
        return (int) Math.round(20.0d * Math.log10(rms / 32768.0d));
    }

    static String wakeThresholdParameter() {
        return WAKE_THRESHOLD_PARAMETER;
    }

    static int pcmPeak(byte[] pcm, int length) {
        if (pcm == null || length < 2) {
            return 0;
        }
        int sampleBytes = Math.min(length, pcm.length) & ~1;
        int peak = 0;
        for (int offset = 0; offset < sampleBytes; offset += 2) {
            int sample = Math.abs((short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8)));
            peak = Math.max(peak, sample);
        }
        return peak;
    }

    private void writeAudio(AiRequest.Builder audioRequestBuilder,
                            byte[] pcm, int length, AiStatus status) {
        AiHandle current = handle;
        if (current == null) {
            return;
        }
        byte[] frame = length == pcm.length ? pcm : Arrays.copyOf(pcm, length);
        audioRequestBuilder.clear().status(status).audio("wav", frame);
        int code = AiHelper.getInst().write(audioRequestBuilder.build(), current);
        if (code != 0) {
            Log.e(TAG, "AIKit write failed code=" + code);
            notifyUnavailable("离线唤醒异常，请稍后重试");
        }
    }

    private void notifyUnavailable(final String reason) {
        if (!startRequested.get() || !unavailableDelivered.compareAndSet(false, true)) {
            return;
        }
        final WakeWordEngine.Listener current = listener;
        if (current != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    current.onEngineUnavailable(reason);
                }
            });
        }
    }

    private void stopSession() {
        audioLoopRunning.set(false);
        AudioRecord current = recorder;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception ignored) {
            }
        }
        if (audioThread == null) {
            recorder = null;
            releaseRecorder(current);
            completeAudioSession();
        }
    }

    private void releaseRecorderFromAudioThread(AudioRecord currentRecorder) {
        synchronized (lock) {
            if (recorder == currentRecorder) {
                recorder = null;
            }
        }
        releaseRecorder(currentRecorder);
    }

    private void releaseRecorder(AudioRecord currentRecorder) {
        if (currentRecorder == null) {
            return;
        }
        try {
            currentRecorder.stop();
        } catch (Exception ignored) {
        }
        currentRecorder.release();
    }

    private void completeAudioSession() {
        synchronized (lock) {
            AiHandle current = handle;
            handle = null;
            audioThread = null;
            if (current != null) {
                AiHelper.getInst().end(current);
            }
            if (startRequested.get()) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        startSession();
                    }
                });
            }
        }
    }

    private void ensureResources() throws IOException {
        copyAssetTree(context.getAssets(), "ivw", resourceDirectory);
    }

    private void writeKeywordFile() throws IOException {
        File keyword = new File(resourceDirectory, "keyword.txt");
        FileOutputStream output = new FileOutputStream(keyword, false);
        try {
            output.write("\u5c0f\u53ee\u5f53;\n".getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File target) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }
            InputStream input = assets.open(assetPath);
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } finally {
                input.close();
                output.close();
            }
            return;
        }
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(target, child));
        }
    }
}
