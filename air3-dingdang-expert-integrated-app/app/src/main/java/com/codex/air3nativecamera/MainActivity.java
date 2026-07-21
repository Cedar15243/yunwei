package com.codex.air3nativecamera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.ToneGenerator;
import android.media.AudioRecord;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codex.air3nativecamera.features.FeatureEntry;
import com.codex.air3nativecamera.features.FeatureRegistry;
import com.codex.air3nativecamera.mode.IntegratedModeController;
import com.codex.air3nativecamera.voice.LegacyVoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceAsrSessionGate;
import com.codex.air3nativecamera.voice.VoiceEventStateMachine;
import com.codex.air3nativecamera.voice.WakeWordEngine;
import com.codex.air3nativecamera.voice.WakeWordEngines;
import com.codex.expertcollab.ExpertCollabCoordinator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;

public final class MainActivity extends Activity implements FeatureEntry.FeatureHost, ExpertCollabCoordinator.Host {
    private enum ScreenMode { CHAT, CAMERA, EXPERT }
    private enum VoiceCommand {
        NONE,
        OPEN_EXPERT,
        OPEN_CAMERA,
        TAKE_PHOTO,
        RETAKE_PHOTO,
        SEND,
        BACK_TO_CHAT,
        START_VOICE,
        NEW_PROJECT,
        NEXT_PROJECT,
        PREVIOUS_PROJECT,
        LATEST_PROJECT,
        SHOW_RECORDS
    }
    private enum VoiceStreamState { IDLE, LISTENING, PARTIAL_READY, FINAL_READY, AI_PENDING, AI_DONE, VOICE_UNCLEAR }
    private enum VoiceSessionPurpose { NONE, WAKE, COMMAND }

    private interface ChatAiClient {
        void send(String prompt, String imageId, byte[] jpegBytes, StreamingCallback callback);
    }

    private interface BackendImageUploadCallback {
        void onUploaded(String sessionId, String imageId, int imageBytes);
        void onError(Exception error);
    }

    private interface RealtimeAsrClient {
        void start(RealtimeAsrCallback callback);
        void acceptPcm(byte[] pcm, int length);
        void finish(String stopReason);
        void cancel();
    }

    private interface RealtimeAsrCallback {
        void onPartial(String text);
        void onFinal(String text);
        void onUnclear(String diagnosticCode);
        void onError(Exception error);
    }

    private interface StreamingCallback {
        void onDelta(String text);
        void onComplete();
        void onError(Exception error);
    }

    private static final int REQUEST_CAMERA = 1001;
    private static final int REQUEST_AUDIO = 1002;
    private static final int KEYCODE_DVR = 173;
    private static final String KEY_LOG_TAG = "DingdangKey";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final boolean DIRECT_GPT_ENABLED = GeneratedConfig.DIRECT_GPT_ENABLED;
    private static final String DIRECT_GPT_BASE_URL = GeneratedConfig.DIRECT_GPT_BASE_URL;
    private static final String DIRECT_GPT_MODEL = GeneratedConfig.DIRECT_GPT_MODEL;
    private static final String DIRECT_GPT_REASONING_EFFORT = GeneratedConfig.DIRECT_GPT_REASONING_EFFORT;
    private static final String DIRECT_GPT_API_KEY = GeneratedConfig.DIRECT_GPT_API_KEY;
    private static final String DIRECT_ASR_ENDPOINT = GeneratedConfig.DIRECT_ASR_ENDPOINT;
    private static final String DIRECT_ASR_API_KEY = GeneratedConfig.DIRECT_ASR_API_KEY;
    private static final String DINGDANG_BACKEND_BASE_URL = GeneratedConfig.DINGDANG_BACKEND_BASE_URL;
    private static final String DINGDANG_BACKEND_API_KEY = GeneratedConfig.DINGDANG_BACKEND_API_KEY;
    private static final String APP_ID = GeneratedConfig.APP_ID;
    private static final String APP_LABEL = GeneratedConfig.APP_LABEL;
    private static final boolean VOICE_PREVIEW_ENABLED =
            "com.codex.air3nativecamera.dingdangexpert.follow.preview.voice".equals(APP_ID);
    private static final boolean OFFLINE_WAKE_ENABLED = GeneratedConfig.IFLYTEK_OFFLINE_WAKE_ENABLED;
    private static final boolean VOICE_WORKFLOW_ENABLED = VOICE_PREVIEW_ENABLED || OFFLINE_WAKE_ENABLED;
    private static final String DIRECT_ASR_MODEL = "fun-asr-realtime";
    private static final int JPEG_QUALITY = GeneratedConfig.FAST_UPLOAD ? 82 : 92;
    private static final int UPLOAD_MAX_IMAGE_EDGE = GeneratedConfig.FAST_UPLOAD ? 1280 : 1600;
    private static final int PREVIEW_MAX_IMAGE_EDGE = 480;
    private static final long VOICE_RECORDING_MS = 30000L;
    private static final long VOICE_AUTO_WAKE_RECORDING_MS = 10000L;
    private static final long VOICE_AUTO_STOP_MIN_RECORDING_MS = 1800L;
    private static final long VOICE_AUTO_STOP_SILENCE_MS = 1500L;
    private static final long VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS = 1800L;
    private static final long VOICE_RECORD_THREAD_JOIN_MS = 700L;
    private static final long CHAT_STREAM_RENDER_INTERVAL_MS = 260L;
    private static final long GPT_REQUEST_WATCHDOG_MS = 240000L;
    private static final long WAKE_PREFIX_GRACE_MS = 4000L;
    private static final long FOREGROUND_WAKE_RETRY_DELAY_MS = 900L;
    private static final long VOICE_EVENT_DESCRIPTION_TIMEOUT_MS = 8000L;
    private static final int MAX_VOICE_COMMAND_CHARS = 16;
    private static final int VOICE_SILENCE_RMS_THRESHOLD = 520;
    private static final int VOICE_SAMPLE_RATE_HZ = 16000;
    private static final int VOICE_WAV_CHANNEL_COUNT = 1;
    private static final int VOICE_WAV_BITS_PER_SAMPLE = 16;
    private static final int VOICE_WAV_HEADER_BYTES = 44;
    private static final String CHAT_PROJECT_PREFS = "dingdang_chat_projects";
    private static final String CHAT_PROJECTS_JSON = "projects_json";
    private static final String CURRENT_PROJECT_INDEX = "current_project_index";
    private static final String HOME_WELCOME_MESSAGE = "说“叮当，拍照”记录现场，再直接说明问题。我会结合画面和语音给出排查建议。";
    private static final String AI_IDENTITY_RESPONSE = "我是华方智联研发的" + APP_LABEL
            + "模型，专注现场运维场景。你可以通过眼镜拍摄现场画面，再用语音说明问题，我会结合图片和问题给出简洁、可执行的排查建议。";

    private static final String WEBSITE_RECOVERY_DEMO_URL = "http://bb.chinacedar.top:18081/ai-ops-glasses/hf-ai-ops-glasses.html#specs";
    private static final String WEBSITE_RECOVERY_DEMO_RESPONSE =
            "\u8fd9\u662f\u7f51\u7ad9 502 \u6545\u969c\uff0c\u6309\u64cd\u4f5c\u6d41\u7a0b\u91cd\u542f\u7f51\u7ad9\u670d\u52a1\u5373\u53ef\u3002\n\n"
                    + "1. \u5148\u770b\u4e00\u4e0b\u7f51\u7ad9\u670d\u52a1\u662f\u4e0d\u662f\u5728\u8fd0\u884c\uff1a\n"
                    + "systemctl is-active nginx\n\n"
                    + "2. \u5982\u679c\u9875\u9762\u8fd8\u662f\u6253\u4e0d\u5f00\uff0c\u91cd\u542f\u4e00\u4e0b\u7f51\u7ad9\u670d\u52a1\uff1a\n"
                    + "systemctl restart nginx\n\n"
                    + "3. \u6700\u540e\u5237\u65b0\u8fd9\u4e2a\u5730\u5740\u9a8c\u8bc1\uff1a\n"
                    + WEBSITE_RECOVERY_DEMO_URL;
    private static final String WEBSITE_RECOVERY_DEMO_AI_GUARD =
            "网站恢复演示 Skill：仅当用户明确说“网站恢复演示”，或图片清晰识别到 "
                    + "bb.chinacedar.top 的 HTTP ERROR 502 时，才使用固定演示流程。固定流程是："
                    + "说明这是网站 502 故障，执行 systemctl is-active nginx、systemctl restart nginx，"
                    + "再刷新 " + WEBSITE_RECOVERY_DEMO_URL + " 验证。其他 502 或证据不足的情况，"
                    + "必须说明无法确认根因，并先要求补充告警、日志或现场信息。";
    private static final String[] WEBSITE_RECOVERY_VOICE_KEYWORDS = {
            "\u7f51\u7ad9\u4e0a\u4e0d\u53bb",
            "\u7f51\u7ad9\u6253\u4e0d\u5f00",
            "\u8fd9\u4e2a\u7f51\u7ad9",
            "502",
            "\u9875\u9762\u65e0\u6cd5\u6b63\u5e38\u8fd0\u884c"
    };
    private static final String[] WEBSITE_RECOVERY_OCR_KEYWORDS = {
            "HTTP ERROR 502",
            "bb.chinacedar.top"
    };
    private static final String[] VOICE_COMMAND_OPEN_CAMERA_WORDS = {
            "\u6253\u5f00\u76f8\u673a", "\u51c6\u5907\u62cd\u7167", "\u642d\u914d\u76f8\u673a"
    };
    private static final String[] VOICE_COMMAND_EXPERT_WORDS = {
            "呼叫专家", "打开专家协同"
    };
    private static final String[] VOICE_COMMAND_PHOTO_WORDS = {
            "\u73b0\u573a\u62cd\u7167", "\u62cd\u7167", "\u62cd\u4e00\u5f20", "\u62cd\u5f20\u7167", "\u7167\u4e00\u4e0b", "\u770b\u4e00\u4e0b", "\u626b\u4e00\u4e0b"
    };
    private static final String[] VOICE_COMMAND_RETAKE_WORDS = {
            "\u91cd\u62cd", "\u91cd\u65b0\u62cd", "\u518d\u62cd", "\u91cd\u65b0\u7167", "\u518d\u7167"
    };
    private static final String[] VOICE_COMMAND_PHOTO_EXTENDED_WORDS = {
            "\u62cd\u7167\u7247", "\u62cd\u4e00\u5f20\u7167\u7247", "\u62cd\u4e2a\u7167\u7247"
    };
    private static final String[] VOICE_COMMAND_SEND_WORDS = {
            "\u53d1\u9001", "\u5f00\u59cb\u5206\u6790", "\u5e2e\u6211\u5206\u6790", "\u5c31\u8fd9\u5f20", "\u7528\u8fd9\u5f20"
    };
    private static final String[] VOICE_COMMAND_BACK_WORDS = {
            "\u8fd4\u56de", "\u56de\u5230\u804a\u5929", "\u53d6\u6d88", "\u4e0d\u62cd\u4e86", "\u9000\u51fa\u76f8\u673a"
    };
    private static final String[] VOICE_COMMAND_SPEAK_WORDS = {
            "\u7ee7\u7eed\u8bf4", "\u7ee7\u7eed\u95ee", "\u6211\u518d\u8bf4", "\u8ffd\u95ee", "\u7ee7\u7eed\u63d0\u95ee"
    };
    private static final String[] VOICE_COMMAND_NEW_PROJECT_WORDS = {
            "\u65b0\u5efa\u9879\u76ee", "\u5efa\u7acb\u9879\u76ee", "\u65b0\u7684\u9879\u76ee"
    };
    private static final String[] VOICE_COMMAND_NEXT_PROJECT_WORDS = {
            "\u4e0b\u4e00\u6761\u8bb0\u5f55", "\u4e0b\u4e00\u4e2a\u8bb0\u5f55", "\u4e0b\u4e00\u6761"
    };
    private static final String[] VOICE_COMMAND_PREVIOUS_PROJECT_WORDS = {
            "\u4e0a\u4e00\u6761\u8bb0\u5f55", "\u4e0a\u4e00\u4e2a\u8bb0\u5f55", "\u4e0a\u4e00\u6761"
    };
    private static final String[] VOICE_COMMAND_LATEST_PROJECT_WORDS = {
            "\u6700\u65b0\u8bb0\u5f55", "\u6700\u8fd1\u8bb0\u5f55", "\u56de\u5230\u6700\u65b0"
    };
    private static final String[] VOICE_COMMAND_RECORDS_WORDS = {
            "\u67e5\u770b\u8bb0\u5f55", "\u6253\u5f00\u8bb0\u5f55", "\u5386\u53f2\u8bb0\u5f55"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<ChatMessage> chatMessages = new ArrayList<>();
    private final ArrayList<ChatProject> chatProjects = new ArrayList<>();
    private final FeatureRegistry featureRegistry = FeatureRegistry.createDefault();
    private final LegacyVoiceCommandRouter legacyVoiceCommandRouter = new LegacyVoiceCommandRouter();
    private final VoiceCommandRouter voiceCommandRouter = new VoiceCommandRouter();
    private final VoiceEventStateMachine voiceEventStateMachine = new VoiceEventStateMachine();
    private final VoiceAsrSessionGate voiceAsrSessionGate = new VoiceAsrSessionGate();

    private ScreenMode screenMode = ScreenMode.CHAT;
    private FrameLayout root;
    private FrameLayout expertLayer;
    private FrameLayout commandOverlay;
    private TextureView previewView;
    private LinearLayout chatLayer;
    private LinearLayout cameraOverlay;
    private LinearLayout projectRail;
    private LinearLayout.LayoutParams projectRailParams;
    private LinearLayout projectListColumn;
    private LinearLayout chatMessagesColumn;
    private LinearLayout composerPanel;
    private TextView titleText;
    private TextView stateText;
    private TextView attachmentPreviewText;
    private ImageView attachmentPreviewImage;
    private TextView transcriptDraftText;
    private TextView cameraButton;
    private TextView voiceButton;
    private AudioWaveView voiceWaveView;
    private TextView menuButton;
    private TextView cameraStatusText;
    private TextView cameraCaptureButton;
    private TextView cameraBackButton;
    private TextView commandTitleText;
    private TextView commandContextText;
    private TextView commandListText;
    private ScrollView chatScrollView;
    private String chatStatus = "在线";

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size previewSize;
    private Size captureSize;
    private String cameraId;
    private int sensorOrientation;
    private boolean captureInFlight;

    private AudioRecord voiceRecorder;
    private Thread voiceRecordThread;
    private Thread voiceRecordCleanupThread;
    private File voiceFile;
    private boolean recordingVoice;
    private Runnable voiceStopRunnable;
    private VoiceStreamState voiceStreamState = VoiceStreamState.IDLE;
    private RealtimeAsrClient realtimeAsrClient;
    private int realtimeAsrPartialCount;
    private boolean realtimeAsrFinished;
    private long voiceRecordingStartedAtMs;
    private long voiceLastSpeechAtMs;
    private long voiceLastTranscriptAtMs;
    private boolean voiceSpeechStarted;
    private boolean voiceAutoStopRequested;
    private Runnable voiceTranscriptStableStopRunnable;
    private boolean voiceAutoListenArmed;
    private boolean voiceStartedFromAutoWindow;
    private VoiceSessionPurpose voiceSessionPurpose = VoiceSessionPurpose.NONE;
    private boolean wakeFeedbackDelivered;
    private long wakePrefixGraceUntilMs;
    private WakeWordEngine wakeWordEngine;

    private byte[] composerImageBytes;
    private String composerImageId = "";
    private String composerImagePreviewBase64 = "";
    private Bitmap composerImagePreviewBitmap;
    private boolean composerImageUploadFailed;
    private boolean sendAfterImageUpload;
    private long composerImageGeneration;
    private byte[] lastDirectAiContextImage;
    private String composerTranscript = "";
    private int streamingAssistantIndex = -1;
    private int liveTranscriptMessageIndex = -1;
    private Runnable chatStreamRenderRunnable;
    private Runnable foregroundAutoVoiceStartRunnable;
    private Runnable voiceEventDescriptionTimeoutRunnable;
    private long lastChatStreamRenderAtMs;
    private boolean scrollChatToBottom;
    private int chatScrollRequestId;
    private long gptStreamStartedAtMs = 0L;
    private boolean gptFirstDeltaLogged = false;
    private int gptRequestGeneration = 0;
    private boolean pendingVoicePhotoCapture;
    private int currentProjectIndex = 0;
    private ChatAiClient chatAiClient;
    private BackendChatClient backendChatClient;
    private DirectAsrClient directAsrClient;
    private IntegratedModeController modeController;
    private ExpertCollabCoordinator expertCoordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        chatAiClient = createChatAiClient();
        directAsrClient = new DirectAsrClient(DIRECT_ASR_ENDPOINT, DIRECT_ASR_API_KEY);
        realtimeAsrClient = createRealtimeAsrClient();
        wakeWordEngine = WakeWordEngines.create(
                getApplicationContext(),
                OFFLINE_WAKE_ENABLED,
                GeneratedConfig.IFLYTEK_APP_ID,
                GeneratedConfig.IFLYTEK_API_KEY,
                GeneratedConfig.IFLYTEK_API_SECRET);
        restoreChatProjects();
        buildUi();
        modeController = new IntegratedModeController(new IntegratedModeController.Hooks() {
            @Override
            public void persistLegacyState() {
                persistChatProjects();
            }

            @Override
            public void stopLegacyVoice() {
                cancelForegroundVoiceListening();
                cancelVoiceEventDescriptionTimeout();
                stopVoiceRecording(false, "expert_enter");
                resetVoiceSessionForForegroundWake();
            }

            @Override
            public void closeLegacyCamera() {
                closeCamera();
                stopCameraThread();
            }

            @Override
            public void showExpert() {
                showExpertLayer();
            }

            @Override
            public void releaseExpert() {
                releaseExpertCoordinator();
            }

            @Override
            public void showChat() {
                renderChatScreen();
            }

            @Override
            public void startLegacyCamera() {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCameraFlow();
                }
            }

            @Override
            public void resumeLegacyVoice() {
                resetVoiceSessionForForegroundWake();
                scheduleForegroundVoiceListening("expert_exit");
            }
        });
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            startCameraFlow();
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
        renderChatScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenMode != ScreenMode.EXPERT
                && previewView != null && previewView.isAvailable() && cameraDevice == null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
        }
        if (screenMode != ScreenMode.EXPERT && isForegroundWakeListeningEnabled()) {
            scheduleForegroundVoiceListening("resume");
        } else {
            cancelForegroundVoiceListening();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (screenMode == ScreenMode.EXPERT) {
            expertLayer.setVisibility(View.VISIBLE);
        } else if (screenMode == ScreenMode.CAMERA) {
            renderCameraScreen();
        } else {
            renderChatScreen();
        }
    }

    @Override
    protected void onPause() {
        if (screenMode == ScreenMode.EXPERT && modeController != null) {
            exitExpertMode();
        }
        persistChatProjects();
        cancelPendingChatStreamRender();
        cancelForegroundVoiceListening();
        stopVoiceRecording(false, "pause");
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseExpertCoordinator();
        persistChatProjects();
        cancelPendingChatStreamRender();
        cancelForegroundVoiceListening();
        stopVoiceRecording(false, "destroy");
        closeCamera();
        stopCameraThread();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(KEY_LOG_TAG,
                    "dispatch action=" + event.getAction()
                            + " keyCode=" + event.getKeyCode()
                            + " keyName=" + KeyEvent.keyCodeToString(event.getKeyCode())
                            + " scanCode=" + event.getScanCode()
                            + " deviceId=" + event.getDeviceId()
                            + " source=" + event.getSource()
                            + " repeat=" + event.getRepeatCount()
                            + " screen=" + screenMode);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && isHandledHardwareKey(event.getKeyCode())) {
            if (event.getRepeatCount() == 0) {
                handleHardwareShortcut(event.getKeyCode());
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && isHandledHardwareKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.i(KEY_LOG_TAG,
                "onKeyDown keyCode=" + keyCode
                        + " keyName=" + KeyEvent.keyCodeToString(keyCode)
                        + " scanCode=" + event.getScanCode()
                        + " deviceId=" + event.getDeviceId()
                        + " source=" + event.getSource()
                        + " screen=" + screenMode);
        if (isHandledHardwareKey(keyCode)) {
            return handleHardwareShortcut(keyCode);
        }
        if (isSystemReservedCameraKey(keyCode)) {
            Log.i(KEY_LOG_TAG, "system-reserved camera key observed; not used as an app shortcut");
            return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleHardwareShortcut(int keyCode) {
        Log.i(KEY_LOG_TAG, "handleHardwareShortcut keyCode=" + keyCode + " screen=" + screenMode);
        if (isCommandOverlayVisible()) {
            if (isBackShortcutKey(keyCode) || isConfirmKey(keyCode)) {
                hideCommandOverlay();
            }
            return true;
        }
        if (screenMode == ScreenMode.EXPERT) {
            if (isBackShortcutKey(keyCode)) {
                exitExpertMode();
                return true;
            }
            if (isConfirmKey(keyCode) && expertCoordinator != null) {
                return expertCoordinator.handleConfirmKey();
            }
            return true;
        }
        if (isChatScrollKey(keyCode)) {
            if (screenMode == ScreenMode.CHAT) {
                scrollChatByKey(keyCode);
                return true;
            }
        }
        if (isConfirmKey(keyCode)) {
            if (screenMode == ScreenMode.CAMERA) {
                captureStillImage();
            } else {
                startToggleVoiceRecording();
            }
            return true;
        }
        if (isCameraShortcutKey(keyCode)) {
            if (screenMode == ScreenMode.CAMERA) {
                captureStillImage();
            } else {
                enterCameraScreen("hardware-key");
            }
            return true;
        }
        if (isBackShortcutKey(keyCode)) {
            if (screenMode == ScreenMode.CAMERA) {
                returnToChatFromCameraFlow();
                return true;
            }
            if (screenMode == ScreenMode.CHAT) {
                if (isProjectRailVisible()) {
                    setProjectRailVisible(false);
                } else {
                    renderChatScreen();
                }
                return true;
            }
        }
        if (isSendShortcutKey(keyCode)) {
            if (screenMode == ScreenMode.CHAT) {
                if (recordingVoice) {
                    finishToggleVoiceRecording("hardware_finish");
                } else if (composerTranscript.trim().length() > 0) {
                    sendComposerToAi();
                } else {
                    setProjectRailVisible(!isProjectRailVisible());
                }
                return true;
            }
        }
        if (isVolumeKey(keyCode)) {
            return true;
        }
        return false;
    }

    private boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private boolean isCameraShortcutKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_FOCUS
                || keyCode == KeyEvent.KEYCODE_F9
                || isSystemReservedCameraKey(keyCode);
    }

    private boolean isSystemReservedCameraKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CAMERA
                || keyCode == KEYCODE_DVR;
    }

    private boolean isBackShortcutKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_F10;
    }

    private boolean isSendShortcutKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_F12;
    }

    private boolean isChatScrollKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private boolean isHandledHardwareKey(int keyCode) {
        return isConfirmKey(keyCode)
                || isCameraShortcutKey(keyCode)
                || isChatScrollKey(keyCode)
                || isBackShortcutKey(keyCode)
                || isSendShortcutKey(keyCode)
                || isSystemReservedCameraKey(keyCode)
                || isVolumeKey(keyCode);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        previewView = new TextureView(this);
        previewView.setVisibility(View.GONE);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        chatLayer = new LinearLayout(this);
        chatLayer.setOrientation(LinearLayout.HORIZONTAL);
        chatLayer.setBackgroundColor(Color.rgb(247, 250, 248));
        chatLayer.setPadding(60, 60, 60, 60);
        root.addView(chatLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout conversationColumn = new LinearLayout(this);
        conversationColumn.setOrientation(LinearLayout.VERTICAL);
        chatLayer.addView(conversationColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f));

        projectRail = new LinearLayout(this);
        projectRail.setOrientation(LinearLayout.VERTICAL);
        projectRail.setPadding(dp(14), dp(12), dp(14), dp(12));
        projectRail.setBackground(roundRect(Color.WHITE, Color.rgb(230, 230, 230), 18));
        projectRailParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT);
        projectRailParams.leftMargin = 0;
        chatLayer.addView(projectRail, projectRailParams);
        projectRail.setVisibility(View.GONE);

        TextView projectHeader = new TextView(this);
        projectHeader.setText("会话记录");
        projectHeader.setTextColor(Color.rgb(18, 18, 18));
        projectHeader.setTextSize(18);
        projectHeader.setTypeface(Typeface.DEFAULT_BOLD);
        projectHeader.setVisibility(View.GONE);
        projectRail.addView(projectHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView newProjectButton = menuItem("新对话");
        newProjectButton.setContentDescription("新建项目");
        newProjectButton.setTextColor(Color.rgb(20, 20, 20));
        LinearLayout.LayoutParams newProjectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        newProjectParams.topMargin = dp(2);
        projectRail.addView(newProjectButton, newProjectParams);

        TextView aiGuidanceButton = menuItem("AI智能运维指导");
        projectRail.addView(aiGuidanceButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView expertCollabButton = menuItem("专家协同");
        projectRail.addView(expertCollabButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView equipmentInspectionButton = menuItem("设备巡检");
        projectRail.addView(equipmentInspectionButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView fieldRecordsButton = menuItem("现场记录");
        projectRail.addView(fieldRecordsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView moreOperationsButton = menuItem("更多运维");
        projectRail.addView(moreOperationsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView memoryButton = menuItem("记忆");
        projectRail.addView(memoryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView historyButton = menuItem("历史对话");
        projectRail.addView(historyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView settingsButton = menuItem("设置");
        projectRail.addView(settingsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        projectListColumn = new LinearLayout(this);
        projectListColumn.setOrientation(LinearLayout.VERTICAL);
        projectRail.addView(projectListColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        conversationColumn.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView leftIcon = iconButton("‹");
        leftIcon.setTextSize(42);
        leftIcon.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 29));
        topBar.addView(leftIcon, squareParams(58));
        leftIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isProjectRailVisible()) {
                    setProjectRailVisible(false);
                } else if (screenMode == ScreenMode.CHAT) {
                    scheduleChatScrollToTop();
                }
            }
        });

        titleText = new TextView(this);
        titleText.setText("新对话");
        titleText.setTextColor(Color.rgb(18, 18, 18));
        titleText.setTextSize(22);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.CENTER);
        titleText.setSingleLine(true);
        topBar.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stateText = new TextView(this);
        stateText.setText("在线");
        stateText.setTextColor(Color.rgb(78, 85, 94));
        stateText.setTextSize(15);
        stateText.setGravity(Gravity.RIGHT);
        stateText.setMaxLines(2);
        stateText.setVisibility(View.VISIBLE);
        topBar.addView(stateText, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT));

        menuButton = iconButton("☰");
        menuButton.setTextSize(28);
        menuButton.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 29));
        topBar.addView(menuButton, squareParams(58));

        chatScrollView = new ScrollView(this);
        chatScrollView.setFillViewport(false);
        chatScrollView.setFocusable(false);
        chatScrollView.setFocusableInTouchMode(false);
        chatScrollView.setDefaultFocusHighlightEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        scrollParams.topMargin = 28;
        scrollParams.bottomMargin = 10;
        conversationColumn.addView(chatScrollView, scrollParams);

        chatMessagesColumn = new LinearLayout(this);
        chatMessagesColumn.setOrientation(LinearLayout.VERTICAL);
        chatMessagesColumn.setPadding(0, 0, 0, 6);
        chatScrollView.addView(chatMessagesColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        composerPanel = new LinearLayout(this);
        composerPanel.setOrientation(LinearLayout.VERTICAL);
        composerPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        composerPanel.setPadding(0, 8, 0, 10);
        composerPanel.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 0));
        conversationColumn.addView(composerPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout draftBox = new LinearLayout(this);
        draftBox.setOrientation(LinearLayout.VERTICAL);
        draftBox.setGravity(Gravity.CENTER_HORIZONTAL);
        composerPanel.addView(draftBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        attachmentPreviewImage = new ImageView(this);
        attachmentPreviewImage.setVisibility(View.GONE);
        attachmentPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        attachmentPreviewImage.setAdjustViewBounds(false);
        attachmentPreviewImage.setContentDescription("现场照片预览");
        attachmentPreviewImage.setBackground(roundRect(Color.WHITE, Color.rgb(224, 224, 224), 12));
        LinearLayout.LayoutParams attachmentImageParams = new LinearLayout.LayoutParams(dp(148), dp(92));
        attachmentImageParams.bottomMargin = dp(6);
        draftBox.addView(attachmentPreviewImage, attachmentImageParams);

        attachmentPreviewText = new TextView(this);
        attachmentPreviewText.setText("");
        attachmentPreviewText.setTextColor(Color.rgb(68, 76, 84));
        attachmentPreviewText.setTextSize(15);
        attachmentPreviewText.setGravity(Gravity.CENTER);
        attachmentPreviewText.setVisibility(View.GONE);
        attachmentPreviewText.setPadding(10, 8, 10, 8);
        attachmentPreviewText.setBackground(roundRect(Color.WHITE, Color.rgb(224, 224, 224), 12));
        draftBox.addView(attachmentPreviewText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        transcriptDraftText = new TextView(this);
        transcriptDraftText.setText("");
        transcriptDraftText.setTextColor(Color.rgb(26, 26, 26));
        transcriptDraftText.setTextSize(18);
        transcriptDraftText.setGravity(Gravity.CENTER);
        transcriptDraftText.setMinLines(1);
        transcriptDraftText.setPadding(18, 10, 18, 10);
        transcriptDraftText.setBackground(roundRect(Color.WHITE, Color.TRANSPARENT, 14));
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        transcriptParams.bottomMargin = dp(8);
        draftBox.addView(transcriptDraftText, transcriptParams);

        voiceWaveView = new AudioWaveView(this);
        voiceWaveView.setVisibility(View.GONE);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(dp(540), dp(90));
        waveParams.bottomMargin = dp(6);
        draftBox.addView(voiceWaveView, waveParams);

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        composerPanel.addView(controlsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        cameraButton = assistantHomeAction("现场拍摄", false);
        cameraButton.setContentDescription("现场拍摄");
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(dp(202), dp(64));
        cameraParams.rightMargin = dp(24);
        controlsRow.addView(cameraButton, cameraParams);

        voiceButton = assistantHomeAction("语音提问", true);
        voiceButton.setContentDescription("语音提问");
        controlsRow.addView(voiceButton, new LinearLayout.LayoutParams(dp(202), dp(64)));

        cameraOverlay = new LinearLayout(this);
        cameraOverlay.setOrientation(LinearLayout.VERTICAL);
        cameraOverlay.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cameraOverlay.setPadding(dp(28), dp(24), dp(28), dp(28));
        cameraOverlay.setVisibility(View.GONE);
        root.addView(cameraOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        expertLayer = new FrameLayout(this);
        expertLayer.setBackgroundColor(Color.rgb(9, 13, 17));
        expertLayer.setVisibility(View.GONE);
        root.addView(expertLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        cameraStatusText = new TextView(this);
        cameraStatusText.setText("取景中 · 说“拍照”");
        cameraStatusText.setTextColor(Color.WHITE);
        cameraStatusText.setTextSize(18);
        cameraStatusText.setGravity(Gravity.CENTER);
        cameraStatusText.setPadding(dp(20), dp(10), dp(20), dp(10));
        cameraStatusText.setBackground(roundRect(Color.argb(190, 0, 0, 0), Color.TRANSPARENT, 16));
        cameraOverlay.addView(cameraStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        cameraCaptureButton = iconButton("●");
        cameraCaptureButton.setContentDescription("拍摄现场照片");
        cameraCaptureButton.setTextColor(Color.WHITE);
        cameraCaptureButton.setTextSize(34);
        cameraCaptureButton.setBackground(roundRect(Color.argb(218, 21, 125, 94), Color.WHITE, 30));
        LinearLayout.LayoutParams cameraCaptureParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        cameraCaptureParams.topMargin = dp(14);
        cameraOverlay.addView(cameraCaptureButton, cameraCaptureParams);

        cameraBackButton = iconButton("‹");
        cameraBackButton.setContentDescription("返回 AI 对话");
        cameraBackButton.setTextColor(Color.WHITE);
        cameraBackButton.setTextSize(42);
        cameraBackButton.setBackground(roundRect(Color.argb(184, 0, 0, 0), Color.TRANSPARENT, 28));
        cameraBackButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams cameraBackParams = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP | Gravity.LEFT);
        cameraBackParams.leftMargin = dp(24);
        cameraBackParams.topMargin = dp(24);
        root.addView(cameraBackButton, cameraBackParams);

        newProjectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                createNewProjectChat();
                setProjectRailVisible(false);
            }
        });
        aiGuidanceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setProjectRailVisible(false);
                renderChatScreen();
            }
        });
        expertCollabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                featureRegistry.require("expert_collab").enter(MainActivity.this);
            }
        });
        equipmentInspectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                featureRegistry.require("equipment_inspection").enter(MainActivity.this);
            }
        });
        fieldRecordsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                featureRegistry.require("field_records").enter(MainActivity.this);
            }
        });
        moreOperationsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setProjectRailVisible(false);
                setChatStatus("更多运维能力筹备中");
            }
        });
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setProjectRailVisible(!isProjectRailVisible());
            }
        });
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterCameraScreen("composer-camera");
            }
        });
        voiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (recordingVoice) {
                    finishToggleVoiceRecording("manual_finish");
                } else {
                    startToggleVoiceRecording();
                }
            }
        });
        cameraCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (composerImageBytes == null) {
                    captureStillImage();
                } else {
                    renderChatScreen();
                }
            }
        });
        cameraBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                returnToChatFromCameraFlow();
            }
        });

        buildCommandOverlay();

        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private TextView iconButton(String value) {
        TextView button = new TextView(this);
        button.setText(value);
        button.setTextSize(27);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(34, 34, 34));
        button.setBackground(roundRect(Color.rgb(235, 235, 235), Color.TRANSPARENT, 27));
        button.setClickable(true);
        button.setDefaultFocusHighlightEnabled(false);
        return button;
    }

    /** A non-navigating overlay keeps voice help available without losing the active task. */
    private void buildCommandOverlay() {
        commandOverlay = new FrameLayout(this);
        commandOverlay.setBackgroundColor(Color.argb(222, 8, 16, 20));
        commandOverlay.setVisibility(View.GONE);
        commandOverlay.setClickable(true);
        root.addView(commandOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(34), dp(28), dp(34), dp(26));
        card.setBackground(roundRect(Color.rgb(250, 253, 251), Color.rgb(210, 224, 216), 16));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        commandOverlay.addView(card, cardParams);

        commandTitleText = new TextView(this);
        commandTitleText.setText("语音命令");
        commandTitleText.setTextColor(Color.rgb(18, 58, 45));
        commandTitleText.setTextSize(28);
        commandTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(commandTitleText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        commandContextText = new TextView(this);
        commandContextText.setTextColor(Color.rgb(47, 95, 76));
        commandContextText.setTextSize(17);
        LinearLayout.LayoutParams contextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contextParams.topMargin = dp(8);
        card.addView(commandContextText, contextParams);

        commandListText = new TextView(this);
        commandListText.setTextColor(Color.rgb(32, 40, 37));
        commandListText.setTextSize(19);
        commandListText.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(18);
        card.addView(commandListText, listParams);

        TextView closeHint = new TextView(this);
        closeHint.setText("说“返回”继续当前任务");
        closeHint.setTextColor(Color.rgb(47, 95, 76));
        closeHint.setTextSize(17);
        closeHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = dp(22);
        card.addView(closeHint, closeParams);

        commandOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideCommandOverlay();
            }
        });
    }

    private boolean isCommandOverlayVisible() {
        return commandOverlay != null && commandOverlay.getVisibility() == View.VISIBLE;
    }

    private void showCommandOverlay() {
        if (commandOverlay == null) {
            return;
        }
        String context;
        String commands;
        if (screenMode == ScreenMode.CAMERA) {
            context = "当前：现场拍摄";
            commands = "拍照  立即拍摄现场\n重拍  放弃当前照片后重拍\n使用照片 / 确认  带回 AI 对话\n返回 / 不拍了  退出相机\n\n通用\n专家  呼叫在线专家\n语音命令  再次查看本页";
        } else if (screenMode == ScreenMode.EXPERT) {
            context = "当前：专家协同";
            commands = "返回  仅关闭本页并继续通话\n挂断  结束本次专家协同\n\n通用\n拍照  记录现场并发起 AI 分析\n语音命令  再次查看本页";
        } else {
            context = "当前：AI 智能运维指导";
            commands = "拍照  拍摄现场，随后直接说问题\n专家  呼叫在线专家协同\n巡检 / 记录 / 设备 / 工单 / 知识 / 报告\n返回  收起当前辅助页面\n\n拍摄后\n重拍  重新取景\n使用照片 / 确认  带图提问\n补充 / 重说  继续输入问题";
        }
        commandContextText.setText(context);
        commandListText.setText(commands);
        commandOverlay.setVisibility(View.VISIBLE);
    }

    private void hideCommandOverlay() {
        if (commandOverlay != null) {
            commandOverlay.setVisibility(View.GONE);
        }
    }

    private TextView actionPill(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(18, 13, 18, 13);
        view.setBackground(roundRect(Color.argb(210, 0, 0, 0), Color.WHITE, 20));
        view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        return view;
    }

    private TextView menuItem(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTextColor(Color.rgb(18, 18, 18));
        view.setGravity(Gravity.CENTER);
        view.setPadding(14, 16, 14, 16);
        view.setBackground(roundRect(Color.WHITE, Color.TRANSPARENT, 0));
        view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        return view;
    }

    private boolean isProjectRailVisible() {
        return projectRail != null && projectRail.getVisibility() == View.VISIBLE;
    }

    private void setChatStatus(String status) {
        chatStatus = status == null || status.trim().length() == 0 ? "在线" : status.trim();
        if (stateText != null) {
            stateText.setText(chatStatus);
            stateText.setVisibility(View.VISIBLE);
        }
    }

    private void setProjectRailVisible(boolean visible) {
        if (projectRail == null || projectRailParams == null) {
            return;
        }
        projectRailParams.width = visible ? dp(288) : 0;
        projectRailParams.leftMargin = visible ? dp(16) : 0;
        projectRail.setVisibility(visible ? View.VISIBLE : View.GONE);
        projectRail.setLayoutParams(projectRailParams);
        if (visible) {
            renderProjectList();
        }
    }

    private void scrollChatByKey(int keyCode) {
        if (chatScrollView == null) {
            return;
        }
        final int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
        chatScrollRequestId++;
        chatScrollView.post(new Runnable() {
            @Override
            public void run() {
                int step = Math.max(dp(96), chatScrollView.getHeight() / 3);
                int target = Math.max(0, chatScrollView.getScrollY() + direction * step);
                chatScrollView.smoothScrollTo(0, target);
            }
        });
    }

    private LinearLayout.LayoutParams squareParams(int sizeDp) {
        int px = dp(sizeDp);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(px, px);
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        return params;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restoreChatProjects() {
        chatProjects.clear();
        SharedPreferences prefs = getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE);
        String saved = prefs.getString(CHAT_PROJECTS_JSON, "");
        currentProjectIndex = prefs.getInt(CURRENT_PROJECT_INDEX, 0);
        if (saved != null && saved.trim().length() > 0) {
            try {
                JSONArray projects = new JSONArray(saved);
                for (int i = 0; i < projects.length(); i++) {
                    ChatProject project = ChatProject.fromJson(projects.getJSONObject(i));
                    chatProjects.add(project);
                }
            } catch (Exception ignored) {
                chatProjects.clear();
                currentProjectIndex = 0;
            }
        }
        boolean migratedWelcomeMessage = migrateHomeWelcomeMessages();
        boolean migratedProtocolAsrMessages = migrateProtocolAsrMessages();
        if (chatProjects.isEmpty()) {
            ChatProject project = new ChatProject(
                    "project-" + System.currentTimeMillis(),
                    "现场诊断",
                    System.currentTimeMillis());
            project.messages.add(new ChatMessage(
                    "assistant",
                    "text",
                    HOME_WELCOME_MESSAGE,
                    "",
                    false));
            chatProjects.add(project);
            currentProjectIndex = 0;
            migratedWelcomeMessage = true;
        }
        if (currentProjectIndex < 0 || currentProjectIndex >= chatProjects.size()) {
            currentProjectIndex = 0;
        }
        loadCurrentProjectMessages();
        if (migratedWelcomeMessage || migratedProtocolAsrMessages) {
            persistChatProjects();
        }
    }

    private boolean migrateHomeWelcomeMessages() {
        boolean changed = false;
        for (int i = 0; i < chatProjects.size(); i++) {
            ChatProject project = chatProjects.get(i);
            for (int j = 0; j < project.messages.size(); j++) {
                ChatMessage message = project.messages.get(j);
                if ("assistant".equals(message.role)
                        && "text".equals(message.kind)
                        && isLegacyHomeWelcomeMessage(message.text)) {
                    message.text = HOME_WELCOME_MESSAGE;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean migrateProtocolAsrMessages() {
        boolean changed = false;
        for (int i = 0; i < chatProjects.size(); i++) {
            ChatProject project = chatProjects.get(i);
            for (int j = project.messages.size() - 1; j >= 0; j--) {
                ChatMessage message = project.messages.get(j);
                if (!"text".equals(message.kind)) {
                    continue;
                }
                String cleaned = sanitizeTranscriptForDisplay(message.text);
                if (cleaned.length() == 0 && looksLikeAsrProtocolJson(message.text)) {
                    project.messages.remove(j);
                    changed = true;
                } else if (!cleaned.equals(message.text)) {
                    message.text = cleaned;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean isLegacyHomeWelcomeMessage(String text) {
        if (text == null) {
            return false;
        }
        return (text.contains("先点我拍照记录现场") && text.contains("再点我说话描述问题"))
                || (text.contains("叮当，拍照") && text.contains("直接说明问题"))
                && text.contains("语音转成文字")
                && text.contains("GPT");
    }

    private void persistChatProjects() {
        saveCurrentProjectFromMessages();
        JSONArray projects = new JSONArray();
        for (int i = 0; i < chatProjects.size(); i++) {
            projects.put(chatProjects.get(i).toJson());
        }
        getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE)
                .edit()
                .putString(CHAT_PROJECTS_JSON, projects.toString())
                .putInt(CURRENT_PROJECT_INDEX, currentProjectIndex)
                .apply();
    }

    private void createNewProjectChat() {
        saveCurrentProjectFromMessages();
        ChatProject project = new ChatProject(
                "project-" + System.currentTimeMillis(),
                "现场诊断 " + (chatProjects.size() + 1),
                System.currentTimeMillis());
        project.messages.add(new ChatMessage(
                "assistant",
                "text",
                "这是新的运维项目记录。先拍照，再语音描述问题，我会结合现场继续分析。",
                "",
                false));
        chatProjects.add(0, project);
        currentProjectIndex = 0;
        clearComposerImage();
        lastDirectAiContextImage = null;
        composerTranscript = "";
        streamingAssistantIndex = -1;
        gptRequestGeneration++;
        gptStreamStartedAtMs = 0L;
        liveTranscriptMessageIndex = -1;
        loadCurrentProjectMessages();
        persistChatProjects();
        scrollChatToBottom = true;
        renderChatScreen();
    }

    private void switchProjectChat(int index) {
        if (index < 0 || index >= chatProjects.size() || index == currentProjectIndex) {
            return;
        }
        saveCurrentProjectFromMessages();
        currentProjectIndex = index;
        clearComposerImage();
        lastDirectAiContextImage = null;
        composerTranscript = "";
        streamingAssistantIndex = -1;
        liveTranscriptMessageIndex = -1;
        loadCurrentProjectMessages();
        persistChatProjects();
        scrollChatToBottom = true;
        renderChatScreen();
    }

    private void renderProjectList() {
        if (projectListColumn == null) {
            return;
        }
        projectListColumn.removeAllViews();
        for (int i = 0; i < chatProjects.size(); i++) {
            final int projectIndex = i;
            ChatProject project = chatProjects.get(i);
            TextView item = new TextView(this);
            item.setText(projectListLabel(project));
            item.setTextColor(Color.rgb(24, 24, 24));
            item.setTextSize(14);
            item.setMinLines(2);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(10, 8, 10, 8);
            item.setBackground(roundRect(
                    i == currentProjectIndex ? Color.WHITE : Color.TRANSPARENT,
                    i == currentProjectIndex ? Color.rgb(218, 218, 218) : Color.TRANSPARENT,
                    12));
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    switchProjectChat(projectIndex);
                    setProjectRailVisible(false);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            projectListColumn.addView(item, params);
        }
    }

    private String projectListLabel(ChatProject project) {
        int userMessages = 0;
        for (int i = 0; i < project.messages.size(); i++) {
            if ("user".equals(project.messages.get(i).role)) {
                userMessages++;
            }
        }
        return project.title + "\n" + userMessages + " 次对话";
    }

    private ChatProject activeProject() {
        if (chatProjects.isEmpty()) {
            restoreChatProjects();
        }
        if (currentProjectIndex < 0 || currentProjectIndex >= chatProjects.size()) {
            currentProjectIndex = 0;
        }
        return chatProjects.get(currentProjectIndex);
    }

    private void loadCurrentProjectMessages() {
        chatMessages.clear();
        ChatProject project = activeProject();
        for (int i = 0; i < project.messages.size(); i++) {
            chatMessages.add(project.messages.get(i).copy());
        }
    }

    private void saveCurrentProjectFromMessages() {
        if (chatProjects.isEmpty() || currentProjectIndex < 0 || currentProjectIndex >= chatProjects.size()) {
            return;
        }
        ChatProject project = chatProjects.get(currentProjectIndex);
        project.messages.clear();
        for (int i = 0; i < chatMessages.size(); i++) {
            project.messages.add(chatMessages.get(i).copy());
        }
        project.updatedAt = System.currentTimeMillis();
    }

    private void updateCurrentProjectTitle(String prompt) {
        ChatProject project = activeProject();
        if (project.title.startsWith("现场诊断")) {
            String clean = prompt == null ? "" : prompt.trim().replace('\n', ' ');
            if (clean.length() > 16) {
                clean = clean.substring(0, 16);
            }
            if (clean.length() > 0) {
                project.title = clean;
            }
        }
    }

    @Override
    public void openFeature(String id, String title, boolean available) {
        setProjectRailVisible(false);
        if (available && "expert_collab".equals(id)) {
            enterExpertMode();
            return;
        }
        renderChatScreen();
        setChatStatus(title + "筹备中");
    }

    @Override
    public void requestExitExpertMode() {
        exitExpertMode();
    }

    @Override
    public void showExpertStatus(String message) {
        Log.i(KEY_LOG_TAG, "Expert mode status=" + (message == null ? "" : message));
    }

    @Override
    public void prepareExpertMedia() {
        cancelForegroundVoiceListening();
        stopVoiceRecording(false, "expert_media_start");
        resetVoiceSessionForForegroundWake();
    }

    private void enterExpertMode() {
        if (modeController != null) {
            modeController.enterExpert();
        }
    }

    private void exitExpertMode() {
        if (modeController != null) {
            modeController.exitExpert();
        }
    }

    private void showExpertLayer() {
        screenMode = ScreenMode.EXPERT;
        chatLayer.setVisibility(View.GONE);
        previewView.setVisibility(View.GONE);
        cameraOverlay.setVisibility(View.GONE);
        cameraBackButton.setVisibility(View.GONE);
        expertLayer.removeAllViews();
        expertCoordinator = new ExpertCollabCoordinator(this, BuildConfig.COLLAB_SERVER_URL, this);
        expertLayer.addView(expertCoordinator.createView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        expertLayer.setVisibility(View.VISIBLE);
        expertCoordinator.start();
        scheduleForegroundVoiceListening("expert_waiting");
    }

    private void releaseExpertCoordinator() {
        if (expertCoordinator != null) {
            expertCoordinator.release();
            expertCoordinator = null;
        }
        if (expertLayer != null) {
            expertLayer.removeAllViews();
            expertLayer.setVisibility(View.GONE);
        }
    }

    private void renderChatScreen() {
        screenMode = ScreenMode.CHAT;
        if (modeController != null) {
            modeController.setLegacyMode(IntegratedModeController.Mode.CHAT);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                expertLayer.setVisibility(View.GONE);
                chatLayer.setVisibility(View.VISIBLE);
                cameraOverlay.setVisibility(View.GONE);
                cameraBackButton.setVisibility(View.GONE);
                previewView.setVisibility(View.GONE);
                titleText.setText("AI 运维指导 · " + activeProject().title);
                setChatStatus(recordingVoice ? "语音识别中" : chatStatus);
                renderProjectList();
                renderMessages();
                renderComposer();
            }
        });
    }

    private void renderCameraScreen() {
        screenMode = ScreenMode.CAMERA;
        if (modeController != null) {
            modeController.setLegacyMode(IntegratedModeController.Mode.CAMERA);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                expertLayer.setVisibility(View.GONE);
                chatLayer.setVisibility(View.GONE);
                previewView.setVisibility(View.VISIBLE);
                cameraOverlay.setVisibility(View.VISIBLE);
                cameraBackButton.setVisibility(View.VISIBLE);
                boolean hasPhoto = composerImageBytes != null;
                cameraStatusText.setText(hasPhoto
                        ? "已拍摄 · 说“使用照片”继续"
                        : "取景中 · 说“拍照”");
                cameraCaptureButton.setText(hasPhoto ? "✓" : "●");
                cameraCaptureButton.setContentDescription(hasPhoto ? "使用当前现场照片" : "拍摄现场照片");
            }
        });
        if (cameraDevice == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
        }
        scheduleForegroundVoiceListening("camera-screen");
    }

    private void renderMessages() {
        chatMessagesColumn.removeAllViews();
        chatMessagesColumn.addView(assistantHomeHeader());
        for (ChatMessage message : chatMessages) {
            chatMessagesColumn.addView(messageBubble(message));
        }
        final boolean shouldScrollToBottom = scrollChatToBottom;
        scrollChatToBottom = false;
        if (shouldScrollToBottom) {
            scheduleChatScrollToBottom();
        } else {
            scheduleChatScrollToTop();
        }
    }

    private void renderChatStreamMessagesOnly() {
        renderMessages();
        scheduleChatScrollToBottom();
    }

    private void scheduleChatScrollToTop() {
        if (chatScrollView == null) {
            return;
        }
        final int requestId = ++chatScrollRequestId;
        chatScrollView.post(new Runnable() {
            @Override
            public void run() {
                if (requestId != chatScrollRequestId) {
                    return;
                }
                chatScrollView.fullScroll(View.FOCUS_UP);
            }
        });
    }

    private void scheduleChatScrollToBottom() {
        int requestId = ++chatScrollRequestId;
        postChatScrollToBottom(0L, requestId);
        postChatScrollToBottom(32L, requestId);
        postChatScrollToBottom(80L, requestId);
        postChatScrollToBottom(220L, requestId);
        postChatScrollToBottom(480L, requestId);
        postChatScrollToBottom(900L, requestId);
        postChatScrollToBottomAfterLayout(requestId);
    }

    private void postChatScrollToBottom(long delayMs, final int requestId) {
        if (chatScrollView == null) {
            return;
        }
        Runnable scrollAction = new Runnable() {
            @Override
            public void run() {
                if (requestId != chatScrollRequestId) {
                    return;
                }
                scrollChatToBottomNow();
            }
        };
        if (delayMs <= 0L) {
            chatScrollView.post(scrollAction);
        } else {
            chatScrollView.postDelayed(scrollAction, delayMs);
        }
    }

    private void postChatScrollToBottomAfterLayout(final int requestId) {
        if (chatMessagesColumn == null) {
            return;
        }
        final ViewTreeObserver observer = chatMessagesColumn.getViewTreeObserver();
        if (!observer.isAlive()) {
            postChatScrollToBottom(0L, requestId);
            return;
        }
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver currentObserver = chatMessagesColumn.getViewTreeObserver();
                if (currentObserver.isAlive()) {
                    currentObserver.removeOnGlobalLayoutListener(this);
                }
                if (requestId != chatScrollRequestId) {
                    return;
                }
                scrollChatToBottomNow();
                postChatScrollToBottom(48L, requestId);
            }
        });
    }

    private void scrollChatToBottomNow() {
        if (chatScrollView == null || chatMessagesColumn == null) {
            return;
        }
        chatScrollView.fullScroll(View.FOCUS_DOWN);
        int maxScrollY = Math.max(0, chatMessagesColumn.getMeasuredHeight() - chatScrollView.getHeight());
        chatScrollView.scrollTo(0, maxScrollY);
    }

    private View assistantHomeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, 0, 0, dp(16));

        ChatMessage lastUser = lastUserMessage();
        if (lastUser != null && chatMessages.size() <= 1) {
            LinearLayout lastCard = new LinearLayout(this);
            lastCard.setOrientation(LinearLayout.VERTICAL);
            lastCard.setPadding(dp(28), dp(18), dp(28), dp(18));
            lastCard.setBackground(roundRect(Color.WHITE, Color.TRANSPARENT, 18));

            TextView lastLabel = new TextView(this);
            lastLabel.setText("上次对话");
            lastLabel.setTextColor(Color.rgb(130, 130, 130));
            lastLabel.setTextSize(16);
            lastCard.addView(lastLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView lastTitle = new TextView(this);
            lastTitle.setText(activeProject().title);
            lastTitle.setTextColor(Color.rgb(18, 18, 18));
            lastTitle.setTextSize(20);
            lastTitle.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lastTitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lastTitleParams.topMargin = dp(10);
            lastCard.addView(lastTitle, lastTitleParams);

            TextView lastContent = new TextView(this);
            lastContent.setText(lastUser.text);
            lastContent.setTextColor(Color.rgb(54, 54, 54));
            lastContent.setTextSize(17);
            lastContent.setSingleLine(true);
            LinearLayout.LayoutParams lastContentParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lastContentParams.topMargin = dp(8);
            lastCard.addView(lastContent, lastContentParams);

            LinearLayout.LayoutParams lastCardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lastCardParams.leftMargin = dp(0);
            lastCardParams.rightMargin = dp(0);
            lastCardParams.bottomMargin = dp(34);
            header.addView(lastCard, lastCardParams);
        } else {
            View spacer = new View(this);
            header.addView(spacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(42)));
        }

        TextView logo = new TextView(this);
        logo.setText("叮");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(28);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(roundRect(Color.rgb(16, 128, 96), Color.TRANSPARENT, 28));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        logoParams.bottomMargin = dp(8);
        header.addView(logo, logoParams);

        TextView hello = new TextView(this);
        hello.setText("叮当AI运维专家");
        hello.setTextColor(Color.rgb(239, 249, 247));
        hello.setTextSize(25);
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        hello.setGravity(Gravity.CENTER);
        header.addView(hello, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView powered = new TextView(this);
        powered.setText("现场诊断 · 语音优先");
        powered.setTextColor(Color.rgb(133, 176, 173));
        powered.setTextSize(18);
        powered.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams poweredParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        poweredParams.topMargin = dp(8);
        header.addView(powered, poweredParams);

        header.addView(buildHudWorkSurface(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(248)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(24);

        TextView photo = assistantHomeAction("现场拍摄", false);
        photo.setContentDescription("现场拍摄");
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterCameraScreen("assistant-home-photo");
            }
        });
        actions.addView(photo, new LinearLayout.LayoutParams(dp(202), dp(78)));

        TextView speak = assistantHomeAction(recordingVoice ? "结束提问" : "语音提问", true);
        speak.setContentDescription(recordingVoice ? "结束提问" : "语音提问");
        speak.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (recordingVoice) {
                    finishToggleVoiceRecording("assistant_home_finish");
                } else {
                    startToggleVoiceRecording();
                }
            }
        });
        LinearLayout.LayoutParams speakParams = new LinearLayout.LayoutParams(dp(202), dp(78));
        speakParams.leftMargin = dp(24);
        actions.addView(speak, speakParams);

        if (shouldShowHomeActions()) {
            header.addView(actions, actionsParams);
        }
        return header;
    }

    private View buildHudWorkSurface() {
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.HORIZONTAL);
        surface.setPadding(dp(28), dp(24), dp(28), dp(18));

        FrameLayout povPanel = new FrameLayout(this);
        povPanel.setBackground(roundRect(Color.rgb(13, 33, 45), Color.rgb(45, 107, 111), 12));
        LinearLayout.LayoutParams povParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.25f);
        povParams.rightMargin = dp(16);
        surface.addView(povPanel, povParams);

        ChatMessage latestImage = latestImageMessage();
        Bitmap latestSnapshot = composerImagePreviewBitmap;
        if (latestSnapshot == null && composerImagePreviewBase64.length() > 0) {
            latestSnapshot = decodeImagePreviewBitmap(composerImagePreviewBase64);
            composerImagePreviewBitmap = latestSnapshot;
        }
        if (latestSnapshot == null && latestImage != null) {
            if (latestImage.imagePreviewBitmap == null && latestImage.imagePreviewBase64.length() > 0) {
                latestImage.imagePreviewBitmap = decodeImagePreviewBitmap(latestImage.imagePreviewBase64);
            }
            if (latestImage.imagePreviewBitmap != null) {
                latestSnapshot = latestImage.imagePreviewBitmap;
            }
        }
        if (latestSnapshot != null) {
            ImageView snapshot = new ImageView(this);
            snapshot.setScaleType(ImageView.ScaleType.CENTER_CROP);
            snapshot.setImageBitmap(latestSnapshot);
            povPanel.addView(snapshot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        TextView povLabel = hudLabel(latestSnapshot == null ? "POV · 相机待命" : "POV · 最近现场帧");
        FrameLayout.LayoutParams povLabelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        povLabelParams.leftMargin = dp(14);
        povLabelParams.topMargin = dp(14);
        povPanel.addView(povLabel, povLabelParams);

        TextView povFooter = new TextView(this);
        povFooter.setText(latestSnapshot == null ? "说“叮当，拍照”开始采集" : "已同步到本次诊断");
        povFooter.setTextColor(Color.rgb(220, 239, 235));
        povFooter.setTextSize(15);
        povFooter.setPadding(dp(14), dp(8), dp(14), dp(8));
        povFooter.setBackground(roundRect(Color.argb(188, 4, 14, 20), Color.TRANSPARENT, 8));
        FrameLayout.LayoutParams povFooterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        povFooterParams.leftMargin = dp(14);
        povFooterParams.bottomMargin = dp(14);
        povPanel.addView(povFooter, povFooterParams);

        LinearLayout diagnosticsPanel = new LinearLayout(this);
        diagnosticsPanel.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPanel.setPadding(dp(22), dp(18), dp(22), dp(16));
        diagnosticsPanel.setBackground(roundRect(Color.rgb(15, 38, 49), Color.rgb(45, 107, 111), 12));
        surface.addView(diagnosticsPanel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView diagnosisTitle = new TextView(this);
        diagnosisTitle.setText("诊断流");
        diagnosisTitle.setTextColor(Color.rgb(103, 231, 195));
        diagnosisTitle.setTextSize(17);
        diagnosisTitle.setTypeface(Typeface.DEFAULT_BOLD);
        diagnosticsPanel.addView(diagnosisTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView diagnosisState = new TextView(this);
        diagnosisState.setText(chatStatus);
        diagnosisState.setTextColor(Color.rgb(239, 249, 247));
        diagnosisState.setTextSize(20);
        diagnosisState.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams diagnosisStateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diagnosisStateParams.topMargin = dp(12);
        diagnosticsPanel.addView(diagnosisState, diagnosisStateParams);

        TextView diagnosisDetail = new TextView(this);
        String lastAdvice = lastAssistantAdvice();
        diagnosisDetail.setText(lastAdvice.length() == 0
                ? "等待现场语音或快照输入"
                : lastAdvice);
        diagnosisDetail.setTextColor(Color.rgb(163, 195, 192));
        diagnosisDetail.setTextSize(15);
        diagnosisDetail.setMaxLines(4);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        detailParams.topMargin = dp(8);
        diagnosticsPanel.addView(diagnosisDetail, detailParams);

        TextView routeLabel = hudLabel("语音控制在线");
        diagnosticsPanel.addView(routeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return surface;
    }

    private TextView hudLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(103, 231, 195));
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(10), dp(6), dp(10), dp(6));
        label.setBackground(roundRect(Color.argb(190, 9, 52, 57), Color.rgb(53, 144, 133), 7));
        return label;
    }

    private String lastAssistantAdvice() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("assistant".equals(message.role) && "text".equals(message.kind)
                    && message.text != null && message.text.trim().length() > 0) {
                String text = message.text.trim().replace('\n', ' ');
                return text.length() > 86 ? text.substring(0, 86) + "..." : text;
            }
        }
        return "";
    }

    private boolean shouldShowHomeActions() {
        return !shouldShowComposerPanel();
    }

    private boolean shouldShowComposerPanel() {
        if (recordingVoice || composerImageBytes != null || hasLiveTranscriptMessage() || streamingAssistantIndex >= 0) {
            return true;
        }
        if (composerTranscript.trim().length() > 0 && !"语音提问".equals(composerTranscript.trim())) {
            return true;
        }
        return chatMessages.size() > 0;
    }

    private TextView assistantHomeAction(String label, boolean primary) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextColor(primary ? Color.rgb(3, 26, 24) : Color.rgb(173, 241, 223));
        action.setTextSize(21);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(18), 0, dp(18), 0);
        action.setBackground(primary
                ? roundRect(Color.rgb(83, 220, 164), Color.rgb(83, 220, 164), 12)
                : roundRect(Color.rgb(16, 42, 52), Color.rgb(59, 154, 139), 12));
        action.setClickable(true);
        action.setDefaultFocusHighlightEnabled(false);
        return action;
    }

    private ChatMessage lastUserMessage() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("user".equals(message.role) && "text".equals(message.kind) && message.text.trim().length() > 0) {
                return message;
            }
        }
        return null;
    }

    private View messageBubble(ChatMessage message) {
        if ("image".equals(message.kind)) {
            return imageMessageBubble(message);
        }
        TextView bubble = new TextView(this);
        bubble.setText(message.text + (message.streaming ? "▌" : ""));
        bubble.setTextSize(18);
        bubble.setLineSpacing(3f, 1.0f);
        bubble.setPadding(16, 12, 16, 12);
        boolean user = "user".equals(message.role);
        bubble.setTextColor(Color.rgb(22, 22, 22));
        bubble.setBackground(roundRect(user ? Color.rgb(235, 245, 255) : Color.rgb(246, 246, 246), Color.TRANSPARENT, 14));
        return wrapMessageBubble(bubble, user);
    }

    private View imageMessageBubble(ChatMessage message) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(10, 10, 10, 10);
        bubble.setBackground(roundRect(Color.rgb(235, 245, 255), Color.TRANSPARENT, 14));

        if (message.imagePreviewBitmap == null && message.imagePreviewBase64.length() > 0) {
            message.imagePreviewBitmap = decodeImagePreviewBitmap(message.imagePreviewBase64);
        }
        Bitmap preview = message.imagePreviewBitmap;
        if (preview != null) {
            ImageView imageView = new ImageView(this);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(false);
            imageView.setContentDescription("现场照片");
            imageView.setImageBitmap(preview);
            int bubbleWidth = Math.round(getResources().getDisplayMetrics().widthPixels * 0.72f) - dp(20);
            int imageHeight = Math.round(bubbleWidth * preview.getHeight() / (float) preview.getWidth());
            imageHeight = Math.max(dp(120), Math.min(dp(360), imageHeight));
            bubble.addView(imageView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));
        }

        TextView caption = new TextView(this);
        caption.setText(message.text);
        caption.setTextSize(15);
        caption.setTextColor(Color.rgb(68, 76, 84));
        caption.setPadding(4, preview == null ? 0 : 8, 4, 0);
        bubble.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapMessageBubble(bubble, true);
    }

    private View wrapMessageBubble(View bubble, boolean user) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        wrapper.setPadding(0, 6, 0, 6);
        int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.72f);
        wrapper.addView(bubble, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void renderComposer() {
        boolean showComposerPanel = shouldShowComposerPanel();
        composerPanel.setVisibility(showComposerPanel ? View.VISIBLE : View.GONE);
        if (!showComposerPanel) {
            voiceWaveView.stop();
            voiceWaveView.setVisibility(View.GONE);
            transcriptDraftText.setVisibility(View.GONE);
            attachmentPreviewImage.setVisibility(View.GONE);
            attachmentPreviewText.setVisibility(View.GONE);
            return;
        }
        if (composerImageBytes == null) {
            attachmentPreviewImage.setVisibility(View.GONE);
            attachmentPreviewImage.setImageBitmap(null);
            composerImagePreviewBitmap = null;
            attachmentPreviewText.setVisibility(View.GONE);
        } else {
            attachmentPreviewImage.setVisibility(View.GONE);
            if (composerImagePreviewBitmap == null && composerImagePreviewBase64.length() > 0) {
                composerImagePreviewBitmap = decodeImagePreviewBitmap(composerImagePreviewBase64);
            }
            attachmentPreviewImage.setImageBitmap(composerImagePreviewBitmap);
            attachmentPreviewText.setVisibility(View.GONE);
            if (composerImageUploadFailed) {
                attachmentPreviewText.setText("照片上传失败，请检查后端或重新拍照");
            } else if ("local-photo".equals(composerImageId) || composerImageId.length() > 0) {
                attachmentPreviewText.setText("照片已添加");
            } else {
                attachmentPreviewText.setText("照片正在上传");
            }
        }
        if (recordingVoice) {
            if (composerTranscript.trim().length() == 0) {
                transcriptDraftText.setVisibility(View.VISIBLE);
                transcriptDraftText.setText(voiceListeningHint());
                transcriptDraftText.setTextColor(Color.rgb(78, 85, 94));
            } else {
                transcriptDraftText.setVisibility(View.VISIBLE);
                transcriptDraftText.setText(composerTranscript);
            }
            voiceWaveView.setVisibility(View.VISIBLE);
            voiceWaveView.start();
            voiceButton.setText("结束提问");
            voiceButton.setContentDescription("结束提问");
            voiceButton.setTextSize(21);
            voiceButton.setTextColor(Color.WHITE);
            voiceButton.setBackground(roundRect(Color.rgb(18, 133, 96), Color.rgb(18, 133, 96), 24));
        } else {
            voiceWaveView.stop();
            voiceWaveView.setVisibility(View.GONE);
            if (composerTranscript.trim().length() == 0 || "语音提问".equals(composerTranscript.trim())) {
                transcriptDraftText.setVisibility(View.GONE);
                transcriptDraftText.setText("");
            } else {
                transcriptDraftText.setVisibility(View.VISIBLE);
                transcriptDraftText.setText(composerTranscript);
            }
            transcriptDraftText.setTextColor(Color.rgb(120, 120, 120));
            voiceButton.setText("语音提问");
            voiceButton.setContentDescription("语音提问");
            voiceButton.setTextSize(21);
            voiceButton.setTextColor(Color.WHITE);
            voiceButton.setBackground(roundRect(Color.rgb(22, 163, 110), Color.rgb(22, 163, 110), 24));
        }
    }

    private String voiceListeningHint() {
        if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
            return "请说明现场问题";
        }
        if (voiceSessionPurpose == VoiceSessionPurpose.COMMAND) {
            return "请说指令或问题";
        }
        return "正在聆听";
    }

    private void enterCameraScreen(String source) {
        renderCameraScreen();
    }

    private void requestVoicePhotoCapture() {
        pendingVoicePhotoCapture = true;
        cameraStatusText.setText("\u8bed\u97f3\u62cd\u7167\uff0c\u6b63\u5728\u51c6\u5907\u76f8\u673a");
        if (screenMode != ScreenMode.CAMERA) {
            enterCameraScreen("voice-command");
        }
        capturePendingVoicePhotoIfReady();
    }

    private void capturePendingVoicePhotoIfReady() {
        if (!pendingVoicePhotoCapture) {
            return;
        }
        if (screenMode == ScreenMode.CAMERA && cameraDevice != null && captureSession != null && imageReader != null && !captureInFlight) {
            pendingVoicePhotoCapture = false;
            captureStillImage();
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                capturePendingVoicePhotoIfReady();
            }
        }, 250L);
    }

    private void confirmCapturedPhoto(byte[] jpegBytes) {
        Log.i(KEY_LOG_TAG, "Photo captured bytes=" + (jpegBytes == null ? 0 : jpegBytes.length));
        final long imageGeneration = ++composerImageGeneration;
        composerImageBytes = jpegBytes;
        composerImageId = "";
        composerImagePreviewBase64 = createImagePreviewBase64(jpegBytes);
        composerImagePreviewBitmap = decodeImagePreviewBitmap(composerImagePreviewBase64);
        composerImageUploadFailed = false;
        showComposerAttachment("正在上传");
        renderChatScreen();
        uploadImageForChat(jpegBytes, imageGeneration);
        voiceStreamState = VoiceStreamState.IDLE;
        if (VOICE_WORKFLOW_ENABLED
                && voiceEventStateMachine.onPhotoCaptured() == VoiceEventStateMachine.Signal.START_DESCRIPTION) {
            beginVoiceEventDescription();
            return;
        }
        scheduleForegroundVoiceListening("photo-captured");
    }

    private void beginVoiceEventDescription() {
        cancelVoiceEventDescriptionTimeout();
        setChatStatus("请说明现场问题");
        composerTranscript = "";
        renderComposer();
        startToggleVoiceRecording();
        voiceEventDescriptionTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (voiceEventStateMachine.state() != VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
                    return;
                }
                stopVoiceRecording(false, "event_description_timeout");
                submitVoiceEvent(voiceEventStateMachine.onDescriptionTimeout());
            }
        };
        mainHandler.postDelayed(voiceEventDescriptionTimeoutRunnable, VOICE_EVENT_DESCRIPTION_TIMEOUT_MS);
    }

    private void cancelVoiceEventDescriptionTimeout() {
        if (voiceEventDescriptionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(voiceEventDescriptionTimeoutRunnable);
            voiceEventDescriptionTimeoutRunnable = null;
        }
    }

    private void submitVoiceEvent(VoiceEventStateMachine.Signal signal) {
        if (signal != VoiceEventStateMachine.Signal.SUBMIT_TO_AI) {
            return;
        }
        cancelVoiceEventDescriptionTimeout();
        String description = voiceEventStateMachine.eventDescription();
        composerTranscript = description.length() == 0
                ? "\u8bf7\u7ed3\u5408\u8fd9\u5f20\u73b0\u573a\u7167\u7247\u7ed9\u51fa\u6392\u67e5\u5efa\u8bae"
                : description;
        voiceEventStateMachine.reset();
        voiceStreamState = VoiceStreamState.AI_PENDING;
        setChatStatus("已听清，正在分析");
        renderComposer();
        sendComposerToAi();
    }

    private void showComposerAttachment(String label) {
        final String safeLabel = label == null ? "" : label;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                attachmentPreviewText.setVisibility(View.VISIBLE);
                attachmentPreviewText.setText("照片 " + safeLabel);
                renderComposer();
            }
        });
    }

    private void clearComposerImage() {
        composerImageGeneration++;
        composerImageBytes = null;
        composerImageId = "";
        composerImagePreviewBase64 = "";
        composerImagePreviewBitmap = null;
        composerImageUploadFailed = false;
        sendAfterImageUpload = false;
    }

    private void uploadImageForChat(final byte[] jpegBytes, final long imageGeneration) {
        if (DIRECT_GPT_ENABLED || backendChatClient == null || jpegBytes == null || jpegBytes.length == 0) {
            onBackendImageUploaded("local-photo", jpegBytes, imageGeneration);
            return;
        }
        backendChatClient.uploadImageForChat(backendSessionIdForActiveProject(), jpegBytes, new BackendImageUploadCallback() {
            @Override
            public void onUploaded(final String sessionId, final String imageId, final int imageBytes) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (imageGeneration != composerImageGeneration) {
                            return;
                        }
                        activeProject().backendSessionId = sessionId;
                        persistChatProjects();
                        onBackendImageUploaded(imageId, jpegBytes, imageGeneration);
                    }
                });
            }

            @Override
            public void onError(final Exception error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (imageGeneration != composerImageGeneration) {
                            return;
                        }
                        composerImageId = "";
                        composerImageUploadFailed = true;
                        showComposerAttachment("上传失败：" + safeMessage(error));
                    }
                });
            }
        });
    }

    private void onBackendImageUploaded(String imageId, byte[] imageBytes, long imageGeneration) {
        if (imageGeneration != composerImageGeneration) {
            return;
        }
        Log.i(KEY_LOG_TAG, "Photo context ready imageId=" + (imageId == null ? "" : imageId)
                + " bytes=" + (imageBytes == null ? 0 : imageBytes.length));
        composerImageBytes = imageBytes;
        composerImageId = imageId == null ? "" : imageId;
        composerImagePreviewBase64 = createImagePreviewBase64(imageBytes);
        composerImagePreviewBitmap = decodeImagePreviewBitmap(composerImagePreviewBase64);
        composerImageUploadFailed = false;
        showComposerAttachment(composerImageId.length() > 0 ? "已上传" : "已添加");
        renderChatScreen();
        if (sendAfterImageUpload && composerTranscript.trim().length() > 0) {
            sendAfterImageUpload = false;
            sendComposerToAi();
        }
    }

    private void appendUserImageMessage(String imageId, String imagePreviewBase64) {
        chatMessages.add(new ChatMessage("user", "image", "现场照片已随问题发送", imageId, imagePreviewBase64, false));
    }

    private ChatMessage latestImageMessage() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("image".equals(message.kind) && message.imageId != null && message.imageId.length() > 0) {
                return message;
            }
        }
        return null;
    }

    private void appendUserTranscriptMessage(String text) {
        String safeText = sanitizeTranscriptForDisplay(text);
        if (safeText.length() == 0) {
            return;
        }
        chatMessages.add(new ChatMessage("user", "text", safeText, "", false));
        scrollChatToBottom = true;
        renderChatScreen();
    }

    private boolean hasLiveTranscriptMessage() {
        return liveTranscriptMessageIndex >= 0
                && liveTranscriptMessageIndex < chatMessages.size()
                && "user".equals(chatMessages.get(liveTranscriptMessageIndex).role)
                && "text".equals(chatMessages.get(liveTranscriptMessageIndex).kind);
    }

    private void updateLiveTranscriptMessage(String text, boolean finalText) {
        String safeText = sanitizeTranscriptForDisplay(text);
        if (safeText.length() == 0) {
            return;
        }
        if (!hasLiveTranscriptMessage()) {
            chatMessages.add(new ChatMessage("user", "text", safeText, "", "", !finalText));
            liveTranscriptMessageIndex = chatMessages.size() - 1;
        } else {
            ChatMessage message = chatMessages.get(liveTranscriptMessageIndex);
            message.text = safeText;
            message.streaming = !finalText;
        }
        scrollChatToBottom = true;
        if (finalText) {
            flushPendingChatStreamRender();
        } else {
            scheduleChatStreamRender();
        }
    }

    private void clearLiveTranscriptMessageIfStreaming() {
        if (hasLiveTranscriptMessage() && chatMessages.get(liveTranscriptMessageIndex).streaming) {
            chatMessages.remove(liveTranscriptMessageIndex);
        }
        liveTranscriptMessageIndex = -1;
        scrollChatToBottom = true;
        renderChatScreen();
    }

    private boolean handleVoiceCommand(String text) {
        VoiceCommand command = classifyVoiceCommand(text);
        if (command == VoiceCommand.NONE) {
            return false;
        }
        clearLiveTranscriptMessageIfStreaming();
        if (command == VoiceCommand.OPEN_EXPERT) {
            voiceStreamState = VoiceStreamState.IDLE;
            enterExpertMode();
            return true;
        }
        if (command == VoiceCommand.OPEN_CAMERA) {
            enterCameraScreen("voice-open-camera");
            voiceStreamState = VoiceStreamState.IDLE;
            scheduleForegroundVoiceListening("camera-opened");
            return true;
        }
        if (command == VoiceCommand.TAKE_PHOTO) {
            if (VOICE_WORKFLOW_ENABLED) {
                voiceEventStateMachine.onCommand(VoiceCommandRouter.Command.PHOTO);
            }
            requestVoicePhotoCapture();
            return true;
        }
        if (command == VoiceCommand.RETAKE_PHOTO) {
            clearComposerImage();
            requestVoicePhotoCapture();
            return true;
        }
        if (command == VoiceCommand.SEND) {
            boolean hasImageContext = composerImageBytes != null || composerImageId.length() > 0 || latestImageMessage() != null;
            if (hasImageContext) {
                composerTranscript = "\u8bf7\u7ed3\u5408\u8fd9\u5f20\u73b0\u573a\u7167\u7247\u7ed9\u51fa\u6392\u67e5\u5efa\u8bae";
            }
            sendComposerToAi();
            return true;
        }
        if (command == VoiceCommand.BACK_TO_CHAT) {
            if (screenMode == ScreenMode.EXPERT) {
                exitExpertMode();
            } else {
                returnToChatFromCameraFlow();
            }
            return true;
        }
        if (command == VoiceCommand.START_VOICE) {
            renderComposer();
            startToggleVoiceRecording();
            return true;
        }
        if (command == VoiceCommand.NEW_PROJECT) {
            createNewProjectChat();
            setProjectRailVisible(false);
            voiceStreamState = VoiceStreamState.IDLE;
            renderChatScreen();
            return true;
        }
        if (command == VoiceCommand.SHOW_RECORDS) {
            renderChatScreen();
            setProjectRailVisible(true);
            voiceStreamState = VoiceStreamState.IDLE;
            return true;
        }
        if (command == VoiceCommand.NEXT_PROJECT) {
            switchProjectChat(Math.min(chatProjects.size() - 1, currentProjectIndex + 1));
            setProjectRailVisible(true);
            voiceStreamState = VoiceStreamState.IDLE;
            return true;
        }
        if (command == VoiceCommand.PREVIOUS_PROJECT) {
            switchProjectChat(Math.max(0, currentProjectIndex - 1));
            setProjectRailVisible(true);
            voiceStreamState = VoiceStreamState.IDLE;
            return true;
        }
        if (command == VoiceCommand.LATEST_PROJECT) {
            switchProjectChat(0);
            setProjectRailVisible(true);
            voiceStreamState = VoiceStreamState.IDLE;
            return true;
        }
        return false;
    }

    private boolean handleVoicePreviewInteraction(String text) {
        VoiceCommandRouter.Command command = voiceCommandRouter.route(text);
        if (command == VoiceCommandRouter.Command.NONE) {
            if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
                submitVoiceEvent(voiceEventStateMachine.onDescriptionFinal(text));
                return true;
            }
            return false;
        }
        clearLiveTranscriptMessageIfStreaming();
        if (isCommandOverlayVisible()) {
            if (command == VoiceCommandRouter.Command.BACK || command == VoiceCommandRouter.Command.CANCEL) {
                hideCommandOverlay();
            } else if (command == VoiceCommandRouter.Command.HELP || command == VoiceCommandRouter.Command.REPEAT) {
                showCommandOverlay();
            } else {
                setChatStatus("请先说“返回”继续当前任务");
            }
            return true;
        }
        if (screenMode == ScreenMode.EXPERT
                && (command == VoiceCommandRouter.Command.BACK
                || command == VoiceCommandRouter.Command.CANCEL)) {
            exitExpertMode();
            return true;
        }
        VoiceEventStateMachine.Signal signal = voiceEventStateMachine.onCommand(command);
        if (signal == VoiceEventStateMachine.Signal.CAPTURE_PHOTO) {
            if (command == VoiceCommandRouter.Command.RETAKE) {
                clearComposerImage();
            }
            requestVoicePhotoCapture();
            return true;
        }
        if (signal == VoiceEventStateMachine.Signal.SUBMIT_TO_AI) {
            submitVoiceEvent(signal);
            return true;
        }
        if (signal == VoiceEventStateMachine.Signal.CANCEL_EVENT) {
            cancelVoiceEventDescriptionTimeout();
            composerTranscript = "";
            clearComposerImage();
            if (screenMode == ScreenMode.CAMERA) {
                returnToChatFromCameraFlow();
            } else {
                setChatStatus("已取消当前事件");
                renderComposer();
            }
            return true;
        }
        if (command == VoiceCommandRouter.Command.EXPERT) {
            enterExpertMode();
            return true;
        }
        if (command == VoiceCommandRouter.Command.BACK) {
            if (screenMode == ScreenMode.EXPERT) {
                exitExpertMode();
            } else {
                returnToChatFromCameraFlow();
            }
            return true;
        }
        if (command == VoiceCommandRouter.Command.INSPECTION) {
            openFeature("equipment_inspection", "设备巡检", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.RECORD) {
            openFeature("field_records", "现场记录", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.DEVICE) {
            openFeature("asset_records", "设备档案", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.KNOWLEDGE) {
            openFeature("knowledge_base", "运维知识库", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.WORK_ORDER) {
            openFeature("work_orders", "运维工单", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.SAFETY) {
            openFeature("safe_operations", "安全作业", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.REPORT) {
            openFeature("operations_reports", "运维报告", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.TRAINING) {
            openFeature("training_drills", "培训演练", false);
            return true;
        }
        if (command == VoiceCommandRouter.Command.HELP) {
            showCommandOverlay();
            return true;
        }
        if (command == VoiceCommandRouter.Command.NORMAL
                || command == VoiceCommandRouter.Command.ABNORMAL
                || command == VoiceCommandRouter.Command.NEXT
                || command == VoiceCommandRouter.Command.PREVIOUS
                || command == VoiceCommandRouter.Command.APPEND
                || command == VoiceCommandRouter.Command.RETRY
                || command == VoiceCommandRouter.Command.SAVE
                || command == VoiceCommandRouter.Command.CONFIRM
                || command == VoiceCommandRouter.Command.REPEAT) {
            setChatStatus("该语音操作将在巡检与记录模块启用");
            return true;
        }
        return true;
    }

    /** Finishes a camera or voice event flow before returning to the chat surface. */
    private void returnToChatFromCameraFlow() {
        cancelForegroundVoiceListening();
        pendingVoicePhotoCapture = false;
        cancelVoiceEventDescriptionTimeout();
        voiceEventStateMachine.reset();
        composerTranscript = "";
        stopVoiceRecording(false, "voice_back");
        closeCamera();
        stopCameraThread();
        voiceSessionPurpose = VoiceSessionPurpose.NONE;
        voiceStreamState = VoiceStreamState.IDLE;
        renderChatScreen();
        scheduleForegroundVoiceListening("voice-command-back");
    }

    private VoiceCommand classifyVoiceCommand(String text) {
        return VoiceCommand.valueOf(legacyVoiceCommandRouter.route(text).name());
    }

    private String normalizeVoiceCommandText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("\u3002", "")
                .replace("\uff01", "")
                .replace("\uff1f", "")
                .replace("\uff0c", "")
                .replace("\u3001", "")
                .trim();
    }

    private String compactVoiceCommandCandidate(String text) {
        return normalizeVoiceCommandText(text)
                .replace("\u53ee\u5f53", "")
                .replace("\u5c0f\u53ee", "")
                .replace("\u5c0f\u4e01", "")
                .replace("\u8bf7", "")
                .replace("\u5e2e\u6211", "")
                .replace("\u4e00\u4e0b", "");
    }

    private boolean isLikelyVoiceCommandPhrase(String text) {
        return text != null && text.length() > 0 && text.length() <= MAX_VOICE_COMMAND_CHARS;
    }

    private boolean matchesVoiceCommand(String text, String[] words) {
        if (!isLikelyVoiceCommandPhrase(text)) {
            return false;
        }
        for (String word : words) {
            if (word != null && word.length() > 0
                    && (text.equals(word) || text.startsWith(word) || text.endsWith(word))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (word != null && word.length() > 0 && text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDingdangWakePrefix(String text) {
        String normalized = normalizeVoiceCommandText(text);
        return normalized.startsWith("\u53ee\u5f53")
                || normalized.startsWith("\u5c0f\u53ee")
                || normalized.startsWith("\u5c0f\u4e01");
    }

    private boolean isDingdangWakeOnly(String text) {
        String normalized = normalizeVoiceCommandText(text);
        return normalized.equals("\u53ee\u5f53")
                || normalized.equals("\u5c0f\u53ee")
                || normalized.equals("\u5c0f\u4e01");
    }

    private boolean hasWakePrefixGrace() {
        return wakePrefixGraceUntilMs > 0L && SystemClock.elapsedRealtime() <= wakePrefixGraceUntilMs;
    }

    private String stripDingdangWakePrefix(String text) {
        String normalized = normalizeVoiceCommandText(text);
        if (normalized.startsWith("\u53ee\u5f53")) {
            return normalized.substring(2);
        }
        if (normalized.startsWith("\u5c0f\u53ee") || normalized.startsWith("\u5c0f\u4e01")) {
            return normalized.substring(2);
        }
        return text;
    }

    private boolean isIgnorableVoiceUtterance(String text) {
        String normalized = normalizeVoiceCommandText(text);
        if (normalized.length() == 0) {
            return true;
        }
        if (normalized.equals("\u597d")
                || normalized.equals("\u55ef")
                || normalized.equals("\u554a")
                || normalized.equals("\u5443")
                || normalized.equals("\u54e6")
                || normalized.equals("\u5582")
                || normalized.equals("\u662f")) {
            return true;
        }
        return isNumericFillerNoise(normalized);
    }

    private boolean isNumericFillerNoise(String normalized) {
        if (normalized == null || normalized.length() == 0) {
            return false;
        }
        String stripped = normalized.replaceAll("[0-9\uff10-\uff19]", "");
        return stripped.length() > 0
                && stripped.length() <= 2
                && (stripped.equals("\u55ef")
                || stripped.equals("\u554a")
                || stripped.equals("\u5443")
                || stripped.equals("\u54e6"));
    }

    private void appendAssistantMessage(String text) {
        chatMessages.add(new ChatMessage("assistant", "text", text, "", false));
    }

    private void appendAssistantStreamingMessage() {
        chatMessages.add(new ChatMessage("assistant", "text", "", "", true));
        streamingAssistantIndex = chatMessages.size() - 1;
        scrollChatToBottom = true;
        lastChatStreamRenderAtMs = 0L;
        renderChatStreamMessagesOnly();
    }

    private void scheduleChatStreamRender() {
        if (chatStreamRenderRunnable != null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long delayMs = Math.max(0L, CHAT_STREAM_RENDER_INTERVAL_MS - (now - lastChatStreamRenderAtMs));
        chatStreamRenderRunnable = new Runnable() {
            @Override
            public void run() {
                chatStreamRenderRunnable = null;
                lastChatStreamRenderAtMs = SystemClock.elapsedRealtime();
                renderChatStreamMessagesOnly();
            }
        };
        if (delayMs <= 0L) {
            mainHandler.post(chatStreamRenderRunnable);
        } else {
            mainHandler.postDelayed(chatStreamRenderRunnable, delayMs);
        }
    }

    private void flushPendingChatStreamRender() {
        if (chatStreamRenderRunnable != null) {
            mainHandler.removeCallbacks(chatStreamRenderRunnable);
            chatStreamRenderRunnable = null;
        }
        lastChatStreamRenderAtMs = SystemClock.elapsedRealtime();
        renderChatStreamMessagesOnly();
    }

    private void cancelPendingChatStreamRender() {
        if (chatStreamRenderRunnable != null) {
            mainHandler.removeCallbacks(chatStreamRenderRunnable);
            chatStreamRenderRunnable = null;
        }
    }

    private void markGptStreamStart(String imageId, String prompt) {
        gptStreamStartedAtMs = SystemClock.elapsedRealtime();
        gptFirstDeltaLogged = false;
        Log.i(KEY_LOG_TAG, "GPT stream start promptChars=" + prompt.length()
                + " hasImageId=" + (imageId != null && imageId.length() > 0));
    }

    private void logGptFirstDeltaIfNeeded(String delta) {
        long latencyMs;
        synchronized (this) {
            if (gptFirstDeltaLogged || gptStreamStartedAtMs <= 0L) {
                return;
            }
            gptFirstDeltaLogged = true;
            latencyMs = SystemClock.elapsedRealtime() - gptStreamStartedAtMs;
        }
        Log.i(KEY_LOG_TAG, "GPT stream first delta latencyMs=" + latencyMs
                + " deltaChars=" + (delta == null ? 0 : delta.length()));
    }

    private void updateAssistantStreamingMessage(String delta) {
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
            ChatMessage message = chatMessages.get(streamingAssistantIndex);
            message.text = message.text + delta;
            scrollChatToBottom = true;
            scheduleChatStreamRender();
        }
    }

    private void finalizeAssistantStreamingMessage() {
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
            chatMessages.get(streamingAssistantIndex).streaming = false;
        }
        streamingAssistantIndex = -1;
        setChatStatus("在线");
        persistChatProjects();
        scrollChatToBottom = true;
        cancelPendingChatStreamRender();
        renderChatStreamMessagesOnly();
        voiceStreamState = VoiceStreamState.IDLE;
        scheduleForegroundVoiceListening("ai-complete");
    }

    private boolean isActiveGptRequest(int requestGeneration) {
        return streamingAssistantIndex >= 0 && gptRequestGeneration == requestGeneration;
    }

    private void scheduleGptRequestWatchdog(final int requestGeneration) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isActiveGptRequest(requestGeneration)) {
                    return;
                }
                Log.w(KEY_LOG_TAG, "GPT stream watchdog still waiting requestGeneration=" + requestGeneration);
                updateAssistantStreamingMessage("\n\n\u7f51\u7edc\u8f83\u6162\uff0c\u4ecd\u5728\u7b49\u5f85 AI \u56de\u590d...");
            }
        }, GPT_REQUEST_WATCHDOG_MS);
    }

    private void sendComposerToAi() {
        final String prompt = composerTranscript.trim();
        final byte[] image = composerImageBytes;
        final byte[] imageForAi = DIRECT_GPT_ENABLED && image == null ? lastDirectAiContextImage : image;
        final String imageId = composerImageId;
        ChatMessage contextImage = (!DIRECT_GPT_ENABLED && image == null) ? latestImageMessage() : null;
        final String imagePreviewBase64 = image != null ? composerImagePreviewBase64 : "";
        String resolvedImageId = "";
        if (imageId.length() > 0) {
            resolvedImageId = imageId;
        } else if (DIRECT_GPT_ENABLED) {
            resolvedImageId = imageForAi != null ? "local-photo" : "";
        } else if (contextImage != null) {
            resolvedImageId = contextImage.imageId;
        }
        final String effectiveImageId = resolvedImageId;
        Log.i(KEY_LOG_TAG, "sendComposerToAi promptChars=" + prompt.length()
                + " hasImageBytes=" + (image != null && image.length > 0)
                + " effectiveImageId=" + effectiveImageId);
        if (image != null && effectiveImageId.length() == 0) {
            if (composerImageUploadFailed) {
                sendAfterImageUpload = false;
                showComposerAttachment("上传失败，请检查后端后重试");
                setChatStatus("照片上传失败");
            } else {
                sendAfterImageUpload = true;
                showComposerAttachment("正在上传，上传完自动发送");
                setChatStatus("照片上传中");
            }
            renderComposer();
            return;
        }
        if (prompt.length() == 0) {
            setComposerStatus("");
            return;
        }
        if (isIdentityQuestion(prompt)) {
            updateCurrentProjectTitle(prompt);
            if (hasLiveTranscriptMessage()) {
                updateLiveTranscriptMessage(prompt, true);
                liveTranscriptMessageIndex = -1;
            } else {
                appendUserTranscriptMessage(prompt);
            }
            composerTranscript = "";
            renderComposer();
            appendAssistantMessage(AI_IDENTITY_RESPONSE);
            scrollChatToBottom = true;
            persistChatProjects();
            renderChatScreen();
            return;
        }
        updateCurrentProjectTitle(prompt);
        if (image != null) {
            appendUserImageMessage(effectiveImageId, imagePreviewBase64);
            lastDirectAiContextImage = Arrays.copyOf(image, image.length);
        }
        if (hasLiveTranscriptMessage()) {
            updateLiveTranscriptMessage(prompt, true);
            liveTranscriptMessageIndex = -1;
        } else {
            appendUserTranscriptMessage(prompt);
        }
        composerTranscript = "";
        composerImageBytes = null;
        composerImageId = "";
        composerImagePreviewBase64 = "";
        composerImagePreviewBitmap = null;
        composerImageUploadFailed = false;
        sendAfterImageUpload = false;
        renderComposer();
        appendAssistantStreamingMessage();
        setChatStatus("正在分析");
        markGptStreamStart(effectiveImageId, prompt);
        final int requestGeneration = ++gptRequestGeneration;
        scheduleGptRequestWatchdog(requestGeneration);
        final String requestPrompt = DIRECT_GPT_ENABLED ? buildDirectAiRequestPrompt(prompt) : prompt;
        chatAiClient.send(requestPrompt, effectiveImageId, imageForAi, new StreamingCallback() {
            @Override
            public void onDelta(String text) {
                final String delta = text;
                logGptFirstDeltaIfNeeded(delta);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateAssistantStreamingMessage(delta);
                    }
                });
            }

            @Override
            public void onComplete() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isActiveGptRequest(requestGeneration)) {
                            return;
                        }
                        Log.i(KEY_LOG_TAG, "GPT stream complete");
                        finalizeAssistantStreamingMessage();
                    }
                });
            }

            @Override
            public void onError(final Exception error) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isActiveGptRequest(requestGeneration)) {
                            return;
                        }
                        Log.w(KEY_LOG_TAG, "GPT stream error " + safeMessage(error));
                        updateAssistantStreamingMessage("\n\nAI 调用失败：" + safeMessage(error));
                        finalizeAssistantStreamingMessage();
                    }
                });
            }
        });
        persistChatProjects();
    }

    private String buildDirectAiRequestPrompt(String prompt) {
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, chatMessages.size() - 6);
        for (int i = start; i < chatMessages.size(); i++) {
            ChatMessage message = chatMessages.get(i);
            if (message.streaming || !"text".equals(message.kind) || message.text == null) {
                continue;
            }
            String text = message.text.trim();
            if (text.length() == 0 || text.equals(prompt)) {
                continue;
            }
            if (text.length() > 320) {
                text = text.substring(0, 320) + "...";
            }
            context.append("user".equals(message.role) ? "现场人员：" : "此前建议：")
                    .append(text)
                    .append('\n');
        }
        if (context.length() == 0) {
            return prompt;
        }
        return "以下是同一现场事件的已确认上下文，仅在与当前问题相关时参考：\n"
                + context + "\n当前问题：" + prompt;
    }

    private boolean isIdentityQuestion(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace(" ", "").replace("？", "?");
        if (normalized.length() == 0) {
            return false;
        }
        boolean asksAboutAssistant = normalized.contains("你")
                || normalized.contains("叮当")
                || normalized.contains("ai")
                || normalized.contains("助手")
                || normalized.contains("系统");
        boolean asksModel = normalized.contains("什么模型")
                || normalized.contains("哪个模型")
                || normalized.contains("用的什么模型")
                || normalized.contains("用什么模型")
                || normalized.contains("模型是什么")
                || normalized.contains("底层模型")
                || normalized.contains("大模型");
        boolean genericModelQuestion = normalized.equals("什么模型")
                || normalized.equals("是什么模型")
                || normalized.equals("你是什么模型")
                || normalized.equals("用的什么模型")
                || normalized.equals("用什么模型")
                || normalized.equals("模型是什么");
        return (asksAboutAssistant && asksModel)
                || genericModelQuestion
                || normalized.contains("你是谁")
                || normalized.contains("你叫什么")
                || normalized.contains("谁研发")
                || normalized.contains("谁开发")
                || normalized.contains("谁做的")
                || normalized.contains("谁家的")
                || normalized.contains("哪个公司")
                || normalized.contains("哪家公司")
                || normalized.contains("厂家是谁")
                || normalized.contains("供应商是谁");
    }

    private void setComposerStatus(String text) {
        composerTranscript = text;
        renderComposer();
    }

    private String createImagePreviewBase64(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return "";
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (bitmap == null) {
            return "";
        }
        Bitmap scaled = scaleBitmapToMaxEdge(bitmap, PREVIEW_MAX_IMAGE_EDGE);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 76, output);
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (scaled != bitmap) {
                scaled.recycle();
            }
            bitmap.recycle();
        }
    }

    private Bitmap decodeImagePreviewBitmap(String imagePreviewBase64) {
        if (imagePreviewBase64 == null || imagePreviewBase64.length() == 0) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(imagePreviewBase64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap scaleBitmapToMaxEdge(Bitmap bitmap, int maxEdge) {
        if (bitmap == null || maxEdge <= 0) {
            return bitmap;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return bitmap;
        }
        float scale = maxEdge / (float) longest;
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
    }

    private String backendSessionIdForActiveProject() {
        ChatProject project = activeProject();
        if (project.backendSessionId == null) {
            project.backendSessionId = "";
        }
        return project.backendSessionId;
    }

    private ChatAiClient createChatAiClient() {
            backendChatClient = new BackendChatClient(DINGDANG_BACKEND_BASE_URL, DINGDANG_BACKEND_API_KEY);
            if (DIRECT_GPT_ENABLED) {
            return new DirectGptClient(DIRECT_GPT_BASE_URL, DIRECT_GPT_MODEL, DIRECT_GPT_REASONING_EFFORT, DIRECT_GPT_API_KEY);
            }
        return new BackendGptClient(backendChatClient, new BackendGptClient.SessionProvider() {
            @Override
            public String sessionId() {
                return backendSessionIdForActiveProject();
            }
        });
    }

    private RealtimeAsrClient createRealtimeAsrClient() {
        if (!DIRECT_GPT_ENABLED && backendChatClient != null) {
            return backendChatClient.withSessionProvider(new BackendChatClient.SessionProvider() {
                @Override
                public String sessionId() {
                    return backendSessionIdForActiveProject();
                }
            });
        }
        return new DirectAsrClient(DIRECT_ASR_ENDPOINT, DIRECT_ASR_API_KEY, DIRECT_ASR_MODEL);
    }

    private void scheduleForegroundVoiceListening(String reason) {
        cancelForegroundVoiceListening();
        if (!isForegroundWakeListeningEnabled()) {
            return;
        }
        if (OFFLINE_WAKE_ENABLED) {
            if (shouldStartOfflineWakeListening() && wakeWordEngine != null) {
                Log.i(KEY_LOG_TAG, "Offline wake start reason=" + reason);
                setChatStatus("叮当待命中");
                wakeWordEngine.start(new WakeWordEngine.Listener() {
                    @Override
                    public void onWakeWordDetected() {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (shouldStartOfflineWakeListening()) {
                                    beginVoiceCommandAfterWake();
                                }
                            }
                        });
                    }

                    @Override
                    public void onEngineUnavailable(final String reason) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (shouldStartOfflineWakeListening()) {
                                    setChatStatus(reason);
                                }
                            }
                        });
                    }
                });
            } else if (shouldStartOfflineWakeListening()) {
                setChatStatus("离线唤醒不可用，请重新打开应用");
            }
            return;
        }
        voiceAutoListenArmed = true;
        if (!shouldStartForegroundVoiceListening()) {
            return;
        }
        foregroundAutoVoiceStartRunnable = new Runnable() {
            @Override
            public void run() {
                foregroundAutoVoiceStartRunnable = null;
                if (shouldStartForegroundVoiceListening()) {
                    Log.i(KEY_LOG_TAG, "Foreground voice auto start reason=" + reason);
                    voiceStartedFromAutoWindow = true;
                    voiceSessionPurpose = VoiceSessionPurpose.WAKE;
                    wakeFeedbackDelivered = false;
                    voiceAutoListenArmed = false;
                    startToggleVoiceRecording();
                }
            }
        };
        mainHandler.postDelayed(foregroundAutoVoiceStartRunnable, foregroundVoiceStartDelayMs(reason));
    }

    private long foregroundVoiceStartDelayMs(String reason) {
        if ("wake-listen-retry".equals(reason)) {
            return FOREGROUND_WAKE_RETRY_DELAY_MS;
        }
        return 600L;
    }

    private void cancelForegroundVoiceListening() {
        if (foregroundAutoVoiceStartRunnable != null) {
            mainHandler.removeCallbacks(foregroundAutoVoiceStartRunnable);
            foregroundAutoVoiceStartRunnable = null;
        }
        voiceAutoListenArmed = false;
        if (wakeWordEngine != null) {
            wakeWordEngine.stop();
        }
    }

    private void resetVoiceSessionForForegroundWake() {
        voiceAsrSessionGate.invalidate();
        voiceSessionPurpose = VoiceSessionPurpose.NONE;
        voiceStreamState = VoiceStreamState.IDLE;
        voiceStartedFromAutoWindow = false;
        wakeFeedbackDelivered = false;
        wakePrefixGraceUntilMs = 0L;
    }

    private boolean shouldStartOfflineWakeListening() {
        return isVoiceControlAvailableOnCurrentScreen()
                && isForegroundWakeListeningEnabled()
                && !recordingVoice
                && voiceStreamState == VoiceStreamState.IDLE
                && voiceSessionPurpose == VoiceSessionPurpose.NONE
                && streamingAssistantIndex < 0
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldStartForegroundVoiceListening() {
        return isVoiceControlAvailableOnCurrentScreen()
                && isForegroundWakeListeningEnabled()
                && voiceAutoListenArmed
                && !recordingVoice
                && voiceStreamState == VoiceStreamState.IDLE
                && voiceSessionPurpose == VoiceSessionPurpose.NONE
                && streamingAssistantIndex < 0
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldContinueForegroundWakeListening(String code) {
        if (!isForegroundWakeListeningEnabled() || streamingAssistantIndex >= 0) {
            return false;
        }
        String safeCode = code == null ? "" : code;
        return isVoiceControlAvailableOnCurrentScreen()
                && !safeCode.startsWith("asr_error:")
                && !"asr_endpoint_missing".equals(safeCode)
                && !"asr_realtime_unavailable".equals(safeCode);
    }

    private static boolean isForegroundWakeListeningEnabled() {
        return "com.codex.air3nativecamera.dingdangmanager.butler".equals(APP_ID)
                || VOICE_WORKFLOW_ENABLED;
    }

    private boolean isVoiceControlAvailableOnCurrentScreen() {
        return screenMode == ScreenMode.CHAT
                || screenMode == ScreenMode.CAMERA
                || (screenMode == ScreenMode.EXPERT
                && expertCoordinator != null
                && expertCoordinator.canUseForegroundVoiceControl());
    }

    private void startToggleVoiceRecording() {
        boolean autoWindowStart = voiceStartedFromAutoWindow;
        voiceStartedFromAutoWindow = false;
        if (recordingVoice) {
            finishToggleVoiceRecording("manual_finish");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        try {
            if (!autoWindowStart && voiceSessionPurpose == VoiceSessionPurpose.NONE) {
                voiceSessionPurpose = VoiceSessionPurpose.COMMAND;
            }
            File file = new File(getFilesDir(), "last_voice_upload.wav");
            int minBufferSize = AudioRecord.getMinBufferSize(
                    VOICE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBufferSize, VOICE_SAMPLE_RATE_HZ);
            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    VOICE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            recorder.startRecording();
            voiceRecorder = recorder;
            voiceFile = file;
            recordingVoice = true;
            voiceStartedFromAutoWindow = autoWindowStart;
            voiceRecordingStartedAtMs = SystemClock.elapsedRealtime();
            voiceLastSpeechAtMs = voiceRecordingStartedAtMs;
            voiceLastTranscriptAtMs = voiceRecordingStartedAtMs;
            voiceSpeechStarted = false;
            voiceAutoStopRequested = false;
            composerTranscript = "";
            realtimeAsrPartialCount = 0;
            realtimeAsrFinished = false;
            startRealtimeAsr();
            setChatStatus("语音识别中");
            transcriptDraftText.setText("结束提问");
            startVoiceRecordThread(recorder, file, bufferSize);
            voiceStopRunnable = new Runnable() {
                @Override
                public void run() {
                    finishToggleVoiceRecording("max_duration");
                }
            };
            mainHandler.postDelayed(voiceStopRunnable, autoWindowStart ? VOICE_AUTO_WAKE_RECORDING_MS : VOICE_RECORDING_MS);
            renderComposer();
        } catch (Exception error) {
            recordingVoice = false;
            setComposerStatus("录音失败：" + safeMessage(error));
        }
    }

    private void startRealtimeAsr() {
        voiceStreamState = VoiceStreamState.LISTENING;
        final long asrSessionId = voiceAsrSessionGate.begin();
        Log.i(KEY_LOG_TAG, "Realtime ASR start state=" + voiceStreamState);
        realtimeAsrClient.start(new RealtimeAsrCallback() {
            @Override
            public void onPartial(String text) {
                onAsrPartial(asrSessionId, text);
            }

            @Override
            public void onFinal(String text) {
                onAsrFinal(asrSessionId, text);
            }

            @Override
            public void onUnclear(String diagnosticCode) {
                onVoiceUnclear(asrSessionId, diagnosticCode);
            }

            @Override
            public void onError(Exception error) {
                onVoiceUnclear(asrSessionId, "asr_error:" + safeMessage(error));
            }
        });
    }

    private void finishToggleVoiceRecording(String stopReason) {
        stopVoiceRecording(true, stopReason);
    }

    private void feedRealtimeAsrPcm(byte[] buffer, int read) {
        if (!recordingVoice || realtimeAsrClient == null || read <= 0) {
            return;
        }
        realtimeAsrClient.acceptPcm(buffer, read);
    }

    private void updateVoiceSilenceAutoStop(byte[] buffer, int read) {
        if (!recordingVoice || voiceAutoStopRequested || buffer == null || read <= 1) {
            return;
        }
        // A wake window must stay open for its full duration. Ambient RMS spikes otherwise
        // make it stop before the user has started saying the wake phrase.
        if (voiceStartedFromAutoWindow) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int rms = pcm16Rms(buffer, read);
        if (rms >= VOICE_SILENCE_RMS_THRESHOLD) {
            voiceSpeechStarted = true;
            voiceLastSpeechAtMs = now;
            return;
        }
        if (!voiceSpeechStarted) {
            return;
        }
        if (now - voiceRecordingStartedAtMs < VOICE_AUTO_STOP_MIN_RECORDING_MS) {
            return;
        }
        if (now - voiceLastSpeechAtMs < VOICE_AUTO_STOP_SILENCE_MS) {
            return;
        }
        voiceAutoStopRequested = true;
        Log.i(KEY_LOG_TAG, "Voice auto stop by silence rms=" + rms
                + " silenceMs=" + (now - voiceLastSpeechAtMs));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordingVoice) {
                    finishToggleVoiceRecording("silence_auto_stop");
                }
            }
        });
    }

    private void scheduleTranscriptStableAutoStop(String transcript) {
        if (!recordingVoice || voiceAutoStopRequested) {
            return;
        }
        String cleaned = sanitizeTranscriptForDisplay(transcript);
        if (cleaned.length() == 0) {
            return;
        }
        voiceSpeechStarted = true;
        voiceLastTranscriptAtMs = SystemClock.elapsedRealtime();
        if (voiceTranscriptStableStopRunnable != null) {
            mainHandler.removeCallbacks(voiceTranscriptStableStopRunnable);
        }
        final long transcriptAtMs = voiceLastTranscriptAtMs;
        voiceTranscriptStableStopRunnable = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (!recordingVoice || voiceAutoStopRequested || realtimeAsrFinished) {
                    return;
                }
                if (voiceLastTranscriptAtMs != transcriptAtMs) {
                    return;
                }
                if (now - transcriptAtMs < VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS) {
                    return;
                }
                voiceAutoStopRequested = true;
                Log.i(KEY_LOG_TAG, "Voice auto stop by transcript stable stableMs=" + (now - transcriptAtMs));
                finishToggleVoiceRecording("transcript_stable_auto_stop");
            }
        };
        mainHandler.postDelayed(voiceTranscriptStableStopRunnable, VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS);
    }

    private int pcm16Rms(byte[] buffer, int read) {
        int samples = read / 2;
        if (samples <= 0) {
            return 0;
        }
        long sumSquares = 0L;
        for (int i = 0; i + 1 < read; i += 2) {
            int low = buffer[i] & 0xff;
            int high = buffer[i + 1];
            short sample = (short) ((high << 8) | low);
            sumSquares += (long) sample * (long) sample;
        }
        return (int) Math.sqrt(sumSquares / (double) samples);
    }

    private void finishRealtimeAsr(String stopReason) {
        if (realtimeAsrClient != null) {
            realtimeAsrClient.finish(stopReason);
        }
    }

    private void startVoiceRecordThread(final AudioRecord recorder, final File outputFile, final int bufferSize) {
        voiceRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[bufferSize];
                int pcmBytes = 0;
                RandomAccessFile output = null;
                try {
                    output = new RandomAccessFile(outputFile, "rw");
                    output.setLength(0);
                    writeWavHeader(output, 0);
                    while (recordingVoice && recorder == voiceRecorder) {
                        int read = recorder.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            output.write(buffer, 0, read);
                            pcmBytes += read;
                            feedRealtimeAsrPcm(buffer, read);
                            updateVoiceSilenceAutoStop(buffer, read);
                        }
                    }
                    output.seek(0);
                    writeWavHeader(output, pcmBytes);
                } catch (final Exception error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setComposerStatus("录音保存失败：" + safeMessage(error));
                        }
                    });
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, "DingdangVoiceRecorder");
        voiceRecordThread.start();
    }

    private void stopVoiceRecording(boolean transcribe, String stopReason) {
        if (!recordingVoice && voiceRecorder == null) {
            return;
        }
        if (voiceStopRunnable != null) {
            mainHandler.removeCallbacks(voiceStopRunnable);
            voiceStopRunnable = null;
        }
        if (voiceTranscriptStableStopRunnable != null) {
            mainHandler.removeCallbacks(voiceTranscriptStableStopRunnable);
            voiceTranscriptStableStopRunnable = null;
        }
        recordingVoice = false;
        voiceAutoStopRequested = false;
        AudioRecord recorder = voiceRecorder;
        voiceRecorder = null;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Exception ignored) {
        }
        if (recorder != null) {
            recorder.release();
        }
        if (transcribe) {
            finishRealtimeAsr(stopReason);
        } else if (realtimeAsrClient != null) {
            voiceAsrSessionGate.invalidate();
            realtimeAsrClient.cancel();
        }
        cleanupVoiceRecordThreadAsync();
        if (!transcribe || voiceFile == null || !voiceFile.exists() || voiceFile.length() <= VOICE_WAV_HEADER_BYTES) {
            voiceStreamState = VoiceStreamState.IDLE;
            setChatStatus("在线");
            renderComposer();
            return;
        }
        setChatStatus("已听清，正在整理");
        renderComposer();
    }

    private void stopVoiceCaptureAfterAsrFinal() {
        if (!recordingVoice && voiceRecorder == null) {
            return;
        }
        if (voiceStopRunnable != null) {
            mainHandler.removeCallbacks(voiceStopRunnable);
            voiceStopRunnable = null;
        }
        if (voiceTranscriptStableStopRunnable != null) {
            mainHandler.removeCallbacks(voiceTranscriptStableStopRunnable);
            voiceTranscriptStableStopRunnable = null;
        }
        recordingVoice = false;
        voiceAutoStopRequested = false;
        AudioRecord recorder = voiceRecorder;
        voiceRecorder = null;
        try {
            if (recorder != null) {
                recorder.stop();
            }
        } catch (Exception ignored) {
        }
        if (recorder != null) {
            recorder.release();
        }
        cleanupVoiceRecordThreadAsync();
    }

    private void onAsrPartial(final long asrSessionId, final String text) {
        final String partial = sanitizeTranscriptForDisplay(text);
        if (partial.length() == 0) {
            return;
        }
        realtimeAsrPartialCount++;
        Log.i(KEY_LOG_TAG, "Realtime ASR partial count=" + realtimeAsrPartialCount + " text=" + partial);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!voiceAsrSessionGate.accepts(asrSessionId)
                        || voiceStreamState == VoiceStreamState.FINAL_READY
                        || voiceStreamState == VoiceStreamState.AI_PENDING) {
                    return;
                }
                if (VOICE_PREVIEW_ENABLED && voiceSessionPurpose == VoiceSessionPurpose.WAKE
                        && isDingdangWakeOnly(partial) && !wakeFeedbackDelivered) {
                    wakeFeedbackDelivered = true;
                    setChatStatus("已唤醒，请说指令");
                    playWakeFeedbackTone();
                }
                voiceStreamState = VoiceStreamState.PARTIAL_READY;
                composerTranscript = partial;
                setChatStatus("正在听");
                updateLiveTranscriptDraft(partial);
                scheduleTranscriptStableAutoStop(partial);
                renderComposer();
            }
        });
    }

    private void onAsrFinal(final long asrSessionId, final String text) {
        final String finalText = sanitizeTranscriptForDisplay(text);
        Log.i(KEY_LOG_TAG, "Realtime ASR final text=" + finalText);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!voiceAsrSessionGate.accepts(asrSessionId) || realtimeAsrFinished) {
                    return;
                }
                realtimeAsrFinished = true;
                boolean autoWindowFinal = voiceStartedFromAutoWindow;
                if (finalText.length() == 0) {
                    realtimeAsrFinished = false;
                    onVoiceUnclear(asrSessionId, "empty_final_text");
                    return;
                }
                if (isIgnorableVoiceUtterance(finalText)) {
                    composerTranscript = "";
                    stopVoiceCaptureAfterAsrFinal();
                    voiceStreamState = VoiceStreamState.IDLE;
                    voiceStartedFromAutoWindow = false;
                    onVoiceUnclear(asrSessionId, "voice-filler-retry");
                    return;
                }
                if (autoWindowFinal && isDingdangWakeOnly(finalText)) {
                    voiceAsrSessionGate.invalidate();
                    composerTranscript = "";
                    stopVoiceCaptureAfterAsrFinal();
                    voiceStreamState = VoiceStreamState.IDLE;
                    voiceStartedFromAutoWindow = false;
                    clearLiveTranscriptMessageIfStreaming();
                    if (VOICE_PREVIEW_ENABLED) {
                        beginVoiceCommandAfterWake();
                    } else {
                        wakePrefixGraceUntilMs = SystemClock.elapsedRealtime() + WAKE_PREFIX_GRACE_MS;
                        voiceSessionPurpose = VoiceSessionPurpose.NONE;
                        scheduleForegroundVoiceListening("wake-prefix-grace");
                    }
                    return;
                }
                boolean missingWakePrefix = autoWindowFinal && !hasDingdangWakePrefix(finalText);
                boolean hasWakePrefix = !missingWakePrefix;
                boolean graceCommand = autoWindowFinal && !hasWakePrefix && hasWakePrefixGrace()
                        && !VOICE_PREVIEW_ENABLED && classifyVoiceCommand(finalText) != VoiceCommand.NONE;
                if (missingWakePrefix && !graceCommand) {
                    wakePrefixGraceUntilMs = 0L;
                    composerTranscript = "";
                    stopVoiceCaptureAfterAsrFinal();
                    voiceStreamState = VoiceStreamState.IDLE;
                    voiceStartedFromAutoWindow = false;
                    voiceSessionPurpose = VoiceSessionPurpose.NONE;
                    onVoiceUnclear(asrSessionId, "wake_prefix_required");
                    return;
                }
                voiceAsrSessionGate.invalidate();
                stopVoiceCaptureAfterAsrFinal();
                voiceStartedFromAutoWindow = false;
                voiceSessionPurpose = VoiceSessionPurpose.NONE;
                voiceStreamState = VoiceStreamState.FINAL_READY;
                String effectiveFinalText = hasWakePrefix ? stripDingdangWakePrefix(finalText) : finalText;
                if (graceCommand) {
                    wakePrefixGraceUntilMs = 0L;
                }
                composerTranscript = effectiveFinalText;
                if (VOICE_WORKFLOW_ENABLED && handleVoicePreviewInteraction(effectiveFinalText)) {
                    return;
                }
                if (handleVoiceCommand(effectiveFinalText)) {
                    return;
                }
                updateLiveTranscriptMessage(finalText, true);
                voiceStreamState = VoiceStreamState.AI_PENDING;
                setChatStatus("已听清，正在分析");
                renderComposer();
                sendComposerToAi();
            }
        });
    }

    private void updateLiveTranscriptDraft(String partial) {
        composerTranscript = sanitizeTranscriptForDisplay(partial);
    }

    private void onVoiceUnclear(final long asrSessionId, final String diagnosticCode) {
        final String code = diagnosticCode == null || diagnosticCode.trim().length() == 0
                ? "voice_unclear"
                : diagnosticCode.trim();
        Log.i(KEY_LOG_TAG, "Realtime ASR unclear code=" + code);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!voiceAsrSessionGate.accepts(asrSessionId)) {
                    return;
                }
                voiceAsrSessionGate.invalidate();
                if (VOICE_WORKFLOW_ENABLED
                        && voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION
                        && composerTranscript.trim().length() > 0) {
                    submitVoiceEvent(voiceEventStateMachine.onDescriptionFinal(composerTranscript));
                    return;
                }
                if (realtimeAsrFinished && composerTranscript.trim().length() > 0) {
                    if (!voiceStartedFromAutoWindow && shouldSendDraftOnAsrFinished(code)) {
                        stopVoiceCaptureAfterAsrFinal();
                        voiceStartedFromAutoWindow = false;
                        voiceSessionPurpose = VoiceSessionPurpose.NONE;
                        voiceStreamState = VoiceStreamState.AI_PENDING;
                        setChatStatus("已听清，正在分析");
                        renderComposer();
                        sendComposerToAi();
                    }
                    return;
                }
                stopVoiceCaptureAfterAsrFinal();
                voiceSessionPurpose = VoiceSessionPurpose.NONE;
                voiceStreamState = VoiceStreamState.VOICE_UNCLEAR;
                composerTranscript = voiceStatusForDiagnostic(code);
                clearLiveTranscriptMessageIfStreaming();
                setChatStatus("在线");
                renderComposer();
                voiceStreamState = VoiceStreamState.IDLE;
                if (shouldContinueForegroundWakeListening(code)) {
                    scheduleForegroundVoiceListening("wake-listen-retry");
                } else {
                    cancelForegroundVoiceListening();
                }
            }
        });
    }

    private void beginVoiceCommandAfterWake() {
        cancelForegroundVoiceListening();
        voiceSessionPurpose = VoiceSessionPurpose.COMMAND;
        wakeFeedbackDelivered = true;
        setChatStatus("已唤醒，请说指令");
        playWakeFeedbackTone();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isVoiceControlAvailableOnCurrentScreen() || recordingVoice
                        || voiceSessionPurpose != VoiceSessionPurpose.COMMAND) {
                    return;
                }
                startToggleVoiceRecording();
            }
        }, 180L);
    }

    private void playWakeFeedbackTone() {
        try {
            ToneGenerator tone = new ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tone.release();
                }
            }, 180L);
        } catch (Exception ignored) {
            // Visual wake feedback remains available when the device cannot play a tone.
        }
    }

    private boolean shouldSendDraftOnAsrFinished(String code) {
        String safeCode = code == null ? "" : code;
        String draft = composerTranscript == null ? "" : composerTranscript.trim();
        return draft.length() > 0
                && ("asr_task_finished".equals(safeCode)
                || "empty_final_text".equals(safeCode)
                || "voice_unclear".equals(safeCode));
    }

    private String voiceStatusForDiagnostic(String code) {
        String safeCode = code == null ? "" : code;
        if ("asr_endpoint_missing".equals(safeCode)) {
            return "语音服务未连接，请检查 ASR 配置";
        }
        if ("asr_realtime_unavailable".equals(safeCode) || safeCode.startsWith("asr_error:")) {
            return "语音服务未连接，请检查后端或网络";
        }
        if ("voice_too_short".equals(safeCode)) {
            return "说话时间太短，请再说一次";
        }
        return "没有听清，请再说一次";
    }

    private void cleanupVoiceRecordThreadAsync() {
        Thread thread = voiceRecordThread;
        voiceRecordThread = null;
        if (thread == null) {
            return;
        }
        voiceRecordCleanupThread = new Thread(new Runnable() {
            @Override
            public void run() {
                waitForVoiceRecordThread(thread);
            }
        }, "DingdangVoiceRecorderCleanup");
        voiceRecordCleanupThread.start();
    }

    private void waitForVoiceRecordThread(Thread thread) {
        try {
            thread.join(VOICE_RECORD_THREAD_JOIN_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeWavHeader(RandomAccessFile output, int pcmBytes) throws IOException {
        int byteRate = VOICE_SAMPLE_RATE_HZ * VOICE_WAV_CHANNEL_COUNT * VOICE_WAV_BITS_PER_SAMPLE / 8;
        int blockAlign = VOICE_WAV_CHANNEL_COUNT * VOICE_WAV_BITS_PER_SAMPLE / 8;
        output.writeBytes("RIFF");
        writeLittleEndianInt(output, 36 + pcmBytes);
        output.writeBytes("WAVE");
        output.writeBytes("fmt ");
        writeLittleEndianInt(output, 16);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, VOICE_WAV_CHANNEL_COUNT);
        writeLittleEndianInt(output, VOICE_SAMPLE_RATE_HZ);
        writeLittleEndianInt(output, byteRate);
        writeLittleEndianShort(output, blockAlign);
        writeLittleEndianShort(output, VOICE_WAV_BITS_PER_SAMPLE);
        output.writeBytes("data");
        writeLittleEndianInt(output, pcmBytes);
    }

    private void writeLittleEndianInt(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }

    private void writeLittleEndianShort(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private void startCameraFlow() {
        startCameraThread();
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                configurePreviewTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        if (previewView.isAvailable()) {
            openCamera();
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("DingdangCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join(800L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (cameraDevice != null || cameraHandler == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraId = chooseCameraId(manager);
            if (cameraId == null) {
                return;
            }
            previewSize = choosePreviewSize(manager, cameraId);
            captureSize = chooseCaptureSize(manager, cameraId);
            sensorOrientation = readSensorOrientation(manager, cameraId);
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    ImageFormat.JPEG,
                    2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    handleCapturedImage(reader);
                }
            }, cameraHandler);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (Exception error) {
            cameraStatusText.setText("相机启动失败：" + safeMessage(error));
        }
    }

    private String chooseCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length == 0 ? null : ids[0];
    }

    private Size choosePreviewSize(CameraManager manager, String id) throws CameraAccessException {
        StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size fallback = new Size(1280, 720);
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        return chooseBestPreviewSize(sizes, fallback);
    }

    private Size chooseCaptureSize(CameraManager manager, String id) throws CameraAccessException {
        StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size fallback = new Size(1280, 720);
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        return chooseBestCaptureSize(sizes, fallback);
    }

    private Size chooseBestPreviewSize(Size[] sizes, Size fallback) {
        Size exact1080p = findExactSize(sizes, 1920, 1080);
        if (exact1080p != null) {
            return exact1080p;
        }
        Size best16By9 = chooseLargestMatchingAspect(sizes, 1920, 1080, 16f / 9f);
        if (best16By9 != null) {
            return best16By9;
        }
        return chooseLargestUnder(sizes, 1920, 1080, fallback);
    }

    private Size chooseBestCaptureSize(Size[] sizes, Size fallback) {
        Size highQuality16By9 = chooseLargestMatchingAspect(sizes, 2560, 1440, 16f / 9f);
        if (highQuality16By9 != null) {
            return highQuality16By9;
        }
        return chooseLargestUnder(sizes, 2560, 1440, fallback);
    }

    private Size findExactSize(Size[] sizes, int width, int height) {
        if (sizes == null) {
            return null;
        }
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                return size;
            }
        }
        return null;
    }

    private Size chooseLargestMatchingAspect(Size[] sizes, int maxWidth, int maxHeight, float aspect) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() > maxWidth || size.getHeight() > maxHeight) {
                continue;
            }
            float candidateAspect = size.getWidth() / (float) size.getHeight();
            if (Math.abs(candidateAspect - aspect) > 0.025f) {
                continue;
            }
            if (best == null || size.getWidth() * size.getHeight() > best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best;
    }

    private Size chooseLargestUnder(Size[] sizes, int maxWidth, int maxHeight, Size fallback) {
        if (sizes == null || sizes.length == 0) {
            return fallback;
        }
        Arrays.sort(sizes, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Integer.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight());
            }
        });
        for (Size size : sizes) {
            if (size.getWidth() <= maxWidth && size.getHeight() <= maxHeight) {
                return size;
            }
        }
        return sizes[0];
    }

    private int readSensorOrientation(CameraManager manager, String id) throws CameraAccessException {
        Integer orientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
        return orientation == null ? 0 : orientation;
    }

    private void createPreviewSession() {
        if (cameraDevice == null || previewSize == null || !previewView.isAvailable()) {
            return;
        }
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                configurePreviewTransform(previewView.getWidth(), previewView.getHeight());
                                if (pendingVoicePhotoCapture) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            capturePendingVoicePhotoIfReady();
                                        }
                                    });
                                }
                            } catch (CameraAccessException ignored) {
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            cameraStatusText.setText("预览失败");
                        }
                    },
                    cameraHandler);
        } catch (Exception error) {
            cameraStatusText.setText("预览失败：" + safeMessage(error));
        }
    }

    private void configurePreviewTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean swapped = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        float bufferWidth = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float bufferHeight = swapped ? previewSize.getWidth() : previewSize.getHeight();
        if (bufferWidth <= 0f || bufferHeight <= 0f) {
            return;
        }
        float viewRatio = viewWidth / (float) viewHeight;
        float bufferRatio = bufferWidth / bufferHeight;
        // Center-crop with one uniform scale; independent X/Y scales distort the camera image.
        float uniformScale = Math.max(viewWidth / bufferWidth, viewHeight / bufferHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(uniformScale, uniformScale, viewWidth / 2f, viewHeight / 2f);
        previewView.setTransform(matrix);
        Log.i(KEY_LOG_TAG, "Camera preview transform view=" + viewWidth + "x" + viewHeight
                + " preview=" + previewSize.getWidth() + "x" + previewSize.getHeight()
                + " viewRatio=" + viewRatio + " bufferRatio=" + bufferRatio
                + " uniformScale=" + uniformScale);
    }

    private void captureStillImage() {
        if (captureInFlight || cameraDevice == null || captureSession == null || imageReader == null) {
            cameraStatusText.setText("相机还没准备好");
            return;
        }
        try {
            captureInFlight = true;
            cameraStatusText.setText("正在拍照");
            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            captureSession.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    cameraStatusText.setText("照片处理中");
                }
            }, cameraHandler);
        } catch (Exception error) {
            captureInFlight = false;
            cameraStatusText.setText("拍照失败：" + safeMessage(error));
        }
    }

    private int jpegOrientation() {
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (deviceRotation) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            case Surface.ROTATION_0:
            default:
                degrees = 0;
                break;
        }
        return (sensorOrientation + degrees + 360) % 360;
    }

    private void handleCapturedImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            byte[] bytes = new byte[image.getPlanes()[0].getBuffer().remaining()];
            image.getPlanes()[0].getBuffer().get(bytes);
            final byte[] compressed = compressJpeg(bytes);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    confirmCapturedPhoto(compressed);
                }
            });
        } catch (final Exception error) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cameraStatusText.setText("照片读取失败：" + safeMessage(error));
                }
            });
        } finally {
            if (image != null) {
                image.close();
            }
            captureInFlight = false;
        }
    }

    private byte[] compressJpeg(byte[] bytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            return bytes;
        }
        Bitmap oriented = applyExifOrientation(bitmap, bytes);
        if (oriented != bitmap) {
            bitmap.recycle();
        }
        // The Air3 camera can emit a portrait JPEG while its TextureView is landscape.
        // Crop to the live view's aspect ratio so the AI evidence matches what the wearer saw.
        Bitmap framed = cropBitmapToPreviewAspect(oriented);
        if (framed != oriented) {
            oriented.recycle();
        }
        Bitmap scaled = scaleBitmapToMaxEdge(framed, UPLOAD_MAX_IMAGE_EDGE);
        if (scaled != framed) {
            framed.recycle();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output);
            return output.toByteArray();
        } finally {
            scaled.recycle();
        }
    }

    private Bitmap cropBitmapToPreviewAspect(Bitmap bitmap) {
        if (bitmap == null || previewSize == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return bitmap;
        }
        float targetAspect = previewSize.getWidth() / (float) previewSize.getHeight();
        float sourceAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        if (Math.abs(sourceAspect - targetAspect) < 0.02f) {
            return bitmap;
        }
        int cropWidth = bitmap.getWidth();
        int cropHeight = bitmap.getHeight();
        if (sourceAspect > targetAspect) {
            cropWidth = Math.round(bitmap.getHeight() * targetAspect);
        } else {
            cropHeight = Math.round(bitmap.getWidth() / targetAspect);
        }
        cropWidth = Math.max(1, Math.min(cropWidth, bitmap.getWidth()));
        cropHeight = Math.max(1, Math.min(cropHeight, bitmap.getHeight()));
        int left = (bitmap.getWidth() - cropWidth) / 2;
        int top = (bitmap.getHeight() - cropHeight) / 2;
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight);
    }

    private Bitmap applyExifOrientation(Bitmap bitmap, byte[] jpegBytes) {
        int orientation = readExifOrientation(jpegBytes);
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return bitmap;
        }
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private int readExifOrientation(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpegBytes));
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
            }
        } catch (Exception ignored) {
        }
        captureSession = null;
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
        } catch (Exception ignored) {
        }
        cameraDevice = null;
        try {
            if (imageReader != null) {
                imageReader.close();
            }
        } catch (Exception ignored) {
        }
        imageReader = null;
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().length() == 0) {
            return error == null ? "unknown" : error.getClass().getSimpleName();
        }
        return message;
    }

    private static String sanitizeTranscriptForDisplay(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.length() == 0
                || "{}".equals(cleaned)
                || "[]".equals(cleaned)
                || "null".equalsIgnoreCase(cleaned)) {
            return "";
        }
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            String extracted = extractTranscriptFromJson(cleaned);
            if (extracted.length() > 0) {
                return extracted;
            }
            if (looksLikeAsrProtocolJson(cleaned)) {
                return "";
            }
        }
        return cleaned;
    }

    private static boolean looksLikeAsrProtocolJson(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (!(cleaned.startsWith("{") || cleaned.startsWith("["))) {
            return false;
        }
        try {
            if (cleaned.startsWith("{")) {
                return hasAsrProtocolKeys(new JSONObject(cleaned));
            }
            JSONArray array = new JSONArray(cleaned);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && hasAsrProtocolKeys(item)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean hasAsrProtocolKeys(JSONObject json) {
        if (json == null) {
            return false;
        }
        if (json.has("sentence_id") || json.has("channel_id") || json.has("sentence_end")
                || json.has("sentence_begin") || json.has("begin_time") || json.has("end_time")
                || json.has("speaker_id") || json.has("words")) {
            return true;
        }
        JSONObject payload = json.optJSONObject("payload");
        if (payload != null && hasAsrProtocolKeys(payload)) {
            return true;
        }
        JSONObject output = json.optJSONObject("output");
        if (output != null && hasAsrProtocolKeys(output)) {
            return true;
        }
        JSONObject sentence = json.optJSONObject("sentence");
        if (sentence != null && hasAsrProtocolKeys(sentence)) {
            return true;
        }
        JSONArray sentences = json.optJSONArray("sentences");
        if (sentences != null) {
            for (int i = 0; i < sentences.length(); i++) {
                JSONObject item = sentences.optJSONObject(i);
                if (item != null && hasAsrProtocolKeys(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String extractTranscriptFromJson(String rawJson) {
        try {
            String cleaned = rawJson == null ? "" : rawJson.trim();
            if (cleaned.startsWith("{")) {
                return extractTranscriptFromJsonObject(new JSONObject(cleaned));
            }
            if (cleaned.startsWith("[")) {
                JSONArray array = new JSONArray(cleaned);
                for (int i = array.length() - 1; i >= 0; i--) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) {
                        String text = extractTranscriptFromJsonObject(item);
                        if (text.length() > 0) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String extractTranscriptFromJsonObject(JSONObject json) {
        if (json == null) {
            return "";
        }
        JSONObject payload = json.optJSONObject("payload");
        if (payload != null) {
            String text = extractTranscriptFromJsonObject(payload);
            if (text.length() > 0) {
                return text;
            }
        }
        JSONObject output = json.optJSONObject("output");
        if (output != null) {
            String text = extractTranscriptFromJsonObject(output);
            if (text.length() > 0) {
                return text;
            }
        }
        JSONObject sentenceObject = json.optJSONObject("sentence");
        if (sentenceObject != null) {
            String text = extractTranscriptFromJsonObject(sentenceObject);
            if (text.length() > 0) {
                return text;
            }
        }
        JSONArray sentences = json.optJSONArray("sentences");
        if (sentences != null) {
            for (int i = sentences.length() - 1; i >= 0; i--) {
                JSONObject item = sentences.optJSONObject(i);
                if (item != null) {
                    String text = extractTranscriptFromJsonObject(item);
                    if (text.length() > 0) {
                        return text;
                    }
                }
            }
        }
        return cleanTranscriptLeaf(json.optString("text", json.optString("transcript", "")));
    }

    private static String cleanTranscriptLeaf(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.length() == 0
                || "{}".equals(cleaned)
                || "[]".equals(cleaned)
                || "null".equalsIgnoreCase(cleaned)
                || looksLikeAsrProtocolJson(cleaned)) {
            return "";
        }
        return cleaned;
    }

    private static final class ChatMessage {
        final String role;
        final String kind;
        String text;
        final String imageId;
        final String imagePreviewBase64;
        Bitmap imagePreviewBitmap;
        boolean streaming;

        ChatMessage(String role, String kind, String text, String imageId, boolean streaming) {
            this(role, kind, text, imageId, "", streaming);
        }

        ChatMessage(String role, String kind, String text, String imageId, String imagePreviewBase64, boolean streaming) {
            this.role = role;
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.imageId = imageId == null ? "" : imageId;
            this.imagePreviewBase64 = imagePreviewBase64 == null ? "" : imagePreviewBase64;
            this.streaming = streaming;
        }

        ChatMessage copy() {
            return new ChatMessage(role, kind, text, imageId, imagePreviewBase64, streaming);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("role", role);
                json.put("kind", kind);
                json.put("text", text);
                json.put("image_id", imageId);
                json.put("image_preview_base64", "");
                json.put("streaming", streaming);
            } catch (Exception ignored) {
            }
            return json;
        }

        static ChatMessage fromJson(JSONObject json) {
            return new ChatMessage(
                    json.optString("role", "assistant"),
                    json.optString("kind", "text"),
                    json.optString("text", ""),
                    json.optString("image_id", ""),
                    json.optString("image_preview_base64", ""),
                    json.optBoolean("streaming", false));
        }
    }

    private static final class ChatProject {
        final String id;
        String title;
        long updatedAt;
        String backendSessionId = "";
        final ArrayList<ChatMessage> messages = new ArrayList<ChatMessage>();

        ChatProject(String id, String title, long updatedAt) {
            this.id = id == null || id.length() == 0 ? "project-" + System.currentTimeMillis() : id;
            this.title = title == null || title.length() == 0 ? "现场诊断" : title;
            this.updatedAt = updatedAt;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            JSONArray messageArray = new JSONArray();
            for (int i = 0; i < messages.size(); i++) {
                messageArray.put(messages.get(i).toJson());
            }
            try {
                json.put("id", id);
                json.put("title", title);
                json.put("updated_at", updatedAt);
                json.put("backend_session_id", backendSessionId);
                json.put("messages", messageArray);
            } catch (Exception ignored) {
            }
            return json;
        }

        static ChatProject fromJson(JSONObject json) {
            ChatProject project = new ChatProject(
                    json.optString("id", "project-" + System.currentTimeMillis()),
                    json.optString("title", "现场诊断"),
                    json.optLong("updated_at", System.currentTimeMillis()));
            project.backendSessionId = json.optString("backend_session_id", "");
            JSONArray messageArray = json.optJSONArray("messages");
            if (messageArray != null) {
                for (int i = 0; i < messageArray.length(); i++) {
                    JSONObject messageJson = messageArray.optJSONObject(i);
                    if (messageJson != null) {
                        project.messages.add(ChatMessage.fromJson(messageJson));
                    }
                }
            }
            return project;
        }
    }

    private static final class DirectGptClient implements ChatAiClient {
        private final String baseUrl;
        private final String model;
        private final String reasoningEffort;
        private final String apiKey;

        DirectGptClient(String baseUrl, String model, String reasoningEffort, String apiKey) {
            this.baseUrl = trimSlash(baseUrl == null || baseUrl.length() == 0 ? "https://api.openai.com/v1" : baseUrl);
            this.model = model == null || model.length() == 0 ? "gpt-4.1-mini" : model;
            this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        @Override
        public void send(final String prompt, final String imageId, final byte[] jpegBytes, final StreamingCallback callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (apiKey.length() == 0) {
                        callback.onError(new IllegalStateException("DIRECT_GPT_API_KEY missing"));
                        return;
                    }
                    HttpURLConnection connection = null;
                    OutputStream output = null;
                    try {
                        JSONObject payload = buildChatPayload(prompt, jpegBytes);
                        long requestStartedAt = SystemClock.elapsedRealtime();
                        Log.i(KEY_LOG_TAG, "Direct GPT request start payloadChars=" + payload.toString().length()
                                + " imageBytes=" + (jpegBytes == null ? 0 : jpegBytes.length));
                        connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout((int) GPT_REQUEST_WATCHDOG_MS);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                        connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE);
                        output = connection.getOutputStream();
                        output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                        output.close();
                        output = null;
                        int status = connection.getResponseCode();
                        Log.i(KEY_LOG_TAG, "Direct GPT response status=" + status
                                + " latencyMs=" + (SystemClock.elapsedRealtime() - requestStartedAt));
                        InputStream stream = status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();
                        if (status < 200 || status >= 300) {
                            String body = readAll(stream);
                            throw new IOException("direct_gpt_http_" + status + ": " + body);
                        }
                        streamChatCompletions(stream, callback);
                        Log.i(KEY_LOG_TAG, "Direct GPT SSE done latencyMs="
                                + (SystemClock.elapsedRealtime() - requestStartedAt));
                        callback.onComplete();
                    } catch (Exception error) {
                        Log.w(KEY_LOG_TAG, "Direct GPT request failed " + safeMessage(error));
                        callback.onError(error);
                    } finally {
                        if (output != null) {
                            try {
                                output.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
            }, "DirectGptClient").start();
        }

        private static String chatCompletionsUrl(String baseUrl) {
            String base = trimSlash(baseUrl == null || baseUrl.length() == 0 ? "https://api.openai.com/v1" : baseUrl);
            if (base.endsWith("/chat/completions")) {
                return base;
            }
            if (base.endsWith("/v1")) {
                return base + "/chat/completions";
            }
            return base + "/v1/chat/completions";
        }

        private JSONObject buildChatPayload(String prompt, byte[] jpegBytes) throws Exception {
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            if (reasoningEffort.length() > 0) {
                payload.put("reasoning_effort", reasoningEffort);
            }
            payload.put("stream", true);
            payload.put("max_tokens", 600);
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "你是" + APP_LABEL + "，面向现场运维人员。只回答用户当前问题，使用简洁中文。需要说明多个要点时，用“1. 2. 3.”逐项列出；有几项已确认的结论就写几项，不设固定条数，绝不为了凑编号补充推测、假设或无关内容。问题只需要一个明确结论时，直接回答一句结论即可。不要使用固定的“风险判断、已观察到的依据、下一步操作、何时呼叫专家”模板，也不要无关地提及网站恢复、HTTP 502 或呼叫专家。只能根据用户文字和实际提供的图片作答；没有图片、图片模糊或证据不足时明确说明，不能编造现场观察。仅在涉及人身安全、带电、旋转、高温高压、泄漏、动火或无法确认的风险时，第一条要求停止操作、保持安全距离并按现场规程升级，不能给出绕过安全措施的步骤。当用户询问你是什么模型、由谁研发、哪家公司提供或底层模型信息时，只回答：我是华方智联研发的" + APP_LABEL + "模型，专注现场运维场景，可以结合眼镜拍摄的现场画面和语音问题，给出简洁、可执行的排查建议。不要透露底层模型名称、供应商或接口信息。若上下文中包含旧的固定格式建议，忽略其格式，仅按本指令回答当前问题。");
            messages.put(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", prompt);
            content.put(text);
            if (jpegBytes != null && jpegBytes.length > 0) {
                JSONObject image = new JSONObject();
                image.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP));
                image.put("image_url", imageUrl);
                content.put(image);
            }
            user.put("content", content);
            messages.put(user);
            payload.put("messages", messages);
            payload.put("temperature", 0.2);
            return payload;
        }

        private static void streamChatCompletions(InputStream stream, StreamingCallback callback) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || !trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                String delta = parseStreamingChatDelta(data);
                if (delta.length() > 0) {
                    callback.onDelta(delta);
                }
            }
        }

        private static String parseStreamingChatDelta(String data) throws Exception {
            JSONObject root = new JSONObject(data);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                return "";
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null) {
                Object content = delta.opt("content");
                return content instanceof String ? (String) content : "";
            }
            JSONObject message = choice.optJSONObject("message");
            if (message != null) {
                Object content = message.opt("content");
                return content instanceof String ? (String) content : "";
            }
            return "";
        }

        private static String parseChatText(String body) throws Exception {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return body;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                return body;
            }
            Object content = message.opt("content");
            if (content instanceof String) {
                return (String) content;
            }
            return String.valueOf(content);
        }
    }

    private static final class BackendGptClient implements ChatAiClient {
        interface SessionProvider {
            String sessionId();
        }

        private final BackendChatClient backendChatClient;
        private final SessionProvider sessionProvider;

        BackendGptClient(BackendChatClient backendChatClient, SessionProvider sessionProvider) {
            this.backendChatClient = backendChatClient;
            this.sessionProvider = sessionProvider;
        }

        @Override
        public void send(String prompt, String imageId, byte[] jpegBytes, StreamingCallback callback) {
            backendChatClient.sendDiagnosis(sessionProvider.sessionId(), imageId, prompt, callback);
        }
    }

    private static final class BackendChatClient implements ChatAiClient, RealtimeAsrClient {
        interface SessionProvider {
            String sessionId();
        }

        private final String baseUrl;
        private final String apiKey;
        private SessionProvider sessionProvider;
        private WebSocketRealtimeAsrSession websocketSession;

        BackendChatClient(String baseUrl, String apiKey) {
            this.baseUrl = trimSlash(baseUrl == null || baseUrl.length() == 0
                    ? "https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses"
                    : baseUrl);
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        BackendChatClient withSessionProvider(SessionProvider provider) {
            this.sessionProvider = provider;
            return this;
        }

        @Override
        public void send(String prompt, String imageId, byte[] jpegBytes, StreamingCallback callback) {
            String sessionId = sessionProvider == null ? "" : sessionProvider.sessionId();
            sendDiagnosis(sessionId, imageId, prompt, callback);
        }

        void uploadImageForChat(final String sessionId, final byte[] jpegBytes, final BackendImageUploadCallback callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection connection = null;
                    OutputStream output = null;
                    try {
                        if (baseUrl.length() == 0) {
                            throw new IllegalStateException("DINGDANG_BACKEND_BASE_URL missing");
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("image_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP));
                        payload.put("image_kind", "field_photo");
                        payload.put("client_ts", String.valueOf(System.currentTimeMillis()));
                        connection = openBackendConnection(backendImagesUrl(sessionId), "POST", "application/json; charset=utf-8");
                        output = connection.getOutputStream();
                        output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                        output.close();
                        output = null;
                        int status = connection.getResponseCode();
                        String body = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
                        if (status < 200 || status >= 300) {
                            throw new IOException("backend_image_http_" + status + ": " + body);
                        }
                        JSONObject json = new JSONObject(body);
                        String uploadedSessionId = json.optString("session_id", sessionId);
                        String imageId = json.optString("image_id", "");
                        if (imageId.length() == 0) {
                            throw new IOException("image_id missing");
                        }
                        callback.onUploaded(uploadedSessionId, imageId, json.optInt("image_bytes", jpegBytes.length));
                    } catch (Exception error) {
                        callback.onError(error);
                    } finally {
                        closeOutput(output);
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
            }, "BackendImageUpload").start();
        }

        void sendDiagnosis(final String sessionId, final String imageId, final String finalText, final StreamingCallback callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection connection = null;
                    OutputStream output = null;
                    try {
                        if (baseUrl.length() == 0) {
                            throw new IllegalStateException("DINGDANG_BACKEND_BASE_URL missing");
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("image_id", imageId);
                        payload.put("final_text", finalText);
                        payload.put("client_context", new JSONObject()
                                .put("source", "dingdang-android"));
                        connection = openBackendConnection(backendDiagnoseStreamUrl(sessionId), "POST", "application/json; charset=utf-8");
                        connection.setRequestProperty("Accept", "text/event-stream");
                        output = connection.getOutputStream();
                        output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                        output.close();
                        output = null;
                        int status = connection.getResponseCode();
                        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                        if (status < 200 || status >= 300) {
                            throw new IOException("backend_diagnose_http_" + status + ": " + readAll(stream));
                        }
                        readSseStream(stream, callback);
                    } catch (Exception error) {
                        callback.onError(error);
                    } finally {
                        closeOutput(output);
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
            }, "BackendDiagnoseStream").start();
        }

        @Override
        public synchronized void start(RealtimeAsrCallback callback) {
            String sessionId = sessionProvider == null ? "" : sessionProvider.sessionId();
            websocketSession = new WebSocketRealtimeAsrSession(backendAsrUrl(sessionId), apiKey, "fun-asr-realtime", new BackendRealtimeAsrCallback(callback), true);
            websocketSession.start();
        }

        @Override
        public synchronized void acceptPcm(byte[] pcm, int length) {
            if (websocketSession != null) {
                websocketSession.sendPcm(pcm, length);
            }
        }

        @Override
        public synchronized void finish(String stopReason) {
            if (websocketSession != null) {
                websocketSession.finish(stopReason);
                websocketSession = null;
            }
        }

        @Override
        public synchronized void cancel() {
            if (websocketSession != null) {
                websocketSession.cancel();
                websocketSession = null;
            }
        }

        private HttpURLConnection openBackendConnection(String url, String method, String contentType) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            if (apiKey.length() > 0) {
                connection.setRequestProperty("x-ops-glasses-key", apiKey);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            return connection;
        }

        private String backendImagesUrl(String sessionId) {
            return baseUrl + "/sessions/" + backendSessionPath(sessionId) + "/images";
        }

        private String backendImagesUrl() {
            return backendImagesUrl(sessionProvider == null ? "" : sessionProvider.sessionId());
        }

        private String backendAsrUrl(String sessionId) {
            return websocketBaseUrl(baseUrl) + "/sessions/" + backendSessionPath(sessionId) + "/asr";
        }

        private String backendAsrUrl() {
            return backendAsrUrl(sessionProvider == null ? "" : sessionProvider.sessionId());
        }

        private String backendDiagnoseStreamUrl(String sessionId) {
            return baseUrl + "/sessions/" + backendSessionPath(sessionId) + "/diagnose/stream";
        }

        private String backendDiagnoseStreamUrl() {
            return backendDiagnoseStreamUrl(sessionProvider == null ? "" : sessionProvider.sessionId());
        }

        private static String backendSessionPath(String sessionId) {
            String safe = sessionId == null ? "" : sessionId.trim();
            return safe.length() == 0 ? "_" : safe;
        }

        private static String websocketBaseUrl(String httpBaseUrl) {
            if (httpBaseUrl.startsWith("https://")) {
                return "wss://" + httpBaseUrl.substring("https://".length());
            }
            if (httpBaseUrl.startsWith("http://")) {
                return "ws://" + httpBaseUrl.substring("http://".length());
            }
            return httpBaseUrl;
        }

        private static void readSseStream(InputStream stream, StreamingCallback callback) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String event = "";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0) {
                    String delta = parseSseDelta(event, data.toString());
                    if (delta.length() > 0) {
                        callback.onDelta(delta);
                    }
                    if ("done".equals(event)) {
                        callback.onComplete();
                        return;
                    }
                    if ("error".equals(event)) {
                        throw new IOException(data.toString());
                    }
                    event = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            callback.onComplete();
        }

        private static String parseSseDelta(String event, String data) throws Exception {
            if (!"delta".equals(event) || data == null || data.trim().length() == 0) {
                return "";
            }
            JSONObject json = new JSONObject(data);
            return json.optString("text", "");
        }
    }

    private static final class BackendRealtimeAsrCallback implements RealtimeAsrCallback {
        private final RealtimeAsrCallback delegate;

        BackendRealtimeAsrCallback(RealtimeAsrCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPartial(String text) {
            BackendAsrEvent event = parseBackendAsrEvent("partial", text);
            if (delegate != null && event.text.length() > 0) {
                delegate.onPartial(event.text);
            }
        }

        @Override
        public void onFinal(String text) {
            BackendAsrEvent event = parseBackendAsrEvent("final", text);
            if (delegate == null) {
                return;
            }
            if (event.text.length() > 0) {
                delegate.onFinal(event.text);
            } else {
                delegate.onUnclear("asr_final_empty");
            }
        }

        @Override
        public void onUnclear(String diagnosticCode) {
            if (delegate != null) {
                delegate.onUnclear(diagnosticCode);
            }
        }

        @Override
        public void onError(Exception error) {
            if (delegate != null) {
                delegate.onError(error);
            }
        }

        private static BackendAsrEvent parseBackendAsrEvent(String fallbackType, String rawText) {
            if (rawText == null) {
                return new BackendAsrEvent(fallbackType, "");
            }
            try {
                JSONObject json = new JSONObject(rawText);
                String eventText = sanitizeTranscriptForDisplay(json.optString("text", json.optString("transcript", "")));
                if (eventText.length() == 0) {
                    eventText = extractTranscriptFromJson(rawText);
                }
                return new BackendAsrEvent(
                        json.optString("type", fallbackType),
                        eventText);
            } catch (Exception ignored) {
                return new BackendAsrEvent(fallbackType, sanitizeTranscriptForDisplay(rawText));
            }
        }
    }

    private static final class BackendAsrEvent {
        final String type;
        final String text;

        BackendAsrEvent(String type, String text) {
            this.type = type == null ? "" : type;
            this.text = text == null ? "" : text;
        }
    }

    private static final class DirectAsrClient implements RealtimeAsrClient {
        interface AsrCallback {
            void onText(String text);
            void onError(Exception error);
        }

        private final String endpoint;
        private final String apiKey;
        private final String model;
        private RealtimeAsrCallback realtimeCallback;
        private ByteArrayOutputStream pendingPcm;
        private WebSocketRealtimeAsrSession websocketSession;

        DirectAsrClient(String endpoint, String apiKey) {
            this(endpoint, apiKey, "fun-asr-realtime");
        }

        DirectAsrClient(String endpoint, String apiKey, String model) {
            this.endpoint = endpoint == null ? "" : endpoint;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null || model.length() == 0 ? "fun-asr-realtime" : model;
        }

        @Override
        public synchronized void start(RealtimeAsrCallback callback) {
            realtimeCallback = callback;
            pendingPcm = new ByteArrayOutputStream();
            if (endpoint.startsWith("wss://") || endpoint.startsWith("ws://")) {
                websocketSession = new WebSocketRealtimeAsrSession(endpoint, apiKey, model, callback);
                websocketSession.start();
            } else if (callback != null) {
                callback.onPartial("正在听，请继续说");
            }
        }

        @Override
        public synchronized void acceptPcm(byte[] pcm, int length) {
            if (pcm == null || length <= 0) {
                return;
            }
            if (websocketSession != null) {
                websocketSession.sendPcm(pcm, length);
                return;
            }
            if (pendingPcm != null) {
                pendingPcm.write(pcm, 0, length);
                int seconds = Math.max(1, pendingPcm.size() / (VOICE_SAMPLE_RATE_HZ * 2));
                if (realtimeCallback != null && pendingPcm.size() % (VOICE_SAMPLE_RATE_HZ * 2) < length) {
                    realtimeCallback.onPartial("正在听 " + seconds + " 秒");
                }
            }
        }

        @Override
        public synchronized void finish(String stopReason) {
            if (websocketSession != null) {
                websocketSession.finish(stopReason);
                websocketSession = null;
                pendingPcm = null;
                return;
            }
            int bytes = pendingPcm == null ? 0 : pendingPcm.size();
            pendingPcm = null;
            if (realtimeCallback == null) {
                return;
            }
            if (endpoint.length() == 0) {
                realtimeCallback.onUnclear("asr_endpoint_missing");
            } else if (bytes <= VOICE_WAV_HEADER_BYTES) {
                realtimeCallback.onUnclear("voice_too_short");
            } else {
                realtimeCallback.onUnclear("asr_realtime_unavailable");
            }
        }

        @Override
        public synchronized void cancel() {
            if (websocketSession != null) {
                websocketSession.cancel();
                websocketSession = null;
            }
            pendingPcm = null;
        }

        void transcribe(final File wavFile, final AsrCallback callback) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (endpoint.length() == 0) {
                        callback.onText("这个现场有什么问题，下一步怎么处理");
                        return;
                    }
                    HttpURLConnection connection = null;
                    OutputStream output = null;
                    try {
                        String boundary = "----dingdang-asr-" + System.currentTimeMillis();
                        connection = (HttpURLConnection) new URL(endpoint).openConnection();
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout(60000);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                        if (apiKey.length() > 0) {
                            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                        }
                        output = connection.getOutputStream();
                        writeMultipartFile(output, boundary, "file", "voice.wav", "audio/wav", wavFile);
                        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                        output.close();
                        output = null;
                        int status = connection.getResponseCode();
                        String body = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
                        if (status < 200 || status >= 300) {
                            throw new IOException("direct_asr_http_" + status + ": " + body);
                        }
                        JSONObject json = new JSONObject(body);
                        callback.onText(json.optString("text", json.optString("transcript", "")));
                    } catch (Exception error) {
                        callback.onError(error);
                    } finally {
                        if (output != null) {
                            try {
                                output.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
            }, "DirectAsrClient").start();
        }
    }

    private static final class WebSocketRealtimeAsrSession {
        private final String endpoint;
        private final String apiKey;
        private final String model;
        private final RealtimeAsrCallback callback;
        private final boolean backendMode;
        private final Object lock = new Object();
        private final ArrayList<byte[]> queuedChunks = new ArrayList<>();
        private Socket socket;
        private InputStream input;
        private OutputStream output;
        private Thread thread;
        private boolean connected;
        private boolean taskStarted;
        private boolean finishing;
        private boolean closed;
        private boolean finalDelivered;
        private String finalText = "";
        private String taskId = "";

        WebSocketRealtimeAsrSession(String endpoint, String apiKey, String model, RealtimeAsrCallback callback) {
            this(endpoint, apiKey, model, callback, false);
        }

        WebSocketRealtimeAsrSession(String endpoint, String apiKey, String model, RealtimeAsrCallback callback, boolean backendMode) {
            this.endpoint = endpoint;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null || model.length() == 0 ? "fun-asr-realtime" : model;
            this.callback = callback;
            this.backendMode = backendMode;
        }

        void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runSocket();
                }
            }, "FunAsrRealtimeWebSocket");
            thread.start();
        }

        void sendPcm(byte[] pcm, int length) {
            if (pcm == null || length <= 0) {
                return;
            }
            byte[] copy = Arrays.copyOf(pcm, length);
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (!connected || output == null || (!backendMode && !taskStarted)) {
                    queuedChunks.add(copy);
                    return;
                }
            }
            writeBinary(copy);
        }

        void finish(String stopReason) {
            synchronized (lock) {
                finishing = true;
            }
            if (connected) {
                if (backendMode) {
                    sendFinishAsync(true);
                } else if (taskStarted) {
                    sendFinishAsync(false);
                }
            }
        }

        void cancel() {
            closeQuietly();
        }

        private void runSocket() {
            try {
                URI uri = URI.create(endpoint);
                boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
                socket = secure
                        ? SSLSocketFactory.getDefault().createSocket(uri.getHost(), port)
                        : new Socket(uri.getHost(), port);
                input = new BufferedInputStream(socket.getInputStream());
                output = new BufferedOutputStream(socket.getOutputStream());
                writeHandshake(uri);
                String statusLine = readHttpLine(input);
                if (statusLine == null || !statusLine.contains("101")) {
                    throw new IOException("websocket_handshake_failed:" + statusLine);
                }
                String line;
                while ((line = readHttpLine(input)) != null && line.length() > 0) {
                    // Consume handshake headers.
                }
                synchronized (lock) {
                    connected = true;
                }
                if (backendMode) {
                    sendBackendStart();
                } else {
                    sendRunTask();
                }
                synchronized (lock) {
                    if (finishing) {
                        if (backendMode) {
                            flushQueuedChunks();
                            sendBackendFinish();
                        }
                    }
                }
                readFrames();
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            } finally {
                closeQuietly();
            }
        }

        private void writeHandshake(URI uri) throws IOException {
            String path = uri.getRawPath() == null || uri.getRawPath().length() == 0 ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null && uri.getRawQuery().length() > 0) {
                path += "?" + uri.getRawQuery();
            }
            String key = Base64.encodeToString(randomBytes(16), Base64.NO_WRAP);
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(uri.getHost()).append("\r\n");
            request.append("Upgrade: websocket\r\n");
            request.append("Connection: Upgrade\r\n");
            request.append("Sec-WebSocket-Version: 13\r\n");
            request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            if (apiKey.length() > 0) {
                request.append("Authorization: Bearer ").append(apiKey).append("\r\n");
            }
            request.append("\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private void sendRunTask() {
            taskId = "dingdang-" + UUID.randomUUID();
            JSONObject header = new JSONObject();
            JSONObject payload = new JSONObject();
            JSONObject parameters = new JSONObject();
            try {
                header.put("action", "run-task");
                header.put("task_id", taskId);
                header.put("streaming", "duplex");
                payload.put("task_group", "audio");
                payload.put("task", "asr");
                payload.put("function", "recognition");
                payload.put("model", model);
                parameters.put("format", "pcm");
                parameters.put("sample_rate", VOICE_SAMPLE_RATE_HZ);
                payload.put("parameters", parameters);
                payload.put("input", new JSONObject());
                JSONObject event = new JSONObject();
                event.put("header", header);
                event.put("payload", payload);
                writeText(event.toString());
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private void sendBackendStart() {
            try {
                JSONObject event = new JSONObject();
                event.put("type", "start");
                event.put("sample_rate", VOICE_SAMPLE_RATE_HZ);
                event.put("format", "pcm");
                writeText(event.toString());
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private void sendFinishTask() {
            JSONObject header = new JSONObject();
            try {
                header.put("action", "finish-task");
                header.put("task_id", taskId.length() == 0 ? "dingdang-finish" : taskId);
                header.put("streaming", "duplex");
                JSONObject event = new JSONObject();
                event.put("header", header);
                JSONObject payload = new JSONObject();
                payload.put("input", new JSONObject());
                event.put("payload", payload);
                writeText(event.toString());
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private void sendFinishAsync(final boolean backendFinish) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (backendFinish) {
                        sendBackendFinish();
                    } else {
                        sendFinishTask();
                    }
                }
            }, "FunAsrFinishSender").start();
        }

        private void sendBackendFinish() {
            try {
                JSONObject event = new JSONObject();
                event.put("type", "finish");
                writeText(event.toString());
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private void flushQueuedChunks() {
            ArrayList<byte[]> chunks;
            synchronized (lock) {
                chunks = new ArrayList<>(queuedChunks);
                queuedChunks.clear();
            }
            for (int i = 0; i < chunks.size(); i++) {
                writeBinary(chunks.get(i));
            }
        }

        private void readFrames() throws IOException {
            while (!closed && input != null) {
                WebSocketFrame frame = readFrame(input);
                if (frame == null) {
                    return;
                }
                if (frame.opcode == 0x8) {
                    return;
                }
                if (frame.opcode == 0x1) {
                    handleTextFrame(new String(frame.payload, StandardCharsets.UTF_8));
                }
                if (!backendMode && finishing && finalText.length() > 0 && !finalDelivered) {
                    finalDelivered = true;
                    if (callback != null) {
                        callback.onFinal(finalText);
                    }
                    return;
                }
            }
        }

        private void handleTextFrame(String text) {
            try {
                if (backendMode) {
                    handleBackendTextFrame(text);
                    return;
                }
                JSONObject event = new JSONObject(text);
                JSONObject header = event.optJSONObject("header");
                JSONObject payload = event.optJSONObject("payload");
                String eventName = header == null ? "" : header.optString("event", header.optString("name", ""));
                String candidate = extractAsrText(payload);
                if (candidate.length() == 0) {
                    candidate = extractTranscriptFromJson(text);
                }
                if ("task-started".equals(eventName)) {
                    synchronized (lock) {
                        taskStarted = true;
                    }
                    flushQueuedChunks();
                    synchronized (lock) {
                        if (finishing) {
                            sendFinishTask();
                        }
                    }
                    return;
                }
                if (candidate.length() > 0) {
                    finalText = candidate;
                    if (callback != null) {
                        callback.onPartial(candidate);
                    }
                }
                if ("task-finished".equals(eventName) || "task-failed".equals(eventName)) {
                    if (callback != null) {
                        if (finalText.length() > 0 && !finalDelivered) {
                            finalDelivered = true;
                            callback.onFinal(finalText);
                        } else if (finalText.length() == 0) {
                            callback.onUnclear("asr_" + eventName.replace('-', '_'));
                        }
                    }
                    closeQuietly();
                }
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private void handleBackendTextFrame(String text) {
            try {
                JSONObject event = new JSONObject(text);
                String type = event.optString("type", "");
                String candidate = sanitizeTranscriptForDisplay(event.optString("text", event.optString("transcript", "")));
                if (candidate.length() == 0) {
                    candidate = extractTranscriptFromJson(text);
                }
                if ("ready".equals(type)) {
                    return;
                }
                if ("partial".equals(type)) {
                    if (candidate.length() > 0 && callback != null) {
                        callback.onPartial(candidate);
                    }
                    return;
                }
                if ("final".equals(type)) {
                    if (callback != null) {
                        if (candidate.length() > 0) {
                            callback.onFinal(candidate);
                        } else {
                            callback.onUnclear("asr_final_empty");
                        }
                    }
                    closeQuietly();
                    return;
                }
                if ("error".equals(type)) {
                    if (callback != null) {
                        callback.onError(new IOException(event.optString("code", "asr_error") + ":" + event.optString("message", "")));
                    }
                    closeQuietly();
                }
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }

        private static String extractAsrText(JSONObject payload) {
            if (payload == null) {
                return "";
            }
            JSONObject output = payload.optJSONObject("output");
            if (output != null) {
                JSONObject sentenceObject = output.optJSONObject("sentence");
                if (sentenceObject != null) {
                    String sentenceText = cleanAsrText(sentenceObject.optString("text", ""));
                    if (sentenceText.length() > 0) {
                        return sentenceText;
                    }
                }
                String sentence = cleanAsrText(output.optString("sentence", ""));
                if (sentence.length() > 0) {
                    return sentence;
                }
                String outputText = cleanAsrText(output.optString("text", output.optString("transcription", "")));
                if (outputText.length() > 0) {
                    return outputText;
                }
            }
            JSONArray sentences = payload.optJSONArray("sentences");
            if (sentences != null && sentences.length() > 0) {
                JSONObject last = sentences.optJSONObject(sentences.length() - 1);
                if (last != null) {
                    return cleanAsrText(last.optString("text", last.optString("sentence", "")));
                }
            }
            return cleanAsrText(payload.optString("text", payload.optString("transcript", "")));
        }

        private static String cleanAsrText(String text) {
            return sanitizeTranscriptForDisplay(text);
        }

        private void writeText(String text) {
            writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        private void writeBinary(byte[] bytes) {
            writeFrame(0x2, bytes);
        }

        private void writeFrame(int opcode, byte[] payload) {
            OutputStream out;
            synchronized (lock) {
                out = output;
                if (closed || out == null) {
                    return;
                }
            }
            try {
                int length = payload == null ? 0 : payload.length;
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x80 | opcode);
                byte[] mask = randomBytes(4);
                if (length <= 125) {
                    frame.write(0x80 | length);
                } else if (length <= 65535) {
                    frame.write(0x80 | 126);
                    frame.write((length >> 8) & 0xff);
                    frame.write(length & 0xff);
                } else {
                    frame.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) {
                        frame.write((length >> (8 * i)) & 0xff);
                    }
                }
                frame.write(mask);
                for (int i = 0; i < length; i++) {
                    frame.write(payload[i] ^ mask[i % 4]);
                }
                out.write(frame.toByteArray());
                out.flush();
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
                closeQuietly();
            }
        }

        private static WebSocketFrame readFrame(InputStream input) throws IOException {
            int first = input.read();
            if (first < 0) {
                return null;
            }
            int second = input.read();
            if (second < 0) {
                return null;
            }
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = (input.read() << 8) | input.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | input.read();
                }
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(input, mask);
            }
            if (length > 4 * 1024 * 1024) {
                throw new IOException("websocket_frame_too_large");
            }
            byte[] payload = new byte[(int) length];
            readFully(input, payload);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            return new WebSocketFrame(opcode, payload);
        }

        private static void readFully(InputStream input, byte[] target) throws IOException {
            int offset = 0;
            while (offset < target.length) {
                int read = input.read(target, offset, target.length - offset);
                if (read < 0) {
                    throw new IOException("unexpected_websocket_eof");
                }
                offset += read;
            }
        }

        private static String readHttpLine(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) >= 0) {
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = output.toByteArray();
                    int length = Math.max(0, bytes.length - 1);
                    return new String(bytes, 0, length, StandardCharsets.UTF_8);
                }
                output.write(current);
                previous = current;
            }
            return null;
        }

        private static byte[] randomBytes(int count) {
            byte[] bytes = new byte[count];
            new SecureRandom().nextBytes(bytes);
            return bytes;
        }

        private void closeQuietly() {
            synchronized (lock) {
                closed = true;
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static final class WebSocketFrame {
        final int opcode;
        final byte[] payload;

        WebSocketFrame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    private static final class AudioWaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;
        private float phase;

        AudioWaveView(Activity activity) {
            super(activity);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void start() {
            running = true;
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float centerY = height / 2f;
            float barWidth = Math.max(6f, width / 52f);
            float gap = barWidth * 0.85f;
            paint.setStrokeWidth(barWidth);
            int count = Math.max(12, (int) (width / (barWidth + gap)));
            for (int i = 0; i < count; i++) {
                float t = i / (float) Math.max(1, count - 1);
                float envelope = (float) Math.sin(Math.PI * t);
                float wave = running
                        ? (float) (0.45f + 0.55f * Math.abs(Math.sin(phase + i * 0.72f)))
                        : 0.28f;
                float barHeight = Math.max(8f, height * (0.14f + 0.62f * envelope * wave));
                int green = 180 + Math.round(54 * envelope);
                int blue = 130 + Math.round(90 * (1f - envelope));
                paint.setColor(Color.rgb(32, green, blue));
                float x = (width - (count - 1) * (barWidth + gap)) / 2f + i * (barWidth + gap);
                canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint);
            }
            if (running) {
                phase += 0.28f;
                postInvalidateDelayed(160L);
            }
        }
    }

    private static void streamText(String text, StreamingCallback callback) throws InterruptedException {
        String safe = text == null || text.length() == 0 ? "我没有拿到有效回答，请重试。" : text;
        int index = 0;
        while (index < safe.length()) {
            int next = Math.min(safe.length(), index + 24);
            callback.onDelta(safe.substring(index, next));
            index = next;
            Thread.sleep(35L);
        }
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeOutput(OutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    private static void writeMultipartFile(
            OutputStream output,
            String boundary,
            String field,
            String fileName,
            String contentType,
            File file) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
