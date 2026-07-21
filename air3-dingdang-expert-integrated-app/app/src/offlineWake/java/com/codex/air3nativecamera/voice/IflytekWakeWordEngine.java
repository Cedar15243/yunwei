package com.codex.air3nativecamera.voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.iflytek.aikit.core.AiAudio;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** AIKit-backed local wake detector. Standby PCM never leaves this process. */
public final class IflytekWakeWordEngine implements WakeWordEngine {
    private static final String TAG = "DingdangWake";
    private static final String ABILITY_ID = "e867a88f2";
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int FRAME_BYTES = 1280;

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
        return audioLoopRunning.get();
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
                parameters.param("wdec_param_nCmThreshold", "0 0:800");
                parameters.param("gramLoad", true);
                AiHelper.getInst().registerListener(ABILITY_ID, new AiListener() {
                    @Override
                    public void onResult(int handleId, List<AiResponse> responses, Object userContext) {
                        if (responses == null) {
                            return;
                        }
                        for (AiResponse response : responses) {
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
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        recorder.startRecording();
        audioLoopRunning.set(true);
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AiStatus status = AiStatus.BEGIN;
                byte[] pcm = new byte[FRAME_BYTES];
                while (audioLoopRunning.get()) {
                    AudioRecord current = recorder;
                    int read = current == null ? -1 : current.read(pcm, 0, pcm.length);
                    if (read <= 0) {
                        continue;
                    }
                    writeAudio(pcm, read, status);
                    status = AiStatus.CONTINUE;
                }
                endHandle();
            }
        }, "dingdang-aikit-wake");
        audioThread.start();
    }

    private void writeAudio(byte[] pcm, int length, AiStatus status) {
        AiHandle current = handle;
        if (current == null) {
            return;
        }
        byte[] frame = length == pcm.length ? pcm : Arrays.copyOf(pcm, length);
        AiRequest.Builder request = AiRequest.builder();
        request.payload(AiAudio.get("wav").data(frame).status(status).valid());
        int code = AiHelper.getInst().write(request.build(), current);
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
        recorder = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception ignored) {
            }
            current.release();
        }
        if (audioThread == null) {
            endHandle();
        }
    }

    private void endHandle() {
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
            output.write("\u53ee\u5f53;\n".getBytes(StandardCharsets.UTF_8));
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
