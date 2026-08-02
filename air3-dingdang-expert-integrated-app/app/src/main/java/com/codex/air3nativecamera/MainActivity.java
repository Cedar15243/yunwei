package com.codex.air3nativecamera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.RestrictionsManager;
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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.WebView;
import android.provider.Settings;

import com.codex.air3nativecamera.features.FeatureEntry;
import com.codex.air3nativecamera.features.FeatureRegistry;
import com.codex.air3nativecamera.features.AIAgentConfig;
import com.codex.air3nativecamera.features.AIAbilityConfig;
import com.codex.air3nativecamera.features.AISkillConfig;
import com.codex.air3nativecamera.features.operations.InspectionChecklist;
import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.features.operations.OperationDetailFactory;
import com.codex.air3nativecamera.features.inspection.InspectionAiBridge;
import com.codex.air3nativecamera.features.inspection.InspectionCatalog;
import com.codex.air3nativecamera.features.inspection.InspectionRun;
import com.codex.air3nativecamera.features.inspection.InspectionTaskDefinition;
import com.codex.air3nativecamera.mode.IntegratedModeController;
import com.codex.air3nativecamera.runtime.ManagedRuntimeConfiguration;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;
import com.codex.air3nativecamera.sync.BackendAuthorization;
import com.codex.air3nativecamera.sync.AndroidNetworkAvailabilityMonitor;
import com.codex.air3nativecamera.sync.AndroidWorkflowPackageStoreProvider;
import com.codex.air3nativecamera.sync.DeviceAccessTokenProvider;
import com.codex.air3nativecamera.sync.DeviceSessionManager;
import com.codex.air3nativecamera.sync.DeviceSyncConfiguration;
import com.codex.air3nativecamera.sync.HttpDeviceSessionIssuer;
import com.codex.air3nativecamera.sync.HttpTaskSyncTransport;
import com.codex.air3nativecamera.sync.TaskSyncClient;
import com.codex.air3nativecamera.sync.TaskSyncEventFactory;
import com.codex.air3nativecamera.sync.TaskSyncReporter;
import com.codex.air3nativecamera.sync.VerifiedWorkflowPackageCache;
import com.codex.air3nativecamera.sync.WorkflowAssignmentRepository;
import com.codex.air3nativecamera.sync.WorkflowAssignmentSyncCoordinator;
import com.codex.air3nativecamera.sync.WorkflowDeliveryController;
import com.codex.air3nativecamera.sync.WorkflowDeviceHttpClient;
import com.codex.air3nativecamera.sync.WorkflowEvidenceUploadCoordinator;
import com.codex.air3nativecamera.sync.WorkflowPackageSnapshotAccess;
import com.codex.air3nativecamera.sync.WorkflowSyncTriggerCoordinator;
import com.codex.air3nativecamera.skills.HoneywellTempHumiditySkill;
import com.codex.air3nativecamera.skills.SceneReferenceGuide;
import com.codex.air3nativecamera.skills.SceneSkillAiBridge;
import com.codex.air3nativecamera.ui.hud.HudWebPresentation;
import com.codex.air3nativecamera.voice.LegacyVoiceCommandRouter;
import com.codex.air3nativecamera.voice.AsrProviderFailure;
import com.codex.air3nativecamera.voice.LocalAsrEngine;
import com.codex.air3nativecamera.voice.LocalAsrEngineFactory;
import com.codex.air3nativecamera.voice.VoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceAsrSessionGate;
import com.codex.air3nativecamera.voice.VoiceEventStateMachine;
import com.codex.air3nativecamera.voice.WakeListeningSchedulePolicy;
import com.codex.air3nativecamera.voice.WakeWordEngine;
import com.codex.air3nativecamera.voice.WakeWordEngines;
import com.codex.air3nativecamera.workflow.AndroidAtomicWorkflowSnapshotStorage;
import com.codex.air3nativecamera.workflow.ManagedWorkflowPublicKeySource;
import com.codex.air3nativecamera.workflow.WorkflowCapabilityRegistry;
import com.codex.air3nativecamera.workflow.WorkflowEvidenceReference;
import com.codex.air3nativecamera.workflow.WorkflowExecutionCoordinator;
import com.codex.air3nativecamera.workflow.WorkflowHudPresenter;
import com.codex.air3nativecamera.workflow.WorkflowPackage;
import com.codex.air3nativecamera.workflow.WorkflowPackageVerifier;
import com.codex.air3nativecamera.workflow.WorkflowRuntimeState;
import com.codex.air3nativecamera.workflow.WorkflowSnapshot;
import com.codex.air3nativecamera.workflow.WorkflowStepContext;
import com.codex.air3nativecamera.workflow.WorkflowVideoCapturePlan;
import com.codex.expertcollab.ExpertCollabCoordinator;
import com.codex.expertcollab.CollabServiceHealth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
    private enum VoiceSessionPurpose { NONE, WAKE, COMMAND, OFFLINE_WAKE_COMMAND }
    private enum HudTaskProgress { NONE, GUIDANCE, COMPLETED }

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
    private static final String WORKFLOW_TRUSTED_PUBLIC_KEYS = "workflow_trusted_public_keys";
    private static final boolean SECURE_RUNTIME = GeneratedConfig.SECURE_RUNTIME;
    private static final String APP_ID = GeneratedConfig.APP_ID;
    private static final String APP_LABEL = GeneratedConfig.APP_LABEL;
    private static final boolean VOICE_PREVIEW_ENABLED = isVoicePreviewPackage(APP_ID);
    private static final boolean OFFLINE_WAKE_ENABLED = GeneratedConfig.IFLYTEK_OFFLINE_WAKE_ENABLED;
    private static final boolean VOICE_WORKFLOW_ENABLED = VOICE_PREVIEW_ENABLED || OFFLINE_WAKE_ENABLED;
    private static final String DIRECT_ASR_MODEL = "fun-asr-realtime";
    private static final int JPEG_QUALITY = GeneratedConfig.FAST_UPLOAD ? 86 : 95;
    private static final int UPLOAD_MAX_IMAGE_EDGE = GeneratedConfig.FAST_UPLOAD ? 1280 : 1600;
    private static final int CAMERA_MIN_STABLE_FRAMES = 3;
    private static final long CAMERA_MIN_STABLE_DURATION_MS = 450L;
    private static final int PREVIEW_MAX_IMAGE_EDGE = 480;
    private static final long VOICE_RECORDING_MS = 30000L;
    private static final long SCENE_VIDEO_MAX_DURATION_MS = 15000L;
    private static final long MEDIA_WAKE_REARM_COOLDOWN_MS = 4000L;
    private static final int SCENE_VIDEO_BIT_RATE = 2_000_000;
    private static final int SCENE_VIDEO_FRAME_RATE = 30;
    private static final long VOICE_AUTO_WAKE_RECORDING_MS = 10000L;
    private static final long VOICE_AUTO_STOP_MIN_RECORDING_MS = 1800L;
    private static final long VOICE_AUTO_STOP_SILENCE_MS = 1500L;
    private static final long VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS = 1800L;
    private static final long VOICE_AUTO_STOP_COMMAND_STABLE_MS = 400L;
    private static final long VOICE_RECORD_THREAD_JOIN_MS = 700L;
    private static final long VOICE_ASR_FINISH_TIMEOUT_MS = 8000L;
    private static final int HUD_DIAGNOSIS_PAGE_SIZE = 130;
    private static final int HUD_CONVERSATION_PAGE_SIZE = 112;
    private static final int HUD_OPERATION_PAGE_SIZE = 4;
    private static final long CHAT_STREAM_RENDER_INTERVAL_MS = 260L;
    private static final long GPT_REQUEST_WATCHDOG_MS = 240000L;
    private static final long GPT_STREAM_TERMINAL_TIMEOUT_MS = 90000L;
    private static final long WAKE_PREFIX_GRACE_MS = 4000L;
    private static final long FOREGROUND_WAKE_RETRY_DELAY_MS = 900L;
    private static final long VOICE_EVENT_DESCRIPTION_TIMEOUT_MS = 30000L;
    private static final int MAX_VOICE_COMMAND_CHARS = 16;
    private static final int VOICE_SILENCE_RMS_THRESHOLD = 520;
    private static final int VOICE_SAMPLE_RATE_HZ = 16000;
    private static final int VOICE_WAV_CHANNEL_COUNT = 1;
    private static final int VOICE_WAV_BITS_PER_SAMPLE = 16;
    private static final int VOICE_WAV_HEADER_BYTES = 44;
    private static final String CHAT_PROJECT_PREFS = "dingdang_chat_projects";
    private static final String AGENT_AUTHORIZATIONS_JSON = "agent_authorizations_json";
    private static final String CHAT_PROJECTS_JSON = "projects_json";
    private static final String CURRENT_PROJECT_INDEX = "current_project_index";
    private static final String TASK_SESSIONS_JSON = "task_sessions_json";
    private static final String INSPECTION_RUN_JSON = "inspection_run_json";
    private static final String HOME_WELCOME_MESSAGE = "说“小叮当，拍照”记录现场，再直接说明问题。我会结合画面和语音给出排查建议。";
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
    private final List<AIAbilityConfig> aiAbilityConfigs = AIAbilityConfig.defaultConfigs();
    private final List<AISkillConfig> aiSkillConfigs = AISkillConfig.defaultConfigs();
    private final List<AIAgentConfig> aiAgentConfigs = AIAgentConfig.defaultConfigs();
    private final InspectionChecklist inspectionChecklist = InspectionChecklist.defaultChecklist();
    private final InspectionCatalog inspectionCatalog = InspectionCatalog.defaultCatalog();
    private InspectionRun activeInspectionRun;
    private boolean inspectionCapturePending;
    private boolean inspectionAiInFlight;
    private long inspectionRequestGeneration;
    private final OperationDetailFactory operationDetailFactory = OperationDetailFactory.defaultFactory();
    private final LegacyVoiceCommandRouter legacyVoiceCommandRouter = new LegacyVoiceCommandRouter();
    private final VoiceCommandRouter voiceCommandRouter = new VoiceCommandRouter();
    private final VoiceEventStateMachine voiceEventStateMachine = new VoiceEventStateMachine();
    private final VoiceAsrSessionGate voiceAsrSessionGate = new VoiceAsrSessionGate();

    private ScreenMode screenMode = ScreenMode.CHAT;
    private FrameLayout root;
    private FrameLayout expertLayer;
    private FrameLayout commandOverlay;
    private FrameLayout capabilityLayer;
    private FrameLayout hudLayer;
    private HudWebPresentation hudPresentation;
    private LinearLayout capabilityContent;
    private boolean capabilityDetailVisible;
    private boolean hudCapabilityVisible;
    private boolean hudVoiceGuideVisible;
    private boolean hudGlassesGuideVisible;
    private String hudOperationAbilityId = "";
    private int hudOperationPageIndex;
    private OperationDetail hudOperationDetail;
    // Persisted messages are records, not an instruction to reopen a task on the next launch.
    private boolean hudTaskWorkspaceActive;
    private boolean requireNewTaskOnNextInput = true;
    private int hudTaskMessageStartIndex;
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
    private volatile long cameraDeviceGeneration;
    private volatile long cameraSessionGeneration;
    private Size previewSize;
    private Size captureSize;
    private Size videoSize;
    private String cameraId;
    private int sensorOrientation;
    private int cameraLensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private boolean captureInFlight;
    private boolean cameraPreviewTransformReady;
    private boolean cameraPreviewStable;
    private int cameraPreviewFrameCount;
    private long cameraPreviewTransformReadyAtMs;
    private MediaRecorder sceneVideoRecorder;
    private File sceneVideoFile;
    private boolean sceneVideoRecording;
    private boolean sceneVideoStarting;
    private boolean pendingSceneVideoCapture;
    private String sceneVideoReturnSurface = "standby";
    private Runnable sceneVideoStopRunnable;
    private long sceneVideoStartedAtMs;

    private AudioRecord voiceRecorder;
    private Thread voiceRecordThread;
    private Thread voiceRecordCleanupThread;
    private File voiceFile;
    private boolean recordingVoice;
    private Runnable voiceStopRunnable;
    private VoiceStreamState voiceStreamState = VoiceStreamState.IDLE;
    private HudTaskProgress hudTaskProgress = HudTaskProgress.NONE;
    private int hudGuidanceStep = 1;
    private TaskSessionManager taskSessionManager = new TaskSessionManager();
    private TaskSyncClient taskSyncClient;
    private TaskSyncReporter taskSyncReporter;
    private DeviceSyncConfiguration deviceSyncConfiguration;
    private DeviceSessionManager deviceSessionManager;
    private WorkflowDeliveryController workflowDeliveryController;
    private WorkflowAssignmentRepository workflowAssignmentRepository;
    private AndroidWorkflowPackageStoreProvider workflowPackageStoreProvider;
    private WorkflowPackageSnapshotAccess workflowSnapshotAccess;
    private WorkflowDeviceHttpClient workflowDeviceHttpClient;
    private WorkflowCapabilityRegistry workflowCapabilityRegistry;
    private WorkflowExecutionCoordinator workflowExecutionCoordinator;
    private WorkflowEvidenceUploadCoordinator workflowEvidenceUploadCoordinator;
    private final WorkflowHudPresenter workflowHudPresenter = new WorkflowHudPresenter();
    private String activeWorkflowAssignmentId = "";
    private boolean workflowCapturePending;
    private WorkflowCapabilityRegistry.Callback workflowPhotoCallback;
    private WorkflowVideoCapturePlan workflowVideoCapturePlan;
    private WorkflowCapabilityRegistry.Callback workflowVideoCallback;
    private Bundle managedRestrictions;
    private ManagedRuntimeConfiguration runtimeConfiguration;
    private final HoneywellTempHumiditySkill honeywellTempHumiditySkill = new HoneywellTempHumiditySkill();
    private MaintenanceTask.Snapshot taskSnapshotBeforeExpert;
    private String taskIdBeforeExpert = "";
    private RealtimeAsrClient realtimeAsrClient;
    private int realtimeAsrPartialCount;
    private boolean realtimeAsrFinished;
    private long voiceRecordingStartedAtMs;
    private long voiceLastSpeechAtMs;
    private long voiceLastTranscriptAtMs;
    private boolean voiceSpeechStarted;
    private boolean voiceAutoStopRequested;
    private Runnable voiceTranscriptStableStopRunnable;
    private Runnable voiceAsrFinishTimeoutRunnable;
    private long activeVoiceAsrSessionId;
    private boolean voiceAutoListenArmed;
    private boolean voiceStartedFromAutoWindow;
    private VoiceSessionPurpose voiceSessionPurpose = VoiceSessionPurpose.NONE;
    private boolean wakeFeedbackDelivered;
    private long wakePrefixGraceUntilMs;
    private long offlineWakeSuppressedUntilMs;
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
    private String recoverableAiError = "";
    private int liveTranscriptMessageIndex = -1;
    private Runnable chatStreamRenderRunnable;
    private Runnable foregroundAutoVoiceStartRunnable;
    private Runnable voiceEventDescriptionTimeoutRunnable;
    private long lastChatStreamRenderAtMs;
    private boolean scrollChatToBottom;
    private int chatScrollRequestId;
    private long gptStreamStartedAtMs = 0L;
    private boolean gptFirstDeltaLogged = false;
    private long voiceInteractionStartedAtMs;
    private long currentAsrStartedAtMs;
    private boolean currentAsrFirstPartialLogged;
    private int gptRequestGeneration = 0;
    private boolean pendingVoicePhotoCapture;
    private int currentProjectIndex = 0;
    private ChatAiClient chatAiClient;
    private BackendChatClient backendChatClient;
    private DirectAsrClient directAsrClient;
    private IntegratedModeController modeController;
    private ExpertCollabCoordinator expertCoordinator;
    private CollabServiceHealth.State collabServiceState = CollabServiceHealth.State.CHECKING;
    private boolean collabHealthCheckInFlight;
    private long lastCollabHealthCheckAtMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveSystemUi();
        managedRestrictions = readManagedRestrictions();
        runtimeConfiguration = resolveRuntimeConfiguration(managedRestrictions);
        deviceSyncConfiguration = resolveDeviceSyncConfiguration();
        if (SECURE_RUNTIME && deviceSyncConfiguration != null) {
            deviceSessionManager = new DeviceSessionManager(
                    deviceSyncConfiguration.bootstrapCredential(),
                    new HttpDeviceSessionIssuer(deviceSyncConfiguration));
            deviceSessionManager.prewarm();
        }
        chatAiClient = createChatAiClient();
        directAsrClient = new DirectAsrClient(DIRECT_ASR_ENDPOINT, DIRECT_ASR_API_KEY);
        realtimeAsrClient = createRealtimeAsrClient();
        wakeWordEngine = WakeWordEngines.create(
                getApplicationContext(),
                OFFLINE_WAKE_ENABLED && runtimeConfiguration.hasIflytekCredentials(),
                runtimeConfiguration.iflytekAppId(),
                runtimeConfiguration.iflytekApiKey(),
                runtimeConfiguration.iflytekApiSecret());
        taskSyncClient = createManagedTaskSyncClient();
        taskSyncReporter = taskSyncClient == null ? null : new TaskSyncReporter(taskSyncClient);
        workflowDeliveryController = createManagedWorkflowDeliveryController();
        if (workflowDeliveryController != null) {
            try {
                workflowDeliveryController.start();
            } catch (RuntimeException exception) {
                Log.e(KEY_LOG_TAG, "Workflow delivery network monitor failed", exception);
                workflowDeliveryController.close();
                workflowDeliveryController = null;
                resetManagedWorkflowRuntime();
            }
        }
        restoreTaskSessions();
        restoreInspectionRun();
        restoreChatProjects();
        restoreAgentAuthorizations();
        int removedIncompleteVideos = purgeIncompleteSceneVideos(new File(getFilesDir(), "evidence"));
        if (removedIncompleteVideos > 0) {
            Log.i(KEY_LOG_TAG, "Removed incomplete scene videos count=" + removedIncompleteVideos);
        }
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
        renderChatScreen();
        requestStartupPermissions();
        refreshCollabServiceHealth(true);
    }

    private TaskSyncClient createManagedTaskSyncClient() {
        if (deviceSyncConfiguration == null) {
            Log.i(KEY_LOG_TAG, "Device task sync is not provisioned");
            return null;
        }
        DeviceAccessTokenProvider tokenProvider = deviceSessionManager;
        if (tokenProvider == null) {
            tokenProvider = new DeviceAccessTokenProvider() {
                @Override
                public String accessToken() {
                    return deviceSyncConfiguration.bootstrapCredential();
                }
            };
        }
        return new TaskSyncClient(new File(getFilesDir(), "task-sync/events.json"),
                new HttpTaskSyncTransport(deviceSyncConfiguration, tokenProvider));
    }

    private WorkflowDeliveryController createManagedWorkflowDeliveryController() {
        if (!SECURE_RUNTIME || deviceSyncConfiguration == null || deviceSessionManager == null) {
            Log.i(KEY_LOG_TAG, "Managed workflow delivery is not provisioned");
            return null;
        }
        String managedKeySet = managedRestrictions == null
                ? ""
                : managedRestrictions.getString(WORKFLOW_TRUSTED_PUBLIC_KEYS, "");
        if (managedKeySet.trim().isEmpty()) {
            Log.w(KEY_LOG_TAG, "Managed workflow signing public keys are not provisioned");
            return null;
        }
        ExecutorService executor = null;
        try {
            ManagedWorkflowPublicKeySource publicKeys =
                    ManagedWorkflowPublicKeySource.fromManagedJson(managedKeySet);
            Map<String, WorkflowCapabilityRegistry.Handler> handlers = new HashMap<>();
            handlers.put("camera.photo", new WorkflowCapabilityRegistry.Handler() {
                @Override
                public void execute(
                        final WorkflowCapabilityRegistry.Request request,
                        final WorkflowCapabilityRegistry.Callback callback
                ) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            beginWorkflowPhotoCapture(request, callback);
                        }
                    });
                }
            });
            handlers.put("camera.video", new WorkflowCapabilityRegistry.Handler() {
                @Override
                public void execute(
                        final WorkflowCapabilityRegistry.Request request,
                        final WorkflowCapabilityRegistry.Callback callback
                ) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            beginWorkflowVideoCapture(request, callback);
                        }
                    });
                }
            });
            WorkflowCapabilityRegistry capabilityRegistry = new WorkflowCapabilityRegistry(handlers);
            Set<String> supportedCapabilities = capabilityRegistry.supportedCapabilities();
            WorkflowPackageVerifier verifier = new WorkflowPackageVerifier(
                    publicKeys,
                    BuildConfig.VERSION_CODE,
                    1,
                    supportedCapabilities);
            File deliveryDirectory = new File(getFilesDir(), "workflow-delivery");
            WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(
                    new AndroidAtomicWorkflowSnapshotStorage(
                            deliveryDirectory,
                            "assignments.json"));
            WorkflowDeviceHttpClient client = new WorkflowDeviceHttpClient(
                    deviceSyncConfiguration,
                    deviceSessionManager,
                    BuildConfig.VERSION_CODE,
                    1,
                    new ArrayList<>(supportedCapabilities));
            AndroidWorkflowPackageStoreProvider storeProvider =
                    new AndroidWorkflowPackageStoreProvider(
                            new File(deliveryDirectory, "packages"), verifier);
            VerifiedWorkflowPackageCache packageCache = new VerifiedWorkflowPackageCache(
                    verifier, storeProvider);
            WorkflowAssignmentSyncCoordinator operation =
                    new WorkflowAssignmentSyncCoordinator(client, repository, packageCache, 4);
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "workflow-delivery-sync");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            WorkflowSyncTriggerCoordinator trigger = new WorkflowSyncTriggerCoordinator(
                    operation,
                    executor,
                    repository::cursor);
            WorkflowPackageSnapshotAccess snapshotAccess =
                    new WorkflowPackageSnapshotAccess(storeProvider);
            WorkflowExecutionCoordinator executionCoordinator =
                    new WorkflowExecutionCoordinator(
                            repository,
                            snapshotAccess,
                            client,
                            executor);
            WorkflowEvidenceUploadCoordinator evidenceUploadCoordinator =
                    new WorkflowEvidenceUploadCoordinator(
                            getFilesDir(),
                            new WorkflowEvidenceUploadCoordinator.PendingSource() {
                                @Override
                                public List<WorkflowEvidenceUploadCoordinator.PendingEvidence>
                                        pending() {
                                    return pendingWorkflowEvidence(repository, snapshotAccess);
                                }
                            },
                            new WorkflowEvidenceUploadCoordinator.Transport() {
                                @Override
                                public String upload(
                                        WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
                                        byte[] bytes,
                                        String capturedAt
                                ) throws IOException {
                                    return client.uploadEvidence(
                                            evidence.assignmentId(),
                                            evidence.executionId(),
                                            evidence.localEvidenceId(),
                                            evidence.nodeId(),
                                            evidence.evidenceKey(),
                                            evidence.kind(),
                                            evidence.contentType(),
                                            evidence.durationSeconds(),
                                            bytes,
                                            capturedAt);
                                }
                            },
                            new WorkflowEvidenceUploadCoordinator.Acknowledger() {
                                @Override
                                public boolean acknowledge(
                                        String assignmentId,
                                        String localEvidenceId,
                                        String remoteAssetId
                                ) {
                                    WorkflowExecutionCoordinator.ActionResult result =
                                            executionCoordinator.onEvidenceUploaded(
                                                    assignmentId,
                                                    localEvidenceId,
                                                    remoteAssetId);
                                    return result.code()
                                            == WorkflowExecutionCoordinator.ActionCode.EVIDENCE_UPDATED
                                            || result.code()
                                            == WorkflowExecutionCoordinator.ActionCode.REPLAY_QUEUED;
                                }
                            },
                            executor);
            workflowAssignmentRepository = repository;
            workflowPackageStoreProvider = storeProvider;
            workflowSnapshotAccess = snapshotAccess;
            workflowDeviceHttpClient = client;
            workflowCapabilityRegistry = capabilityRegistry;
            workflowExecutionCoordinator = executionCoordinator;
            workflowEvidenceUploadCoordinator = evidenceUploadCoordinator;
            ExecutorService ownedExecutor = executor;
            Log.i(KEY_LOG_TAG,
                    "Managed workflow delivery ready trustedKeys=" + publicKeys.size()
                            + " capabilities=" + supportedCapabilities.size());
            return new WorkflowDeliveryController(
                    deviceSessionManager::prewarm,
                    new java.util.function.LongPredicate() {
                        @Override public boolean test(long hintedSequence) {
                            boolean requested = trigger.request(hintedSequence);
                            resumePendingWorkflowOutboxes(executionCoordinator);
                            evidenceUploadCoordinator.request();
                            return requested;
                        }
                    },
                    new AndroidNetworkAvailabilityMonitor(getApplicationContext()),
                    ownedExecutor::shutdownNow);
        } catch (RuntimeException exception) {
            if (executor != null) executor.shutdownNow();
            resetManagedWorkflowRuntime();
            Log.e(KEY_LOG_TAG, "Managed workflow delivery initialization failed", exception);
            return null;
        }
    }

    private void resetManagedWorkflowRuntime() {
        workflowCapturePending = false;
        workflowPhotoCallback = null;
        cancelWorkflowVideoCapture("workflow_video_runtime_reset");
        activeWorkflowAssignmentId = "";
        workflowExecutionCoordinator = null;
        workflowEvidenceUploadCoordinator = null;
        workflowCapabilityRegistry = null;
        workflowDeviceHttpClient = null;
        workflowSnapshotAccess = null;
        workflowPackageStoreProvider = null;
        workflowAssignmentRepository = null;
    }

    private List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pendingWorkflowEvidence(
            WorkflowAssignmentRepository repository,
            WorkflowPackageSnapshotAccess snapshotAccess
    ) {
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending = new ArrayList<>();
        for (WorkflowAssignmentRepository.CachedAssignment assignment
                : repository.assignments()) {
            if ("revoked".equals(assignment.status())
                    || "none".equals(assignment.mode())
                    || !assignment.packageCached()) {
                continue;
            }
            WorkflowSnapshot snapshot = snapshotAccess.load(assignment.assignmentId());
            WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
            if (state == null || state.executionId().isEmpty()) continue;
            for (WorkflowEvidenceReference evidence : state.evidenceReferences()) {
                if (!evidence.remoteAssetId().isEmpty()
                        || (evidence.type() != WorkflowStepContext.EvidenceType.PHOTO
                        && evidence.type() != WorkflowStepContext.EvidenceType.VIDEO)) {
                    continue;
                }
                try {
                    WorkflowEvidenceUploadCoordinator.MediaKind mediaKind =
                            evidence.type() == WorkflowStepContext.EvidenceType.VIDEO
                                    ? WorkflowEvidenceUploadCoordinator.MediaKind.VIDEO
                                    : WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO;
                    pending.add(new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                            assignment.assignmentId(),
                            state.executionId(),
                            evidence.localEvidenceId(),
                            evidence.nodeId(),
                            evidence.evidenceKey(),
                            mediaKind,
                            evidence.localReference(),
                            evidence.durationSeconds()));
                } catch (IllegalArgumentException exception) {
                    Log.w(KEY_LOG_TAG, "Invalid pending workflow evidence", exception);
                }
            }
        }
        return pending;
    }

    private void resumePendingWorkflowOutboxes(
            WorkflowExecutionCoordinator executionCoordinator
    ) {
        for (WorkflowExecutionCoordinator.TaskListItem task : executionCoordinator.tasks()) {
            if (task.entryAction() == WorkflowExecutionCoordinator.EntryAction.RESUME_WORKFLOW
                    || task.entryAction()
                    == WorkflowExecutionCoordinator.EntryAction.VIEW_COMPLETED) {
                executionCoordinator.startOrResume(task.assignmentId());
            }
        }
    }

    private DeviceSyncConfiguration resolveDeviceSyncConfiguration() {
        String endpoint = managedRestrictions == null
                ? ""
                : managedRestrictions.getString("ops_device_sync_endpoint", "");
        if (endpoint.trim().length() == 0 && runtimeConfiguration.backendBaseUrl().length() > 0) {
            endpoint = runtimeConfiguration.backendBaseUrl() + "/device-sync/events";
        }
        return DeviceSyncConfiguration.fromManagedValues(
                endpoint,
                runtimeConfiguration.backendCredential());
    }

    private Bundle readManagedRestrictions() {
        RestrictionsManager manager = (RestrictionsManager) getSystemService(RESTRICTIONS_SERVICE);
        Bundle restrictions = manager == null ? null : manager.getApplicationRestrictions();
        return restrictions == null ? new Bundle() : restrictions;
    }

    private ManagedRuntimeConfiguration resolveRuntimeConfiguration(Bundle restrictions) {
        Map<String, String> managed = new HashMap<>();
        if (restrictions != null) {
            managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL,
                    restrictions.getString(ManagedRuntimeConfiguration.BACKEND_BASE_URL, ""));
            String backendToken = restrictions.getString(
                    ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, "");
            if (backendToken.trim().length() == 0) {
                backendToken = restrictions.getString("ops_device_sync_token", "");
            }
            managed.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, backendToken);
            managed.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID,
                    restrictions.getString(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, ""));
            managed.put(ManagedRuntimeConfiguration.IFLYTEK_API_KEY,
                    restrictions.getString(ManagedRuntimeConfiguration.IFLYTEK_API_KEY, ""));
            managed.put(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET,
                    restrictions.getString(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET, ""));
        }
        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                SECURE_RUNTIME,
                DINGDANG_BACKEND_BASE_URL,
                DINGDANG_BACKEND_API_KEY,
                GeneratedConfig.IFLYTEK_APP_ID,
                GeneratedConfig.IFLYTEK_API_KEY,
                GeneratedConfig.IFLYTEK_API_SECRET,
                managed);
        if (SECURE_RUNTIME && !configuration.isBackendProvisioned()) {
            Log.w(KEY_LOG_TAG, "Secure runtime backend credential is not provisioned");
        }
        if (SECURE_RUNTIME && OFFLINE_WAKE_ENABLED && !configuration.hasIflytekCredentials()) {
            Log.w(KEY_LOG_TAG, "Secure runtime offline wake authorization is not provisioned");
        }
        return configuration;
    }

    private TaskSession startNewTaskForActiveProject(String problem) {
        ChatProject project = activeProject();
        if (project == null) return null;
        TaskSession session = taskSessionManager.startNew(project.id, problem);
        persistChatProjects();
        recordTaskSyncEvent(session, "task_started",
                taskSyncPayload("problem", session.maintenanceTask().initialProblem()),
                session.id() + ":task_started");
        return session;
    }

    private void recordTaskSyncEvent(TaskSession session, String eventType,
            JSONObject payload, String idempotencyKey) {
        if (taskSyncReporter == null || session == null) return;
        String projectTitle = "";
        for (ChatProject project : chatProjects) {
            if (session.projectId().equals(project.id)) {
                projectTitle = project.title;
                break;
            }
        }
        taskSyncReporter.record(TaskSyncEventFactory.create(
                session,
                projectTitle,
                eventType,
                payload,
                System.currentTimeMillis(),
                idempotencyKey));
    }

    private void recordCurrentTaskSyncEvent(String eventType, JSONObject payload, String suffix) {
        TaskSession session = taskSessionManager.active();
        if (session == null) return;
        String safeSuffix = suffix == null || suffix.trim().length() == 0
                ? UUID.randomUUID().toString()
                : suffix.trim();
        recordTaskSyncEvent(session, eventType, payload,
                session.id() + ":" + eventType + ":" + safeSuffix);
    }

    private static JSONObject taskSyncPayload(Object... values) {
        JSONObject payload = new JSONObject();
        if (values == null) return payload;
        try {
            for (int index = 0; index + 1 < values.length; index += 2) {
                payload.put(String.valueOf(values[index]), values[index + 1]);
            }
        } catch (Exception ignored) {
            return new JSONObject();
        }
        return payload;
    }

    private void persistAndRecordUserTurn(String text, boolean hasPhoto) {
        persistChatProjects();
        recordCurrentTaskSyncEvent("user_message",
                taskSyncPayload("text", text, "hasPhoto", hasPhoto), "");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_AUDIO) {
            renderChatScreen();
            if (granted) {
                scheduleForegroundVoiceListening("audio_permission_granted");
            }
            if (shouldRequestCameraPermission(screenMode == ScreenMode.CAMERA)) {
                requestCameraPermissionIfNeeded();
            }
            return;
        }
        if (requestCode == REQUEST_CAMERA && granted && screenMode == ScreenMode.CAMERA) {
            startCameraFlow();
        } else if (requestCode == REQUEST_CAMERA && !granted && screenMode == ScreenMode.CAMERA) {
            pendingVoicePhotoCapture = false;
            pendingSceneVideoCapture = false;
            sceneVideoStarting = false;
            cameraStatusText.setText("相机权限未开启，请在系统设置中授权后再拍摄");
            failWorkflowVideoCaptureAndReturn(
                    "workflow_video_camera_permission_denied",
                    "相机权限未授权，工作流录像未开始");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveSystemUi();
        if (workflowDeliveryController != null) {
            workflowDeliveryController.onForeground();
        }
        if (screenMode == ScreenMode.CAMERA
                && previewView != null && previewView.isAvailable() && cameraDevice == null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
        }
        if (screenMode != ScreenMode.EXPERT && isForegroundWakeListeningEnabled()) {
            scheduleForegroundVoiceListening("resume");
        } else {
            cancelForegroundVoiceListening();
        }
        if (screenMode == ScreenMode.CHAT) {
            renderChatScreen();
        }
        refreshCollabServiceHealth(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveSystemUi();
        }
    }

    private void applyImmersiveSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
        cancelWorkflowPhotoCapture("workflow_photo_interrupted");
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelWorkflowPhotoCapture("workflow_photo_destroyed");
        releaseExpertCoordinator();
        if (workflowDeliveryController != null) {
            workflowDeliveryController.close();
            workflowDeliveryController = null;
        }
        resetManagedWorkflowRuntime();
        if (taskSyncReporter != null) {
            taskSyncReporter.close();
            taskSyncReporter = null;
        }
        if (deviceSessionManager != null) {
            deviceSessionManager.close();
            deviceSessionManager = null;
        }
        if (hudPresentation != null) {
            hudPresentation.destroy();
            hudPresentation = null;
        }
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
        if (hudVoiceGuideVisible || hudGlassesGuideVisible) {
            if (isBackShortcutKey(keyCode) || isConfirmKey(keyCode)) {
                returnToHudStandby("语音待命");
            }
            return true;
        }
        if (isCapabilityCenterVisible()) {
            if (isBackShortcutKey(keyCode)) {
                if (capabilityDetailVisible) {
                    if ("workflow".equals(hudOperationAbilityId)
                            && !activeWorkflowAssignmentId.isEmpty()) {
                        performHudOperation(workflowNavigationBackAction(
                                hudOperationDetail == null
                                        ? "" : hudOperationDetail.primaryAction(),
                                hudOperationDetail == null
                                        ? "" : hudOperationDetail.secondaryAction()));
                    } else if (hudPresentation != null) {
                        capabilityDetailVisible = false;
                        hudPresentation.showState("capabilities");
                    } else {
                        renderCapabilityHub();
                    }
                } else {
                    hideCapabilityCenter();
                }
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
                if (sceneVideoRecording) {
                    stopSceneVideoCapture("hardware-stop");
                } else {
                    captureStillImage();
                }
            } else {
                startToggleVoiceRecording();
            }
            return true;
        }
        if (isCameraShortcutKey(keyCode)) {
            if (screenMode == ScreenMode.CAMERA) {
                if (sceneVideoRecording) {
                    stopSceneVideoCapture("hardware-stop");
                } else {
                    captureStillImage();
                }
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
                String target = chatBackTarget(isProjectRailVisible(), hudTaskWorkspaceActive);
                if ("project-rail".equals(target)) {
                    setProjectRailVisible(false);
                } else if ("standby".equals(target)) {
                    returnToHudStandby("语音待命");
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

    static String chatBackTarget(boolean projectRailVisible, boolean taskWorkspaceActive) {
        if (projectRailVisible) {
            return "project-rail";
        }
        return taskWorkspaceActive ? "standby" : "chat";
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
        chatLayer.setBackgroundColor(Color.rgb(243, 247, 246));
        chatLayer.setPadding(dp(28), dp(20), dp(28), dp(18));
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
        projectRail.setPadding(dp(18), dp(18), dp(18), dp(18));
        projectRail.setBackground(roundRect(Color.WHITE, Color.rgb(220, 231, 227), 8));
        projectRailParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT);
        projectRailParams.leftMargin = 0;
        chatLayer.addView(projectRail, projectRailParams);
        projectRail.setVisibility(View.GONE);

        TextView projectHeader = new TextView(this);
        projectHeader.setText("运维工作台\nAI 指导与现场运维能力");
        projectHeader.setTextColor(Color.rgb(25, 56, 48));
        projectHeader.setTextSize(18);
        projectHeader.setTypeface(Typeface.DEFAULT_BOLD);
        projectHeader.setLineSpacing(dp(4), 1f);
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

        TextView moreOperationsButton = menuItem("更多运维能力");
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

        TextView leftIcon = iconButton("☰");
        leftIcon.setTextSize(25);
        leftIcon.setContentDescription("AI 能力中心");
        leftIcon.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 29));
        topBar.addView(leftIcon, squareParams(58));
        leftIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCapabilityCenter();
            }
        });

        titleText = new TextView(this);
        titleText.setText("现场设备诊断");
        titleText.setTextColor(Color.rgb(23, 37, 35));
        titleText.setTextSize(20);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        titleText.setSingleLine(true);
        topBar.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stateText = new TextView(this);
        stateText.setText("● Air3 · 在线");
        stateText.setTextColor(Color.rgb(50, 117, 92));
        stateText.setTextSize(14);
        stateText.setGravity(Gravity.RIGHT);
        stateText.setMaxLines(2);
        stateText.setVisibility(View.VISIBLE);
        topBar.addView(stateText, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT));

        menuButton = iconButton("☰");
        menuButton.setTextSize(25);
        menuButton.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 29));
        topBar.addView(menuButton, squareParams(58));
        menuButton.setVisibility(View.GONE);

        chatScrollView = new ScrollView(this);
        chatScrollView.setFillViewport(false);
        chatScrollView.setFocusable(false);
        chatScrollView.setFocusableInTouchMode(false);
        chatScrollView.setDefaultFocusHighlightEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        scrollParams.topMargin = dp(16);
        scrollParams.bottomMargin = dp(8);
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
        composerPanel.setPadding(0, dp(8), 0, 0);
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

        voiceWaveView = new AudioWaveView(this);
        voiceWaveView.setVisibility(View.GONE);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(dp(540), dp(90));
        waveParams.bottomMargin = dp(6);
        draftBox.addView(voiceWaveView, waveParams);

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(dp(8), dp(7), dp(10), dp(7));
        controlsRow.setBackground(roundRect(Color.WHITE, Color.rgb(215, 228, 224), 10));
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(dp(880), dp(72));
        controlsParams.gravity = Gravity.CENTER_HORIZONTAL;
        composerPanel.addView(controlsRow, controlsParams);

        cameraButton = new TextView(this);
        cameraButton.setText("+");
        cameraButton.setTextColor(Color.rgb(52, 71, 65));
        cameraButton.setTextSize(30);
        cameraButton.setGravity(Gravity.CENTER);
        cameraButton.setContentDescription("现场拍摄");
        cameraButton.setClickable(true);
        cameraButton.setDefaultFocusHighlightEnabled(false);
        controlsRow.addView(cameraButton, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.MATCH_PARENT));

        transcriptDraftText = new TextView(this);
        transcriptDraftText.setText("");
        transcriptDraftText.setTextColor(Color.rgb(120, 120, 120));
        transcriptDraftText.setTextSize(17);
        transcriptDraftText.setGravity(Gravity.CENTER_VERTICAL);
        transcriptDraftText.setMaxLines(1);
        transcriptDraftText.setPadding(dp(8), 0, dp(8), 0);
        controlsRow.addView(transcriptDraftText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        voiceButton = new TextView(this);
        voiceButton.setText("\uD83C\uDFA4");
        voiceButton.setTextColor(Color.WHITE);
        voiceButton.setTextSize(20);
        voiceButton.setGravity(Gravity.CENTER);
        voiceButton.setContentDescription("语音提问");
        voiceButton.setClickable(true);
        voiceButton.setDefaultFocusHighlightEnabled(false);
        voiceButton.setBackground(roundRect(Color.rgb(12, 139, 104), Color.TRANSPARENT, 28));
        controlsRow.addView(voiceButton, new LinearLayout.LayoutParams(dp(52), dp(52)));

        cameraOverlay = new LinearLayout(this);
        cameraOverlay.setOrientation(LinearLayout.VERTICAL);
        cameraOverlay.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cameraOverlay.setPadding(dp(28), dp(24), dp(28), dp(28));
        cameraOverlay.setVisibility(View.GONE);
        root.addView(cameraOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        expertLayer = new FrameLayout(this);
        expertLayer.setBackgroundColor(Color.rgb(237, 243, 241));
        expertLayer.setVisibility(View.GONE);
        root.addView(expertLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildHudPresentation();

        buildCapabilityCenter();

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
                if (sceneVideoRecording) {
                    stopSceneVideoCapture("touch-stop");
                } else if (composerImageBytes == null) {
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

    private void buildHudPresentation() {
        hudLayer = new FrameLayout(this);
        hudLayer.setVisibility(View.VISIBLE);
        WebView webView = new WebView(this);
        hudPresentation = new HudWebPresentation(webView, new HudWebPresentation.Actions() {
            @Override public void openCapabilities() { runHudAction(new Runnable() {
                @Override public void run() { showCapabilityCenter(); }
            }); }
            @Override public void capturePhoto() { runHudAction(new Runnable() {
                @Override public void run() {
                    activateHudTaskWorkspace();
                    voiceEventStateMachine.beginPhotoCapture();
                    if (hudTaskProgress == HudTaskProgress.GUIDANCE) {
                        setChatStatus("请拍摄当前维修部位近景");
                    } else {
                        clearHudTaskProgress();
                    }
                    enterCameraScreen("hud-capture");
                }
            }); }
            @Override public void captureVideo() { runHudAction(new Runnable() {
                @Override public void run() { startSceneVideoCapture(); }
            }); }
            @Override public void startVoice() { runHudAction(new Runnable() {
                @Override public void run() {
                    activateHudTaskWorkspace();
                    recoverableAiError = "";
                    if (hudTaskProgress == HudTaskProgress.GUIDANCE) {
                        setChatStatus("请描述当前维修步骤的结果");
                    } else {
                        clearHudTaskProgress();
                    }
                    startToggleVoiceRecording();
                }
            }); }
            @Override public void openDiagnosis() { runHudAction(new Runnable() {
                @Override public void run() { beginVoiceDiagnosisConversation(); }
            }); }
            @Override public void openExpert() { runHudAction(new Runnable() {
                @Override public void run() { featureRegistry.require("expert_collab").enter(MainActivity.this); }
            }); }
            @Override public void openAbility(final String route) { runHudAction(new Runnable() {
                @Override public void run() { showHudAbility(route); }
            }); }
            @Override public void startGuidance() { runHudAction(new Runnable() {
                @Override public void run() { startHudGuidance(); }
            }); }
            @Override public void previousConversationPage() { runHudAction(new Runnable() {
                @Override public void run() { changeConversationPage(false); }
            }); }
            @Override public void nextConversationPage() { runHudAction(new Runnable() {
                @Override public void run() { changeConversationPage(true); }
            }); }
            @Override public void previousDiagnosisPage() { runHudAction(new Runnable() {
                @Override public void run() { changeDiagnosisPage(false); }
            }); }
            @Override public void nextDiagnosisPage() { runHudAction(new Runnable() {
                @Override public void run() { changeDiagnosisPage(true); }
            }); }
            @Override public void sendImageOnly() { runHudAction(new Runnable() {
                @Override public void run() { submitImageOnly(); }
            }); }
            @Override public void retryAi() { runHudAction(new Runnable() {
                @Override public void run() { retryAiWithVoice(); }
            }); }
            @Override public void openVoiceGuide() { runHudAction(new Runnable() {
                @Override public void run() { openHudVoiceGuide(); }
            }); }
            @Override public void openGlassesTutorial() { runHudAction(new Runnable() {
                @Override public void run() { openHudGlassesTutorial(); }
            }); }
            @Override public void previousTutorialPage() { runHudAction(new Runnable() {
                @Override public void run() { hudPresentation.changeTutorialPage(false); }
            }); }
            @Override public void nextTutorialPage() { runHudAction(new Runnable() {
                @Override public void run() { hudPresentation.changeTutorialPage(true); }
            }); }
            @Override public void openVoiceSettings() { runHudAction(new Runnable() {
                @Override public void run() { openVoicePermissionSettings(); }
            }); }
            @Override public void hangUp() { runHudAction(new Runnable() {
                @Override public void run() { exitExpertMode(); }
            }); }
            @Override public void goBack() { runHudAction(new Runnable() {
                @Override public void run() { navigateHudBack(); }
            }); }
            @Override public void goHome() { runHudAction(new Runnable() {
                @Override public void run() { returnToHudStandby("语音待命"); }
            }); }
            @Override public void restartTask() { runHudAction(new Runnable() {
                @Override public void run() { restartHudTask(); }
            }); }
            @Override public void performOperation(final String action) { runHudAction(new Runnable() {
                @Override public void run() { performHudOperation(action); }
            }); }
        });
        hudLayer.addView(hudPresentation.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(hudLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void runHudAction(Runnable action) {
        mainHandler.post(action);
    }

    private void buildCapabilityCenter() {
        capabilityLayer = new FrameLayout(this);
        // Keep the underlying task faintly visible while the HUD owns the focus.
        capabilityLayer.setBackgroundColor(Color.argb(192, 225, 235, 231));
        capabilityLayer.setVisibility(View.GONE);
        root.addView(capabilityLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout surface = new FrameLayout(this);
        surface.setBackground(roundRect(Color.argb(232, 248, 252, 250), Color.rgb(188, 216, 205), 12));
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                dp(920),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        surfaceParams.setMargins(dp(54), dp(32), dp(54), dp(32));
        capabilityLayer.addView(surface, surfaceParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        surface.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        capabilityContent = new LinearLayout(this);
        capabilityContent.setOrientation(LinearLayout.VERTICAL);
        capabilityContent.setPadding(dp(54), dp(34), dp(54), dp(30));
        scrollView.addView(capabilityContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        renderCapabilityHub();
    }

    private void showCapabilityCenter() {
        if (hudPresentation != null) {
            setProjectRailVisible(false);
            hudVoiceGuideVisible = false;
            hudGlassesGuideVisible = false;
            hudCapabilityVisible = true;
            capabilityDetailVisible = false;
            hudOperationAbilityId = "";
            hudPresentation.showState("capabilities");
            refreshCollabServiceHealth(false);
            return;
        }
        if (capabilityLayer == null) {
            return;
        }
        setProjectRailVisible(false);
        renderCapabilityHub();
        capabilityLayer.setVisibility(View.VISIBLE);
        refreshCollabServiceHealth(false);
    }

    private void hideCapabilityCenter() {
        capabilityDetailVisible = false;
        hudCapabilityVisible = false;
        hudOperationAbilityId = "";
        if (hudPresentation != null) {
            syncHudPresentation();
            return;
        }
        if (capabilityLayer != null) {
            capabilityLayer.setVisibility(View.GONE);
        }
    }

    private boolean isCapabilityCenterVisible() {
        if (hudPresentation != null) {
            return hudCapabilityVisible;
        }
        return capabilityLayer != null && capabilityLayer.getVisibility() == View.VISIBLE;
    }

    private void showHudAbility(String route) {
        if (hudPresentation == null) {
            return;
        }
        hudCapabilityVisible = true;
        capabilityDetailVisible = true;
        if ("memory".equals(route)) {
            route = "device_brain";
        } else if ("agent".equals(route)) {
            route = "agent_center";
        }
        AIAbilityConfig ability = null;
        for (AIAbilityConfig config : aiAbilityConfigs) {
            if (config.id().equals(route)) {
                ability = config;
                break;
            }
        }
        if (ability == null) {
            capabilityDetailVisible = false;
            hudPresentation.showState("capabilities");
            return;
        }
        if (ability.route() != AIAbilityConfig.Route.DIAGNOSIS
                && ability.route() != AIAbilityConfig.Route.EXPERT_COLLAB) {
            showHudOperationDetail(ability.id());
            return;
        }
        hudPresentation.setAbilityDetail(
                ability.title(),
                ability.pageDescription(),
                ability.voiceCommand());
        hudPresentation.showState("abilityDetail");
    }

    private void showHudOperationDetail(String abilityId) {
        showHudOperationDetail(abilityId, false);
    }

    private void showHudOperationDetail(String abilityId, boolean preservePage) {
        OperationDetail detail = resolveHudOperationDetail(abilityId);
        showHudOperationDetail(abilityId, detail, preservePage);
    }

    private OperationDetail resolveHudOperationDetail(String abilityId) {
        if ("tasks".equals(abilityId) && workflowExecutionCoordinator != null) {
            List<WorkflowExecutionCoordinator.TaskListItem> tasks =
                    workflowExecutionCoordinator.tasks();
            if (!tasks.isEmpty()) return workflowHudPresenter.taskList(tasks);
        }
        return operationDetailFactory.create(abilityId, currentMaintenanceTask(),
                inspectionChecklist, activeInspectionRun, taskSessionManager.sessions());
    }

    private void showHudOperationDetail(
            String abilityId,
            OperationDetail detail,
            boolean preservePage
    ) {
        if (detail == null) return;
        hudCapabilityVisible = true;
        capabilityDetailVisible = true;
        hudOperationAbilityId = abilityId == null ? "" : abilityId;
        hudOperationDetail = detail;
        int pageCount = operationPageCount(detail.items().size(), HUD_OPERATION_PAGE_SIZE);
        hudOperationPageIndex = preservePage
                ? Math.max(0, Math.min(hudOperationPageIndex, pageCount - 1)) : 0;
        int start = operationPageStart(hudOperationPageIndex, HUD_OPERATION_PAGE_SIZE,
                detail.items().size());
        int end = Math.min(detail.items().size(), start + HUD_OPERATION_PAGE_SIZE);
        List<String> pageItems = detail.items().subList(start, end);
        List<String> pageActions = detail.itemActions().subList(
                Math.min(start, detail.itemActions().size()),
                Math.min(end, detail.itemActions().size()));
        hudPresentation.setOperationDetail(detail.tag(), detail.title(), detail.description(),
                pageItems.toArray(new String[0]), pageActions.toArray(new String[0]),
                detail.primaryAction(), detail.primaryLabel(),
                detail.secondaryAction(), detail.secondaryLabel());
        hudPresentation.setOperationPage(hudOperationPageIndex + 1, pageCount);
        hudPresentation.showState("operationDetail");
        scheduleForegroundVoiceListening("operation-detail");
    }

    static int operationPageCount(int itemCount, int pageSize) {
        int safeSize = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, itemCount) + safeSize - 1) / safeSize);
    }

    static int operationPageStart(int pageIndex, int pageSize, int itemCount) {
        int safeSize = Math.max(1, pageSize);
        int pageCount = operationPageCount(itemCount, safeSize);
        int safePage = Math.max(0, Math.min(pageIndex, pageCount - 1));
        return Math.min(Math.max(0, itemCount), safePage * safeSize);
    }

    static String workflowNavigationBackAction(
            String primaryAction,
            String secondaryAction
    ) {
        String secondary = secondaryAction == null ? "" : secondaryAction.trim();
        if ("workflow_list".equals(secondary) || "workflow_back".equals(secondary)) {
            return secondary;
        }
        String primary = primaryAction == null ? "" : primaryAction.trim();
        if (primary.startsWith("workflow_start:")
                || primary.startsWith("workflow_resume:")
                || primary.startsWith("workflow_choose:")
                || primary.startsWith("workflow_standard:")) {
            return "workflow_list";
        }
        return "workflow_back";
    }

    private void changeHudOperationPage(boolean next) {
        OperationDetail detail = hudOperationDetail == null
                ? resolveHudOperationDetail(hudOperationAbilityId) : hudOperationDetail;
        int pageCount = operationPageCount(detail.items().size(), HUD_OPERATION_PAGE_SIZE);
        int target = hudOperationPageIndex + (next ? 1 : -1);
        if (target < 0 || target >= pageCount) {
            setChatStatus(next ? "已是最后一页" : "已是第一页");
            scheduleForegroundVoiceListening("operation-page-boundary");
            return;
        }
        hudOperationPageIndex = target;
        showHudOperationDetail(hudOperationAbilityId, detail, true);
        setChatStatus(next ? "能力内容下一页" : "能力内容上一页");
    }

    private void performHudOperation(String action) {
        String value = action == null ? "" : action.trim();
        if ("operation_next_page".equals(value)) {
            changeHudOperationPage(true);
            return;
        }
        if ("operation_previous_page".equals(value)) {
            changeHudOperationPage(false);
            return;
        }
        if (value.startsWith("workflow_open:")) {
            openWorkflowTaskDetail(value.substring("workflow_open:".length()));
            return;
        }
        if ("workflow_list".equals(value)) {
            activeWorkflowAssignmentId = "";
            showHudOperationDetail("tasks");
            setChatStatus("已返回维修工单");
            return;
        }
        if (value.startsWith("workflow_start:")) {
            openWorkflowExecution(value.substring("workflow_start:".length()), false);
            return;
        }
        if (value.startsWith("workflow_resume:")) {
            openWorkflowExecution(value.substring("workflow_resume:".length()), false);
            return;
        }
        if (value.startsWith("workflow_choose:")) {
            openWorkflowExecution(value.substring("workflow_choose:".length()), true);
            return;
        }
        if (value.startsWith("workflow_standard:")) {
            activeWorkflowAssignmentId = value.substring("workflow_standard:".length()).trim();
            hideCapabilityCenter();
            setChatStatus("已进入普通维修任务");
            beginVoiceDiagnosisConversation();
            return;
        }
        if ("workflow_back".equals(value)) {
            openWorkflowTaskDetail(activeWorkflowAssignmentId);
            return;
        }
        if ("workflow_capture_photo".equals(value)) {
            dispatchWorkflowPhotoCapture();
            return;
        }
        if ("workflow_capture_video".equals(value)) {
            dispatchWorkflowVideoCapture();
            return;
        }
        if ("workflow_next".equals(value)) {
            advanceActiveWorkflow(false, "");
            return;
        }
        if (value.startsWith("workflow_select:")) {
            selectActiveWorkflowChoice(value);
            return;
        }
        if ("workflow_confirm".equals(value)) {
            showWorkflowConfirmation("confirm", "确认执行当前操作？",
                    "确认后将推进当前工单步骤。AI 不会代替你确认。");
            return;
        }
        if ("workflow_complete".equals(value)) {
            showWorkflowConfirmation("complete", "确认完成当前任务？",
                    "确认后当前工作流将结束，并保留项目记录与未同步证据队列。");
            return;
        }
        if (value.startsWith("workflow_confirmed:")) {
            String kind = value.substring("workflow_confirmed:".length()).trim();
            advanceActiveWorkflow(true, "complete".equals(kind)
                    ? "确认完成当前任务" : "确认执行当前操作");
            return;
        }
        if (value.startsWith("workflow_capture_")
                || value.startsWith("workflow_voice_")
                || "workflow_ai_assist".equals(value)
                || "workflow_expert_call".equals(value)
                || value.startsWith("workflow_input:")) {
            setChatStatus("当前工作流能力尚未在本版本启用");
            return;
        }
        if (value.startsWith("set_agent:")) {
            String[] parts = value.split(":", 3);
            String agentId = parts.length > 1 ? parts[1] : "";
            boolean enabled = parts.length > 2 && "enabled".equals(parts[2]);
            boolean validState = parts.length > 2
                    && ("enabled".equals(parts[2]) || "disabled".equals(parts[2]));
            if (parts.length == 3 && validState
                    && operationDetailFactory.setAgentPackageAuthorized(agentId, enabled)) {
                boolean persisted = persistAgentPackageAuthorizations();
                showHudOperationDetail("agent_center", true);
                setChatStatus(persisted
                        ? (enabled ? "本机技能已启用" : "本机技能已停用")
                        : "技能状态已更新，但本机保存失败");
            } else {
                setChatStatus("未找到对应 Agent 技能包");
            }
            return;
        }
        if (value.startsWith("start_inspection:")) {
            String taskId = value.substring("start_inspection:".length());
            InspectionTaskDefinition definition = inspectionCatalog.find(taskId);
            if (definition == null) {
                setChatStatus("巡检任务不存在");
                return;
            }
            taskSessionManager.pauseActive();
            invalidateInspectionAiRequest();
            activeInspectionRun = InspectionRun.start(definition);
            persistChatProjects();
            showHudOperationDetail("inspection");
            setChatStatus("巡检已开始：" + definition.title());
            return;
        }
        if ("capture_inspection_photo".equals(value)) {
            if (activeInspectionRun == null || inspectionAiInFlight) {
                setChatStatus(inspectionAiInFlight ? "AI 正在识别当前巡检照片" : "请先选择巡检任务");
                return;
            }
            inspectionCapturePending = true;
            hideCapabilityCenter();
            enterCameraScreen("inspection-capture");
            return;
        }
        if ("confirm_inspection_normal".equals(value)) {
            confirmInspectionPoint(InspectionRun.Outcome.NORMAL);
            return;
        }
        if ("confirm_inspection_abnormal".equals(value)) {
            confirmInspectionPoint(InspectionRun.Outcome.ABNORMAL);
            return;
        }
        if ("cancel_inspection".equals(value)) {
            activeInspectionRun = null;
            invalidateInspectionAiRequest();
            persistChatProjects();
            showHudOperationDetail("inspection");
            setChatStatus("已退出巡检任务");
            return;
        }
        if (value.startsWith("complete_inspection:")) {
            String itemId = value.substring("complete_inspection:".length());
            if (!inspectionChecklist.complete(itemId)) {
                setChatStatus("该巡检项已完成或不存在");
            } else {
                setChatStatus("已记录本地巡检进度：" + inspectionChecklist.progressLabel());
            }
            showHudOperationDetail("inspection");
            return;
        }
        if ("capture_photo".equals(value)) {
            hideCapabilityCenter();
            activateHudTaskWorkspace();
            voiceEventStateMachine.beginPhotoCapture();
            enterCameraScreen("operation-capture");
            return;
        }
        if ("capture_video".equals(value)) {
            startSceneVideoCapture();
            return;
        }
        if ("start_diagnosis".equals(value)) {
            beginVoiceDiagnosisConversation();
            return;
        }
        if ("continue_task".equals(value)) {
            hideCapabilityCenter();
            restoreActiveTaskWorkspace();
            syncHudPresentation();
            return;
        }
        if (value.startsWith("resume_task:")) {
            resumeTaskWorkspace(value.substring("resume_task:".length()));
        }
    }

    private void openWorkflowTaskDetail(String assignmentId) {
        String id = assignmentId == null ? "" : assignmentId.trim();
        if (workflowExecutionCoordinator == null || id.isEmpty()) {
            setChatStatus("工单运行时不可用");
            showHudOperationDetail("tasks");
            return;
        }
        WorkflowExecutionCoordinator.TaskDetail detail =
                workflowExecutionCoordinator.detail(id);
        if (detail == null) {
            setChatStatus("工单不存在或访问权限已撤销");
            showHudOperationDetail("tasks");
            return;
        }
        activeWorkflowAssignmentId = id;
        showHudOperationDetail("workflow", workflowHudPresenter.taskDetail(detail), false);
        setChatStatus("已打开工单详情");
    }

    private void openWorkflowExecution(String assignmentId, boolean optionalConfirmed) {
        String id = assignmentId == null ? "" : assignmentId.trim();
        if (workflowExecutionCoordinator == null || id.isEmpty()) {
            setChatStatus("工单运行时不可用");
            return;
        }
        activeWorkflowAssignmentId = id;
        WorkflowExecutionCoordinator.OpenResult result = optionalConfirmed
                ? workflowExecutionCoordinator.startWorkflow(id)
                : workflowExecutionCoordinator.startOrResume(id);
        showHudOperationDetail(
                "workflow",
                workflowHudPresenter.openResult(id, result),
                false);
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.STARTED) {
            setChatStatus("工作流已开始");
        } else if (result.code() == WorkflowExecutionCoordinator.OpenCode.RESUMED) {
            setChatStatus("已恢复上次工作流步骤");
        } else if (result.code() == WorkflowExecutionCoordinator.OpenCode.NOT_READY) {
            setChatStatus("工作流仍在安全下发或校验中");
        } else if (result.code() == WorkflowExecutionCoordinator.OpenCode.COMPLETED) {
            setChatStatus("该工作流已完成");
        } else {
            setChatStatus("工作流未启动：" + result.code().name());
        }
    }

    private void dispatchWorkflowPhotoCapture() {
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        if (workflowCapabilityRegistry == null || snapshot == null) {
            setChatStatus("工作流照片能力不可用");
            return;
        }
        WorkflowRuntimeState state = snapshot.runtimeState();
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (state.executionId().isEmpty() || node == null
                || !"photo_capture".equals(node.type())) {
            setChatStatus("当前步骤不允许拍照");
            return;
        }
        WorkflowCapabilityRegistry.Request request;
        try {
            request = new WorkflowCapabilityRegistry.Request(
                    activeWorkflowAssignmentId,
                    state.executionId(),
                    state.stepAttempt(node.nodeId()) + 1,
                    new JSONObject());
        } catch (RuntimeException exception) {
            setChatStatus("工作流拍照请求无效");
            return;
        }
        WorkflowCapabilityRegistry.Dispatch dispatch = workflowCapabilityRegistry.dispatch(
                node,
                request,
                new WorkflowCapabilityRegistry.Callback() {
                    @Override
                    public void complete(final WorkflowCapabilityRegistry.Result result) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (result == null
                                        || result.status()
                                        == WorkflowCapabilityRegistry.Result.Status.FAILED) {
                                    setChatStatus("工作流拍照未完成");
                                }
                            }
                        });
                    }
                });
        if (dispatch != WorkflowCapabilityRegistry.Dispatch.STARTED) {
            setChatStatus("工作流照片能力未启用");
        }
    }

    private void beginWorkflowPhotoCapture(
            WorkflowCapabilityRegistry.Request request,
            WorkflowCapabilityRegistry.Callback callback
    ) {
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
        WorkflowPackage.Node node = state == null
                ? null : snapshot.workflowPackage().node(state.currentNodeId());
        if (request == null || callback == null || workflowCapturePending
                || state == null || node == null
                || !activeWorkflowAssignmentId.equals(request.assignmentId())
                || !state.executionId().equals(request.executionId())
                || !"photo_capture".equals(node.type())) {
            if (callback != null) {
                callback.complete(WorkflowCapabilityRegistry.Result.failed(
                        "workflow_photo_state_invalid"));
            }
            setChatStatus("当前工作流拍照状态无效");
            return;
        }
        workflowCapturePending = true;
        workflowPhotoCallback = callback;
        hideCapabilityCenter();
        enterCameraScreen("workflow-capture");
        setChatStatus("请拍摄当前工作流要求的照片");
    }

    private void dispatchWorkflowVideoCapture() {
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        if (workflowCapabilityRegistry == null || snapshot == null) {
            setChatStatus("工作流录像能力不可用");
            return;
        }
        WorkflowRuntimeState state = snapshot.runtimeState();
        WorkflowPackage.Node node = snapshot.workflowPackage().node(state.currentNodeId());
        if (state.executionId().isEmpty() || node == null
                || !"video_capture".equals(node.type())) {
            setChatStatus("当前步骤不允许录像");
            return;
        }
        WorkflowCapabilityRegistry.Request request;
        try {
            request = new WorkflowCapabilityRegistry.Request(
                    activeWorkflowAssignmentId,
                    state.executionId(),
                    state.stepAttempt(node.nodeId()) + 1,
                    new JSONObject());
        } catch (RuntimeException exception) {
            setChatStatus("工作流录像请求无效");
            return;
        }
        WorkflowCapabilityRegistry.Dispatch dispatch = workflowCapabilityRegistry.dispatch(
                node,
                request,
                new WorkflowCapabilityRegistry.Callback() {
                    @Override
                    public void complete(final WorkflowCapabilityRegistry.Result result) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (result == null
                                        || result.status()
                                        == WorkflowCapabilityRegistry.Result.Status.FAILED) {
                                    Log.w(KEY_LOG_TAG, "Workflow video capture did not complete");
                                }
                            }
                        });
                    }
                });
        if (dispatch != WorkflowCapabilityRegistry.Dispatch.STARTED) {
            setChatStatus("工作流录像能力未启用");
        }
    }

    private void beginWorkflowVideoCapture(
            WorkflowCapabilityRegistry.Request request,
            WorkflowCapabilityRegistry.Callback callback
    ) {
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
        WorkflowPackage.Node node = state == null
                ? null : snapshot.workflowPackage().node(state.currentNodeId());
        WorkflowVideoCapturePlan plan;
        try {
            plan = WorkflowVideoCapturePlan.from(
                    node, (int) (SCENE_VIDEO_MAX_DURATION_MS / 1000L));
        } catch (RuntimeException exception) {
            plan = null;
        }
        if (request == null || callback == null || workflowVideoCapturePlan != null
                || sceneVideoRecording || sceneVideoStarting || pendingSceneVideoCapture
                || state == null || node == null || plan == null
                || !activeWorkflowAssignmentId.equals(request.assignmentId())
                || !state.executionId().equals(request.executionId())) {
            if (callback != null) {
                callback.complete(WorkflowCapabilityRegistry.Result.failed(
                        "workflow_video_state_invalid"));
            }
            setChatStatus("当前工作流录像状态或时长配置无效");
            return;
        }
        workflowVideoCapturePlan = plan;
        workflowVideoCallback = callback;
        setChatStatus("请录制当前工作流要求的视频，最长 "
                + plan.maximumDurationSeconds() + " 秒");
        startSceneVideoCapture();
    }

    private void selectActiveWorkflowChoice(String action) {
        String[] parts = action == null ? new String[0] : action.split(":", 3);
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
        WorkflowPackage.Node node = state == null
                ? null : snapshot.workflowPackage().node(state.currentNodeId());
        if (parts.length != 3 || node == null || !"choice".equals(node.type())
                || !node.nodeId().equals(parts[1])) {
            setChatStatus("工作流选项已失效，请重新打开当前步骤");
            return;
        }
        String fieldKey = node.config().optString("fieldKey", "").trim();
        if (fieldKey.isEmpty()) {
            setChatStatus("工作流选项配置无效");
            return;
        }
        WorkflowStepContext context;
        try {
            context = new WorkflowStepContext().putField(fieldKey, parts[2]);
        } catch (RuntimeException exception) {
            setChatStatus("工作流选项值无效");
            return;
        }
        advanceActiveWorkflow(context, new JSONObject(),
                taskSyncPayload("selectedValue", parts[2]));
    }

    private void advanceActiveWorkflow(boolean confirmed, String confirmationPhrase) {
        WorkflowStepContext context = new WorkflowStepContext().setConfirmed(confirmed);
        if (confirmed) context.setConfirmationPhrase(confirmationPhrase);
        advanceActiveWorkflow(context, new JSONObject(), new JSONObject());
    }

    private void advanceActiveWorkflow(
            WorkflowStepContext context,
            JSONObject input,
            JSONObject output
    ) {
        if (workflowExecutionCoordinator == null || activeWorkflowAssignmentId.isEmpty()) {
            setChatStatus("当前没有可执行的工作流");
            return;
        }
        WorkflowExecutionCoordinator.ActionResult result =
                workflowExecutionCoordinator.advance(
                        activeWorkflowAssignmentId, context, input, output);
        showHudOperationDetail(
                "workflow",
                workflowHudPresenter.actionResult(activeWorkflowAssignmentId, result),
                false);
        if (result.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED) {
            setChatStatus(result.state() != null
                    && result.state().status() == WorkflowRuntimeState.Status.COMPLETED
                    ? "工作流已完成，项目记录已保留" : "已进入下一工作流步骤");
        } else if (result.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED) {
            setChatStatus("当前步骤尚未满足条件：" + result.reason());
        } else {
            setChatStatus("工作流操作未执行：" + result.reason());
        }
    }

    private void showWorkflowConfirmation(String kind, String title, String description) {
        OperationDetail detail = new OperationDetail(
                "二次确认",
                title,
                description,
                Arrays.asList("工单：" + activeWorkflowAssignmentId,
                        "只有人工确认后才会推进流程。"),
                "workflow_confirmed:" + kind,
                "确认执行",
                "workflow_back",
                "取消");
        showHudOperationDetail("workflow", detail, false);
        setChatStatus("等待人工二次确认");
    }

    private WorkflowSnapshot activeWorkflowSnapshot() {
        if (workflowSnapshotAccess == null || activeWorkflowAssignmentId.isEmpty()) return null;
        return workflowSnapshotAccess.load(activeWorkflowAssignmentId);
    }

    private void setEnvironmentAgentEnabled(boolean enabled) {
        if (!operationDetailFactory.setAgentPackageAuthorized("environment_ops", enabled)) {
            setChatStatus("未找到环境诊断技能");
            return;
        }
        boolean persisted = persistAgentPackageAuthorizations();
        if (isCapabilityCenterVisible()) {
            showHudOperationDetail("agent_center", true);
        }
        setChatStatus(persisted
                ? (enabled ? "环境诊断技能已启用" : "环境诊断技能已停用")
                : "环境诊断状态已更新，但本机保存失败");
    }

    private boolean persistAgentPackageAuthorizations() {
        return getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE).edit()
                .putString(AGENT_AUTHORIZATIONS_JSON,
                        operationDetailFactory.agentAuthorizationJson().toString())
                .commit();
    }

    private void renderCapabilityHub() {
        if (capabilityContent == null) {
            return;
        }
        capabilityDetailVisible = false;
        capabilityContent.removeAllViews();

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        capabilityContent.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("AI 能力中心");
        title.setTextColor(Color.rgb(4, 93, 72));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headingCopy.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("语音优先 · 视觉辅助");
        subtitle.setTextColor(Color.rgb(99, 126, 116));
        subtitle.setTextSize(13);
        headingCopy.addView(subtitle);

        TextView close = iconButton("×");
        close.setTextSize(28);
        close.setContentDescription("返回语音待命");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideCapabilityCenter();
            }
        });
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView status = new TextView(this);
        status.setText("● AI 诊断可用    ·    专家协同 " + collabServiceState.label()
                + "    ·    " + aiAbilityConfigs.size() + " 项现场能力已加载");
        status.setTextColor(Color.rgb(57, 112, 92));
        status.setTextSize(13);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        capabilityContent.addView(status, statusParams);

        for (int index = 0; index < aiAbilityConfigs.size(); index += 2) {
            AIAbilityConfig first = aiAbilityConfigs.get(index);
            AIAbilityConfig second = index + 1 < aiAbilityConfigs.size()
                    ? aiAbilityConfigs.get(index + 1)
                    : null;
            addCapabilityRow(first, second);
        }
    }

    private void addCapabilityRow(AIAbilityConfig first, AIAbilityConfig second) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        capabilityContent.addView(row, rowParams);
        row.addView(capabilityTile(first), capabilityTileParams(second == null, second != null));
        if (second != null) {
            row.addView(capabilityTile(second), capabilityTileParams(false, false));
        }
    }

    private LinearLayout.LayoutParams capabilityTileParams(boolean fullWidth, boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                dp(fullWidth ? 78 : 102),
                fullWidth ? 0f : 1f);
        if (hasRightMargin) {
            params.rightMargin = dp(10);
        }
        return params;
    }

    private View capabilityTile(final AIAbilityConfig ability) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(11), dp(14), dp(10));
        tile.setBackground(roundRect(Color.rgb(255, 255, 255), Color.rgb(201, 224, 215), 10));
        tile.setClickable(true);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAbility(ability);
            }
        });

        TextView iconText = new TextView(this);
        iconText.setText(ability.icon());
        iconText.setTextColor(Color.rgb(7, 139, 104));
        iconText.setTextSize(13);
        iconText.setTypeface(Typeface.DEFAULT_BOLD);
        tile.addView(iconText);

        TextView label = new TextView(this);
        label.setText(ability.title());
        label.setTextColor(Color.rgb(35, 77, 64));
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(4);
        tile.addView(label, labelParams);

        TextView detailText = new TextView(this);
        detailText.setText(abilityStatusLabel(ability) + " · " + ability.summary());
        detailText.setTextColor(Color.rgb(101, 128, 119));
        detailText.setTextSize(11);
        detailText.setMaxLines(2);
        tile.addView(detailText);
        return tile;
    }

    private void showCapabilityPage(AIAbilityConfig ability) {
        capabilityDetailVisible = true;
        capabilityContent.removeAllViews();
        TextView back = iconButton("‹");
        back.setTextSize(34);
        back.setContentDescription("返回 AI 能力中心");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renderCapabilityHub();
            }
        });
        capabilityContent.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView kicker = new TextView(this);
        kicker.setText(abilityStatusLabel(ability));
        kicker.setTextColor(Color.rgb(7, 139, 104));
        kicker.setTextSize(14);
        capabilityContent.addView(kicker);

        TextView pageTitle = new TextView(this);
        pageTitle.setText(ability.title());
        pageTitle.setTextColor(Color.rgb(4, 93, 72));
        pageTitle.setTextSize(32);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams pageTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pageTitleParams.topMargin = dp(8);
        capabilityContent.addView(pageTitle, pageTitleParams);

        TextView itemList = new TextView(this);
        itemList.setText(ability.pageDescription());
        itemList.setTextColor(Color.rgb(56, 85, 75));
        itemList.setTextSize(17);
        itemList.setLineSpacing(dp(12), 1f);
        itemList.setPadding(dp(16), dp(20), dp(16), dp(20));
        itemList.setBackground(roundRect(Color.argb(85, 255, 255, 255), Color.rgb(214, 228, 222), 8));
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        itemParams.topMargin = dp(24);
        capabilityContent.addView(itemList, itemParams);

        if (ability.route() == AIAbilityConfig.Route.SKILL_CENTER) {
            appendSkillCatalog();
        }
        if (ability.route() == AIAbilityConfig.Route.AGENT_CENTER) {
            appendAgentCatalog();
        }

        TextView command = new TextView(this);
        command.setText(ability.voiceCommand());
        command.setTextColor(Color.rgb(47, 95, 76));
        command.setTextSize(14);
        command.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams commandParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        commandParams.topMargin = dp(20);
        capabilityContent.addView(command, commandParams);
    }

    private void appendSkillCatalog() {
        TextView catalogTitle = new TextView(this);
        catalogTitle.setText("可扩展领域技能");
        catalogTitle.setTextColor(Color.rgb(4, 93, 72));
        catalogTitle.setTextSize(16);
        catalogTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(22);
        capabilityContent.addView(catalogTitle, titleParams);

        for (AISkillConfig skill : aiSkillConfigs) {
            TextView skillRow = new TextView(this);
            skillRow.setText(skill.title() + "  ·  " + skill.status().label() + "\n" + skill.summary());
            skillRow.setTextColor(Color.rgb(56, 85, 75));
            skillRow.setTextSize(14);
            skillRow.setLineSpacing(dp(3), 1f);
            skillRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            skillRow.setBackground(roundRect(Color.argb(80, 255, 255, 255), Color.rgb(214, 228, 222), 8));
            LinearLayout.LayoutParams skillParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            skillParams.topMargin = dp(8);
            capabilityContent.addView(skillRow, skillParams);
        }
    }

    private void appendAgentCatalog() {
        TextView catalogTitle = new TextView(this);
        catalogTitle.setText("待接入智能体");
        catalogTitle.setTextColor(Color.rgb(4, 93, 72));
        catalogTitle.setTextSize(16);
        catalogTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(22);
        capabilityContent.addView(catalogTitle, titleParams);

        for (AIAgentConfig agent : aiAgentConfigs) {
            TextView agentRow = new TextView(this);
            agentRow.setText(agent.title() + "  ·  " + agent.status().label()
                    + "\n" + agent.summary()
                    + "\n可调用 Skill：" + joinAgentSkillIds(agent.skillIds()));
            agentRow.setTextColor(Color.rgb(56, 85, 75));
            agentRow.setTextSize(14);
            agentRow.setLineSpacing(dp(3), 1f);
            agentRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            agentRow.setBackground(roundRect(Color.argb(58, 255, 255, 255), Color.rgb(202, 224, 215), 8));
            LinearLayout.LayoutParams agentParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            agentParams.topMargin = dp(8);
            capabilityContent.addView(agentRow, agentParams);
        }
    }

    private String joinAgentSkillIds(List<String> skillIds) {
        StringBuilder labels = new StringBuilder();
        for (String skillId : skillIds) {
            for (AISkillConfig skill : aiSkillConfigs) {
                if (skill.id().equals(skillId)) {
                    if (labels.length() > 0) {
                        labels.append("、");
                    }
                    labels.append(skill.title());
                    break;
                }
            }
        }
        return labels.length() == 0 ? "待配置" : labels.toString();
    }

    private void openAbilityById(String id) {
        for (AIAbilityConfig ability : aiAbilityConfigs) {
            if (ability.id().equals(id)) {
                openAbility(ability);
                return;
            }
        }
        throw new IllegalArgumentException("unknown AI ability id: " + id);
    }

    private void openAbility(AIAbilityConfig ability) {
        if (hudPresentation != null) {
            if (ability.route() == AIAbilityConfig.Route.DIAGNOSIS) {
                hideCapabilityCenter();
                renderChatScreen();
                return;
            }
            if (ability.route() == AIAbilityConfig.Route.EXPERT_COLLAB) {
                hideCapabilityCenter();
                featureRegistry.require("expert_collab").enter(MainActivity.this);
                return;
            }
            showHudAbility(ability.id());
            return;
        }
        if (ability.route() == AIAbilityConfig.Route.DIAGNOSIS) {
            hideCapabilityCenter();
            renderChatScreen();
            return;
        }
        if (ability.route() == AIAbilityConfig.Route.EXPERT_COLLAB) {
            hideCapabilityCenter();
            featureRegistry.require("expert_collab").enter(MainActivity.this);
            return;
        }
        showCapabilityPage(ability);
    }

    private TextView iconButton(String value) {
        TextView button = new TextView(this);
        button.setText(value);
        button.setTextSize(25);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(48, 64, 60));
        button.setBackground(roundRect(Color.TRANSPARENT, Color.TRANSPARENT, 6));
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
        if (isCapabilityCenterVisible()) {
            context = capabilityDetailVisible ? "当前：AI 能力模块" : "当前：AI 能力中心";
            commands = "开始诊断  进入已上线的 AI 诊断\n巡检 / 现场感知 / 设备 / 知识 / 技能中心 / 任务中心\n专家  直入专家视频协同\n\n返回 / 取消  返回上一层\n拍照  直接记录现场并进入图片分析";
        } else if (screenMode == ScreenMode.CAMERA) {
            context = "当前：现场拍摄";
            commands = "拍照  立即拍摄现场\n重拍  放弃当前照片后重拍\n使用照片 / 确认  带回 AI 对话\n返回 / 不拍了  退出相机\n\n通用\n专家  呼叫在线专家\n语音命令  再次查看本页";
        } else if (screenMode == ScreenMode.EXPERT) {
            context = "当前：专家协同";
            commands = "等待接听时：返回 / 取消  结束呼叫并返回 AI\n通话已接通后：使用底部挂断或 F10 结束通话\n\n通用\n语音命令  再次查看本页";
        } else {
            context = "当前：AI 智能运维指导";
            commands = "开始诊断  进入 AI 诊断\n拍照  拍摄现场，随后直接说问题\n专家  呼叫在线专家协同\n巡检 / 现场感知 / 设备 / 知识 / 技能中心 / 智能体中心 / 任务中心\n\n拍摄后\n重拍  重新取景\n使用照片 / 确认  带图提问\n补充 / 重说  继续输入问题";
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
        view.setTextSize(16);
        view.setTextColor(Color.rgb(43, 62, 57));
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setBackground(roundRect(Color.rgb(247, 251, 250), Color.rgb(226, 236, 232), 6));
        view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        return view;
    }

    private boolean isProjectRailVisible() {
        return projectRail != null && projectRail.getVisibility() == View.VISIBLE;
    }

    private void setChatStatus(String status) {
        chatStatus = status == null || status.trim().length() == 0 ? "在线" : status.trim();
        updateHeaderStatus();
    }

    private void updateHeaderStatus() {
        if (stateText != null) {
            String visibleStatus = !hasRecordAudioPermission()
                    ? "麦克风未开启"
                    : (recordingVoice ? "语音识别中" : chatStatus);
            stateText.setText("● Air3 · " + visibleStatus);
            stateText.setVisibility(View.VISIBLE);
        }
        syncHudPresentation();
    }

    /** Keeps the local effect draft in lockstep with the real voice and AI request lifecycle. */
    private void syncHudPresentation() {
        if (hudPresentation == null || screenMode != ScreenMode.CHAT) {
            return;
        }
        hudPresentation.setCollabStatus(collabServiceState.label());
        String guideState = activeHudGuideState(hudVoiceGuideVisible, hudGlassesGuideVisible);
        if (!guideState.isEmpty()) {
            if ("voiceGuide".equals(guideState)) {
                hudPresentation.setVoiceGuideContext(voiceGuideContext());
            }
            hudPresentation.showState(guideState);
            return;
        }
        if (hudCapabilityVisible) {
            return;
        }
        MaintenanceTask task = currentMaintenanceTask();
        String transcript = composerTranscript == null ? "" : composerTranscript.trim();
        boolean transcriptListening = recordingVoice
                || voiceStreamState == VoiceStreamState.LISTENING
                || voiceStreamState == VoiceStreamState.PARTIAL_READY;
        hudPresentation.setTranscript(hudTranscriptLabel(transcript, transcriptListening));
        boolean hasImage = composerImageBytes != null;
        boolean waitingForDescription = voiceEventStateMachine.state()
                == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION;
        hudPresentation.setInputStatus(photoEvidenceStatus(hasImage, waitingForDescription),
                recordingVoice ? "正在识别" : "等待语音输入");
        hudPresentation.setTaskEvidence(latestTaskEvidenceText(transcript), latestTaskImagePreview());
        hudPresentation.setTaskDetectionMarkers(latestTaskDetectionMarkers());
        hudPresentation.setTaskVoiceState(taskVoiceState(), taskVoiceLabel());

        if (!hasRecordAudioPermission()) {
            hudPresentation.setVoicePermissionError("麦克风权限未开启。请在系统设置中开启后使用语音助手。");
            hudPresentation.showState("error");
            return;
        }

        int responseIndex = latestAssistantResponseIndexForHudTask();
        ChatMessage response = responseIndex >= 0 ? chatMessages.get(responseIndex) : null;
        int completedResponseCount = completedAssistantResponseCountForHudTask();
        if (hudTaskProgress == HudTaskProgress.GUIDANCE) {
            int step = task == null ? hudGuidanceStep : task.currentRepairStepNumber();
            int total = task == null ? 3 : task.repairStepCount();
            String action = task == null ? "请确认当前部件状态。" : task.currentRepairStep();
            hudPresentation.setGuidanceStep(step, total, action);
            hudPresentation.showState(guidanceHudState());
            return;
        }
        if (hudTaskProgress == HudTaskProgress.COMPLETED) {
            hudPresentation.showState("complete");
            return;
        }
        if (recoverableAiError.length() > 0) {
            hudPresentation.setError(recoverableAiError);
            hudPresentation.showState("error");
            return;
        }
        if (waitingForDescription && completedResponseCount == 0) {
            hudPresentation.showState("photoDraft");
            return;
        }
        // Full-screen listening and analysis belong only to first-turn collection. Once a task
        // reply exists, voice capture, paging and follow-up AI calls stay inside this workspace.
        int persistedAiTurnCount = task == null ? 0 : task.aiTurnCount();
        if (shouldKeepEstablishedTaskSurface(hudTaskWorkspaceActive, completedResponseCount,
                persistedAiTurnCount, hudTaskProgress != HudTaskProgress.NONE)) {
            if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
                String draft = chatMessages.get(streamingAssistantIndex).text.trim();
                hudPresentation.setConversationPage(draft.length() == 0
                        ? "AI 正在分析本轮照片与描述，请稍候。"
                        : draft, 1, 1);
            } else if (task != null) {
                int pageIndex = task.conversationPageIndex(HUD_CONVERSATION_PAGE_SIZE);
                hudPresentation.setConversationPage(
                        task.conversationPage(pageIndex, HUD_CONVERSATION_PAGE_SIZE),
                        pageIndex + 1, task.conversationPageCount(HUD_CONVERSATION_PAGE_SIZE));
            } else {
                hudPresentation.setConversation(response == null ? "正在等待 AI 回复" : response.text.trim());
            }
            hudPresentation.showState("conversation");
            return;
        }
        if (completedResponseCount == 0 && (recordingVoice || voiceStreamState == VoiceStreamState.LISTENING
                || voiceStreamState == VoiceStreamState.PARTIAL_READY)) {
            hudPresentation.showState("listening");
            return;
        }
        if (completedResponseCount == 0
                && (voiceStreamState == VoiceStreamState.AI_PENDING || streamingAssistantIndex >= 0)) {
            hudPresentation.setAnalysisInput(transcript.length() == 0 ? "正在整理现场输入" : transcript);
            hudPresentation.showState("analysis");
            return;
        }
        if (shouldShowHudTaskWorkspace(hudTaskWorkspaceActive, responseIndex, hudTaskMessageStartIndex)) {
            if (completedResponseCount > 1 || !isActionableDiagnosisResponse(response.text)) {
                hudPresentation.setConversation(response.text.trim());
                hudPresentation.showState("conversation");
                return;
            }
            if (task != null) {
                int pageSize = HUD_DIAGNOSIS_PAGE_SIZE;
                hudPresentation.setResponsePage(task.diagnosisTitle(),
                        task.responsePage(task.diagnosisPageIndex(), pageSize),
                        task.diagnosisPageIndex() + 1, task.responsePageCount(pageSize), task.confidence());
            } else {
                hudPresentation.setResponse("AI 诊断结果", response.text.trim());
            }
            hudPresentation.showState("diagnosis");
            return;
        }
        hudPresentation.showState("standby");
    }

    static String hudTranscriptLabel(String transcript, boolean listening) {
        String value = transcript == null ? "" : transcript.trim();
        if (value.length() > 0) {
            return value;
        }
        return listening ? "正在识别现场描述" : "在线 · 当前任务上下文已保留";
    }

    static boolean shouldShowHudTaskWorkspace(boolean taskWorkspaceActive, int responseIndex,
            int taskMessageStartIndex) {
        return taskWorkspaceActive && responseIndex >= taskMessageStartIndex && responseIndex >= 0;
    }

    static boolean shouldRemainInTaskConversation(int completedResponseCount, boolean interactionInFlight) {
        return completedResponseCount > 0;
    }

    static boolean shouldKeepEstablishedTaskSurface(boolean taskWorkspaceActive,
            int completedResponseCount, boolean guidedTaskActive) {
        return taskWorkspaceActive && (completedResponseCount > 0 || guidedTaskActive);
    }

    static boolean shouldKeepEstablishedTaskSurface(boolean taskWorkspaceActive,
            int completedResponseCount, int persistedAiTurnCount, boolean guidedTaskActive) {
        return taskWorkspaceActive
                && (completedResponseCount > 0 || persistedAiTurnCount > 0 || guidedTaskActive);
    }

    static int taskMessageStartIndexAfterProjectReset(boolean taskWorkspaceActive,
            int currentProjectMessageCount) {
        return taskWorkspaceActive ? Math.max(0, currentProjectMessageCount) : 0;
    }

    static int taskMessageStartIndexAfterProjectSwitch(boolean resumedTask, int firstUserIndex,
            int currentProjectMessageCount) {
        if (!resumedTask) {
            return Math.max(0, currentProjectMessageCount);
        }
        return Math.max(0, firstUserIndex);
    }

    static int taskMessageStartIndexOnActivation(int currentMessageCount,
            int liveTranscriptIndex) {
        if (liveTranscriptIndex >= 0 && liveTranscriptIndex < currentMessageCount) {
            return liveTranscriptIndex;
        }
        return Math.max(0, currentMessageCount);
    }

    static boolean shouldClearComposerForProjectTransition(boolean preservePendingInput) {
        return !preservePendingInput;
    }

    static boolean shouldLeaveGuidanceAfterAiReply(boolean guidanceActive, String response) {
        return guidanceActive && response != null && response.trim().length() > 0;
    }

    static boolean shouldAcceptOfflineWakeDetection(long nowMs, long suppressedUntilMs) {
        return nowMs >= suppressedUntilMs;
    }

    static boolean shouldStartGuidance(boolean taskWorkspaceActive, int completedResponseCount,
            int repairStepCount) {
        return taskWorkspaceActive && completedResponseCount > 0 && repairStepCount > 0;
    }

    static String guidanceHudState() {
        return "conversation";
    }

    static String photoEvidenceStatus(boolean hasImage, boolean waitingForDescription) {
        if (!hasImage) {
            return "未添加现场照片";
        }
        return waitingForDescription ? "照片待发送" : "照片已加入本轮";
    }

    static String photoDescriptionPrompt() {
        return "如需补充描述，请说“小叮当”唤醒，再说出现场问题；照片将等待 30 秒，超时无输入将自动仅发送图片";
    }

    static boolean shouldRefreshPhotoDraftAfterCapture(
            VoiceEventStateMachine.Signal signal) {
        return signal == VoiceEventStateMachine.Signal.START_DESCRIPTION;
    }

    static String sceneVideoReturnTarget(boolean capabilityVisible, boolean detailVisible,
            String operationId, boolean taskWorkspace) {
        String safeOperationId = operationId == null ? "" : operationId.trim();
        if (capabilityVisible && detailVisible && safeOperationId.length() > 0) {
            return "operation:" + safeOperationId;
        }
        if (capabilityVisible) {
            return "capabilities";
        }
        if (taskWorkspace) {
            return "task";
        }
        return "standby";
    }

    static boolean shouldFinishSceneVideoBeforeCameraExit(boolean recording, boolean starting,
            boolean pending) {
        return recording || starting || pending;
    }

    static boolean isCurrentCameraCallback(long expectedGeneration, long currentGeneration,
            boolean resourceAvailable) {
        return expectedGeneration == currentGeneration && resourceAvailable;
    }

    static boolean isCurrentCameraSession(long expectedGeneration, long currentGeneration,
            boolean deviceMatches) {
        return expectedGeneration == currentGeneration && deviceMatches;
    }

    static boolean shouldPostVideoStopToMainThread(boolean alreadyOnMainThread) {
        return !alreadyOnMainThread;
    }

    static boolean shouldDeferPhotoDraftAutoSubmit(boolean recording, boolean listening,
            boolean partialReady, String transcript) {
        return recording || listening || partialReady
                || (transcript != null && transcript.trim().length() > 0);
    }

    private void activateHudTaskWorkspace() {
        if (!hudTaskWorkspaceActive) {
            hudTaskMessageStartIndex = taskMessageStartIndexOnActivation(
                    chatMessages.size(), liveTranscriptMessageIndex);
        }
        hudTaskWorkspaceActive = true;
    }

    private int latestAssistantResponseIndexForHudTask() {
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = chatMessages.size() - 1; i >= start; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("assistant".equals(message.role) && "text".equals(message.kind)
                    && !message.streaming && message.text != null && message.text.trim().length() > 0) {
                return i;
            }
        }
        return -1;
    }

    private int completedAssistantResponseCountForHudTask() {
        int count = 0;
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = start; i < chatMessages.size(); i++) {
            ChatMessage message = chatMessages.get(i);
            if ("assistant".equals(message.role) && "text".equals(message.kind)
                    && !message.streaming && message.text != null && message.text.trim().length() > 0) {
                count++;
            }
        }
        return count;
    }

    private String latestTaskEvidenceText(String liveTranscript) {
        String currentNarration = sanitizeTaskNarration(liveTranscript);
        if (currentNarration.length() > 0) {
            return currentNarration;
        }
        if (composerImageBytes != null
                && voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
            return "照片待发送，请补充现场描述";
        }
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = chatMessages.size() - 1; i >= start; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("user".equals(message.role) && "text".equals(message.kind)
                    && message.text != null && message.text.trim().length() > 0) {
                String storedNarration = sanitizeTaskNarration(message.text);
                if (storedNarration.length() > 0) {
                    return storedNarration;
                }
            }
        }
        return "等待现场语音或文字描述";
    }

    private String latestTaskImagePreview() {
        if (composerImagePreviewBase64 != null && composerImagePreviewBase64.length() > 0) {
            return composerImagePreviewBase64;
        }
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = chatMessages.size() - 1; i >= start; i--) {
            ChatMessage message = chatMessages.get(i);
            if (!isTaskHudDisplayImage(message.role, message.kind, message.imageId)) {
                continue;
            }
            if (message.imagePreviewBase64 != null && message.imagePreviewBase64.length() > 0) {
                return message.imagePreviewBase64;
            }
            if (SceneReferenceGuide.isReferenceImageId(message.imageId)) {
                return sceneReferencePreviewBase64(message.imageId);
            }
        }
        return "";
    }

    private String latestTaskDetectionMarkers() {
        if (composerImagePreviewBase64 != null && composerImagePreviewBase64.length() > 0) {
            return "[]";
        }
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = chatMessages.size() - 1; i >= start; i--) {
            ChatMessage message = chatMessages.get(i);
            if (!isTaskHudDisplayImage(message.role, message.kind, message.imageId)) {
                continue;
            }
            if (SceneReferenceGuide.isReferenceImageId(message.imageId)) {
                return "[]";
            }
            if (message.detectionMarkersJson != null
                    && message.detectionMarkersJson.length() > 0) {
                return message.detectionMarkersJson;
            }
            return "[]";
        }
        return "[]";
    }

    private String taskVoiceState() {
        if (recordingVoice || voiceStreamState == VoiceStreamState.LISTENING
                || voiceStreamState == VoiceStreamState.PARTIAL_READY) {
            return "listening";
        }
        if (voiceStreamState == VoiceStreamState.AI_PENDING || streamingAssistantIndex >= 0) {
            return "thinking";
        }
        return "idle";
    }

    private String taskVoiceLabel() {
        if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
            return "说“小叮当”补充描述，或说“仅发送图片”";
        }
        String state = taskVoiceState();
        if ("listening".equals(state)) {
            return "正在聆听，本轮转写已显示在上方";
        }
        if ("thinking".equals(state)) {
            boolean writing = streamingAssistantIndex >= 0
                    && streamingAssistantIndex < chatMessages.size()
                    && chatMessages.get(streamingAssistantIndex).text != null
                    && chatMessages.get(streamingAssistantIndex).text.trim().length() > 0;
            return writing ? "AI 正在生成本轮指导" : "AI 正在分析本轮照片与描述";
        }
        return "说“小叮当”继续补充，或轻触呼吸图标";
    }

    private void returnToHudStandby(String status) {
        invalidateInspectionAiRequest();
        cancelActiveGptRequestForNavigation();
        cancelVoiceEventDescriptionTimeout();
        voiceEventStateMachine.reset();
        pendingVoicePhotoCapture = false;
        clearComposerImage();
        taskSessionManager.pauseActive();
        clearHudTaskProgress();
        hudVoiceGuideVisible = false;
        hudGlassesGuideVisible = false;
        hudCapabilityVisible = false;
        capabilityDetailVisible = false;
        hudTaskWorkspaceActive = false;
        requireNewTaskOnNextInput = true;
        hudTaskMessageStartIndex = chatMessages.size();
        recoverableAiError = "";
        composerTranscript = "";
        voiceStreamState = VoiceStreamState.IDLE;
        persistChatProjects();
        if (hudPresentation != null && screenMode == ScreenMode.CHAT) {
            hudPresentation.showState("standby");
            setChatStatus(status);
            return;
        }
        setChatStatus(status);
        renderChatScreen();
    }

    private void startHudGuidance() {
        hudCapabilityVisible = false;
        MaintenanceTask task = currentMaintenanceTask();
        int repairStepCount = task == null ? 0 : task.repairStepCount();
        if (!shouldStartGuidance(hudTaskWorkspaceActive, completedAssistantResponseCountForHudTask(),
                repairStepCount)) {
            setChatStatus("请先完成 AI 诊断，生成维修步骤后再开始维修");
            syncHudPresentation();
            return;
        }
        activateHudTaskWorkspace();
        hudTaskProgress = HudTaskProgress.GUIDANCE;
        hudGuidanceStep = task.currentRepairStepNumber();
        hudPresentation.setGuidanceStep(hudGuidanceStep, task.repairStepCount(), task.currentRepairStep());
        hudPresentation.showState(guidanceHudState());
    }

    private void clearHudTaskProgress() {
        hudTaskProgress = HudTaskProgress.NONE;
        hudGuidanceStep = 1;
    }

    private void retryAiWithVoice() {
        recoverableAiError = "";
        composerImageUploadFailed = false;
        sendAfterImageUpload = false;
        composerTranscript = "";
        voiceStreamState = VoiceStreamState.IDLE;
        setChatStatus("请重新描述现场问题");
        renderChatScreen();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recordingVoice && screenMode == ScreenMode.CHAT) {
                    startToggleVoiceRecording();
                }
            }
        }, 180L);
    }

    private boolean handleHudGuidanceVoiceCommand(VoiceCommandRouter.Command command) {
        MaintenanceTask task = currentMaintenanceTask();
        boolean persistedGuidanceTask = task != null
                && task.phase() == MaintenanceTask.Phase.GUIDANCE
                && task.repairStepCount() > 0;
        if (!shouldHandleGuidanceVoiceCommand(
                hudTaskProgress == HudTaskProgress.GUIDANCE, persistedGuidanceTask, command)) {
            return false;
        }
        if (command == VoiceCommandRouter.Command.NEXT_PAGE
                || command == VoiceCommandRouter.Command.PREVIOUS_PAGE) {
            // Repair guidance is one step per screen. A paging phrase must not escape to the
            // conversation pager or transient listening view.
            setChatStatus("维修指导请说“下一步”继续当前操作");
            syncHudPresentation();
            return true;
        }
        if (command == VoiceCommandRouter.Command.NEXT) {
            hudTaskProgress = HudTaskProgress.GUIDANCE;
            boolean advanced = task != null ? task.advanceRepairStep() : hudGuidanceStep < 3;
            if (advanced) {
                if (task == null) {
                    hudGuidanceStep++;
                } else {
                    hudGuidanceStep = task.currentRepairStepNumber();
                }
                hudPresentation.setGuidanceStep(hudGuidanceStep, task == null ? 3 : task.repairStepCount(),
                        task == null ? "继续下一项检查" : task.currentRepairStep());
                setChatStatus("维修步骤 " + hudGuidanceStep + " / "
                        + (task == null ? 3 : task.repairStepCount()));
            } else {
                hudTaskProgress = HudTaskProgress.COMPLETED;
                if (task != null) {
                    task.complete();
                    taskSessionManager.completeActive();
                }
                hudPresentation.showState("complete");
                setChatStatus("本次维修指导完成");
            }
            if (task != null) {
                persistChatProjects();
                TaskSession session = taskSessionManager.active();
                if (advanced) {
                    recordTaskSyncEvent(session, "step_changed",
                            taskSyncPayload(
                                    "stepNumber", task.currentRepairStepNumber(),
                                    "stepCount", task.repairStepCount(),
                                    "phase", task.phase().name()),
                            session.id() + ":step_changed:" + task.currentRepairStepNumber());
                } else {
                    recordTaskSyncEvent(session, "task_completed",
                            taskSyncPayload(
                                    "stepNumber", task.currentRepairStepNumber(),
                                    "stepCount", task.repairStepCount(),
                                    "phase", task.phase().name()),
                            session.id() + ":task_completed");
                }
            }
            return true;
        }
        if (command == VoiceCommandRouter.Command.FINISH) {
            hudTaskProgress = HudTaskProgress.COMPLETED;
            if (task != null) {
                task.complete();
                taskSessionManager.completeActive();
                persistChatProjects();
                TaskSession session = taskSessionManager.active();
                recordTaskSyncEvent(session, "task_completed",
                        taskSyncPayload(
                                "stepNumber", task.currentRepairStepNumber(),
                                "stepCount", task.repairStepCount(),
                                "phase", task.phase().name()),
                        session.id() + ":task_completed");
            }
            hudPresentation.showState("complete");
            setChatStatus("本次维修指导完成");
            return true;
        }
        if (command == VoiceCommandRouter.Command.ABNORMAL) {
            hudTaskProgress = HudTaskProgress.GUIDANCE;
            setChatStatus("请描述当前步骤的异常情况");
            voiceSessionPurpose = VoiceSessionPurpose.COMMAND;
            startToggleVoiceRecording();
            return true;
        }
        if (command == VoiceCommandRouter.Command.RETRY) {
            clearHudTaskProgress();
            setChatStatus("请补充现场情况后重新分析");
            startToggleVoiceRecording();
            return true;
        }
        return false;
    }

    static boolean shouldHandleGuidanceVoiceCommand(boolean guidanceSurface,
            boolean persistedGuidanceTask, VoiceCommandRouter.Command command) {
        if (guidanceSurface) {
            return true;
        }
        if (!persistedGuidanceTask || command == null) {
            return false;
        }
        return command == VoiceCommandRouter.Command.NEXT
                || command == VoiceCommandRouter.Command.FINISH
                || command == VoiceCommandRouter.Command.ABNORMAL
                || command == VoiceCommandRouter.Command.RETRY;
    }

    private void changeDiagnosisPage(boolean next) {
        MaintenanceTask task = currentMaintenanceTask();
        if (task == null) {
            setChatStatus("暂无可翻页的诊断结果");
            return;
        }
        boolean changed = next ? task.nextDiagnosisPage(HUD_DIAGNOSIS_PAGE_SIZE) : task.previousDiagnosisPage();
        if (changed) {
            setChatStatus(next ? "诊断内容下一页" : "诊断内容上一页");
        } else {
            setChatStatus(next ? "已是最后一页" : "已是第一页");
        }
        syncHudPresentation();
    }

    private void changeConversationPage(boolean next) {
        MaintenanceTask task = currentMaintenanceTask();
        if (task == null) {
            setChatStatus("暂无可翻页的任务对话");
            return;
        }
        boolean changed = next ? task.nextConversationPage(HUD_CONVERSATION_PAGE_SIZE)
                : task.previousConversationPage(HUD_CONVERSATION_PAGE_SIZE);
        setChatStatus(changed ? (next ? "对话内容下一页" : "对话内容上一页")
                : (next ? "已是最新一页" : "已是最早一页"));
        syncHudPresentation();
    }

    private void submitImageOnly() {
        if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
            submitVoiceEvent(voiceEventStateMachine.onCommand(VoiceCommandRouter.Command.IMAGE_ONLY));
            return;
        }
        if (composerImageBytes == null || composerImageBytes.length == 0) {
            setChatStatus("当前没有待发送的现场照片");
            syncHudPresentation();
            return;
        }
        composerTranscript = "请仅结合这张现场照片识别设备状态、异常区域和需要补拍的位置。";
        voiceStreamState = VoiceStreamState.AI_PENDING;
        sendComposerToAi();
    }

    private void navigateHudBack() {
        if (hudVoiceGuideVisible || hudGlassesGuideVisible) {
            returnToHudStandby("语音待命");
            return;
        }
        if (isCapabilityCenterVisible()) {
            if (capabilityDetailVisible) {
                capabilityDetailVisible = false;
                hudPresentation.showState("capabilities");
            } else {
                hideCapabilityCenter();
            }
            return;
        }
        if (screenMode == ScreenMode.CAMERA) {
            returnToChatFromCameraFlow();
            return;
        }
        if (hudTaskProgress == HudTaskProgress.GUIDANCE || hudTaskProgress == HudTaskProgress.COMPLETED) {
            clearHudTaskProgress();
            hudTaskWorkspaceActive = true;
            setChatStatus("已返回任务对话");
            syncHudPresentation();
            return;
        }
        if (hudTaskWorkspaceActive) {
            returnToHudStandby("语音待命");
            return;
        }
        renderChatScreen();
    }

    private void openHudVoiceGuide() {
        if (hudPresentation == null) {
            showCommandOverlay();
            return;
        }
        String guideContext = voiceGuideContext();
        setProjectRailVisible(false);
        hudCapabilityVisible = false;
        capabilityDetailVisible = false;
        hudVoiceGuideVisible = true;
        hudGlassesGuideVisible = false;
        hudPresentation.setVoiceGuideContext(guideContext);
        hudPresentation.showState("voiceGuide");
        scheduleForegroundVoiceListening("voice-guide");
    }

    private void openHudGlassesTutorial() {
        if (hudPresentation == null) {
            showCommandOverlay();
            return;
        }
        setProjectRailVisible(false);
        hudCapabilityVisible = false;
        capabilityDetailVisible = false;
        hudVoiceGuideVisible = false;
        hudGlassesGuideVisible = true;
        hudPresentation.setTutorialPage(1);
        hudPresentation.showState("glassesGuide");
        scheduleForegroundVoiceListening("glasses-guide");
    }

    private String voiceGuideContext() {
        return resolveVoiceGuideContext(activeInspectionRun != null,
                screenMode == ScreenMode.EXPERT, hudTaskWorkspaceActive, hudCapabilityVisible);
    }

    static String resolveVoiceGuideContext(boolean inspectionActive, boolean expertVisible,
            boolean taskVisible, boolean capabilityVisible) {
        if (expertVisible) return "expert";
        if (taskVisible) return "task";
        if (capabilityVisible) return inspectionActive ? "inspection" : "capabilities";
        return "home";
    }

    static String activeHudGuideState(boolean voiceGuideVisible, boolean glassesGuideVisible) {
        if (voiceGuideVisible) return "voiceGuide";
        if (glassesGuideVisible) return "glassesGuide";
        return "";
    }

    private void restartHudTask() {
        invalidateInspectionAiRequest();
        cancelVoiceEventDescriptionTimeout();
        stopVoiceRecording(false, "restart_task");
        closeCamera();
        stopCameraThread();
        createNewProjectChat();
        ChatProject project = activeProject();
        if (project != null) {
            startNewTaskForActiveProject("等待现场问题");
            requireNewTaskOnNextInput = false;
        }
        clearHudTaskProgress();
        hudTaskWorkspaceActive = false;
        hudTaskMessageStartIndex = chatMessages.size();
        recoverableAiError = "";
        setChatStatus("已创建新的维修任务");
        renderChatScreen();
        scheduleForegroundVoiceListening("restart-task");
    }

    private MaintenanceTask currentMaintenanceTask() {
        TaskSession session = taskSessionManager.active();
        return session == null ? null : session.maintenanceTask();
    }

    private MaintenanceTask ensureMaintenanceTask(String problem) {
        ChatProject project = activeProject();
        if (project == null) {
            return null;
        }
        TaskSession activeSession = taskSessionManager.active();
        if (!requireNewTaskOnNextInput
                && activeSession != null && project.id.equals(activeSession.projectId())) {
            return activeSession.maintenanceTask();
        }
        if (!requireNewTaskOnNextInput
                && hudTaskWorkspaceActive && taskSessionManager.resumeProject(project.id)) {
            return taskSessionManager.active().maintenanceTask();
        }
        if (activeSession != null) {
            taskSessionManager.pauseActive();
        }
        if (taskSessionManager.findProject(project.id) != null || projectHasUserInput(project)) {
            createNewProjectChat(true);
            project = activeProject();
            hudTaskMessageStartIndex = taskMessageStartIndexAfterProjectReset(
                    hudTaskWorkspaceActive, chatMessages.size());
        }
        TaskSession session = startNewTaskForActiveProject(problem);
        requireNewTaskOnNextInput = false;
        return session == null ? null : session.maintenanceTask();
    }

    private void restoreActiveTaskWorkspace() {
        TaskSession session = taskSessionManager.active();
        if (session == null) return;
        hudTaskWorkspaceActive = true;
        requireNewTaskOnNextInput = false;
        hudTaskMessageStartIndex = firstUserMessageIndex(chatMessages);
        clearHudTaskProgress();
        if (session.maintenanceTask().phase() == MaintenanceTask.Phase.GUIDANCE) {
            hudTaskProgress = HudTaskProgress.GUIDANCE;
        } else if (session.maintenanceTask().phase() == MaintenanceTask.Phase.COMPLETED) {
            hudTaskProgress = HudTaskProgress.COMPLETED;
        }
    }

    private void resumeTaskWorkspace(String taskId) {
        TaskSession session = taskSessionManager.find(taskId);
        if (session == null || !taskSessionManager.resume(taskId)) {
            setChatStatus("维修任务不存在或已完成");
            return;
        }
        cancelActiveGptRequestForNavigation();
        saveCurrentProjectFromMessages();
        for (int i = 0; i < chatProjects.size(); i++) {
            if (chatProjects.get(i).id.equals(session.projectId())) {
                currentProjectIndex = i;
                break;
            }
        }
        loadCurrentProjectMessages();
        restoreActiveTaskWorkspace();
        hideCapabilityCenter();
        persistChatProjects();
        setChatStatus("已恢复维修任务");
        syncHudPresentation();
    }

    private static int firstUserMessageIndex(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role)) return i;
        }
        return messages.size();
    }

    private boolean projectHasUserInput(ChatProject project) {
        if (project == null) {
            return false;
        }
        for (ChatMessage message : project.messages) {
            if ("user".equals(message.role)) {
                return true;
            }
        }
        return false;
    }

    private ChatMessage latestAssistantResponse() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("assistant".equals(message.role) && "text".equals(message.kind)
                    && !message.streaming && message.text != null && message.text.trim().length() > 0) {
                return message;
            }
        }
        return null;
    }

    private boolean hasRecordAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStartupPermissions() {
        if (!hasRecordAudioPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
    }

    private void requestCameraPermissionIfNeeded() {
        if (!shouldRequestCameraPermission(screenMode == ScreenMode.CAMERA)) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else if (screenMode == ScreenMode.CAMERA) {
            startCameraFlow();
        }
    }

    static boolean shouldRequestCameraPermission(boolean cameraScreen) {
        return cameraScreen;
    }

    private void openVoicePermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private String abilityStatusLabel(AIAbilityConfig ability) {
        if (ability.route() == AIAbilityConfig.Route.EXPERT_COLLAB) {
            return collabServiceState.label();
        }
        return ability.status().label();
    }

    private void refreshCollabServiceHealth(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (collabHealthCheckInFlight || (!force && now - lastCollabHealthCheckAtMs < 20_000L)) {
            return;
        }
        collabHealthCheckInFlight = true;
        lastCollabHealthCheckAtMs = now;
        collabServiceState = CollabServiceHealth.State.CHECKING;
        new Thread(new Runnable() {
            @Override
            public void run() {
                CollabServiceHealth.State result = CollabServiceHealth.State.UNAVAILABLE;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(
                            CollabServiceHealth.healthUrl(BuildConfig.COLLAB_SERVER_URL)).openConnection();
                    connection.setConnectTimeout(3500);
                    connection.setReadTimeout(3500);
                    connection.setRequestMethod("GET");
                    result = CollabServiceHealth.fromHttpStatus(connection.getResponseCode());
                } catch (Exception ignored) {
                    result = CollabServiceHealth.State.UNAVAILABLE;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
                final CollabServiceHealth.State finalResult = result;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        collabHealthCheckInFlight = false;
                        collabServiceState = finalResult;
                        if (hudPresentation != null) {
                            hudPresentation.setCollabStatus(collabServiceState.label());
                        }
                        if (isCapabilityCenterVisible() && !capabilityDetailVisible) {
                            renderCapabilityHub();
                        }
                    }
                });
            }
        }, "CollabHealth").start();
    }

    private void setProjectRailVisible(boolean visible) {
        if (projectRail == null || projectRailParams == null) {
            return;
        }
        projectRailParams.width = visible ? dp(340) : 0;
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
                || (text.contains("小叮当，拍照") && text.contains("直接说明问题"))
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
                .putString(TASK_SESSIONS_JSON, taskSessionManager.toJson().toString())
                .putString(INSPECTION_RUN_JSON,
                        activeInspectionRun == null ? "" : activeInspectionRun.toJson().toString())
                .putInt(CURRENT_PROJECT_INDEX, currentProjectIndex)
                .apply();
    }

    private void restoreInspectionRun() {
        String saved = getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE)
                .getString(INSPECTION_RUN_JSON, "");
        if (saved == null || saved.trim().length() == 0) {
            activeInspectionRun = null;
            return;
        }
        try {
            activeInspectionRun = InspectionRun.fromJson(new JSONObject(saved), inspectionCatalog);
        } catch (Exception exception) {
            Log.w(KEY_LOG_TAG, "Unable to restore inspection run", exception);
            activeInspectionRun = null;
        }
    }

    private void restoreTaskSessions() {
        String saved = getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE)
                .getString(TASK_SESSIONS_JSON, "");
        if (saved == null || saved.trim().length() == 0) {
            taskSessionManager = new TaskSessionManager();
            return;
        }
        try {
            taskSessionManager = TaskSessionManager.fromJson(new JSONObject(saved));
            // App launch always lands on the home HUD. Resuming old work must be explicit.
            taskSessionManager.pauseActive();
        } catch (Exception exception) {
            Log.w(KEY_LOG_TAG, "Unable to restore task sessions", exception);
            taskSessionManager = new TaskSessionManager();
        }
    }

    private void restoreAgentAuthorizations() {
        String saved = getSharedPreferences(CHAT_PROJECT_PREFS, MODE_PRIVATE)
                .getString(AGENT_AUTHORIZATIONS_JSON, "");
        if (saved == null || saved.trim().length() == 0) return;
        try {
            operationDetailFactory.restoreAgentAuthorizations(new JSONObject(saved));
        } catch (Exception exception) {
            Log.w(KEY_LOG_TAG, "Unable to restore Agent authorizations", exception);
        }
    }

    private void createNewProjectChat() {
        createNewProjectChat(false);
    }

    private void createNewProjectChat(boolean preservePendingInput) {
        cancelActiveGptRequestForNavigation();
        taskSessionManager.pauseActive();
        requireNewTaskOnNextInput = true;
        saveCurrentProjectFromMessages();
        ChatProject project = new ChatProject(
                "project-" + System.currentTimeMillis(),
                "现场诊断 " + (chatProjects.size() + 1),
                System.currentTimeMillis());
        chatProjects.add(0, project);
        currentProjectIndex = 0;
        if (shouldClearComposerForProjectTransition(preservePendingInput)) {
            clearComposerImage();
            composerTranscript = "";
        }
        lastDirectAiContextImage = null;
        liveTranscriptMessageIndex = -1;
        loadCurrentProjectMessages();
        persistChatProjects();
        scrollChatToBottom = true;
        renderChatScreen();
    }

    private void switchProjectChat(int index) {
        if (index < 0 || index >= chatProjects.size()) {
            return;
        }
        if (index == currentProjectIndex) {
            ChatProject selected = activeProject();
            if (selected != null && taskSessionManager.resumeProject(selected.id)) {
                restoreActiveTaskWorkspace();
                persistChatProjects();
                scrollChatToBottom = true;
                renderChatScreen();
            }
            return;
        }
        cancelActiveGptRequestForNavigation();
        saveCurrentProjectFromMessages();
        currentProjectIndex = index;
        clearComposerImage();
        lastDirectAiContextImage = null;
        composerTranscript = "";
        streamingAssistantIndex = -1;
        liveTranscriptMessageIndex = -1;
        loadCurrentProjectMessages();
        ChatProject selected = activeProject();
        boolean resumedTask = selected != null && taskSessionManager.resumeProject(selected.id);
        hudTaskMessageStartIndex = taskMessageStartIndexAfterProjectSwitch(
                resumedTask, firstUserMessageIndex(chatMessages), chatMessages.size());
        hudTaskWorkspaceActive = resumedTask;
        requireNewTaskOnNextInput = !resumedTask;
        clearHudTaskProgress();
        if (resumedTask) {
            MaintenanceTask task = currentMaintenanceTask();
            if (task != null && task.phase() == MaintenanceTask.Phase.GUIDANCE) {
                hudTaskProgress = HudTaskProgress.GUIDANCE;
            } else if (task != null && task.phase() == MaintenanceTask.Phase.COMPLETED) {
                hudTaskProgress = HudTaskProgress.COMPLETED;
            }
        }
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
        if (modeController == null || modeController.mode() == IntegratedModeController.Mode.EXPERT) {
            return;
        }
        invalidateInspectionAiRequest();
        cancelActiveGptRequestForNavigation();
        composerImageGeneration++;
        sendAfterImageUpload = false;
        voiceStreamState = VoiceStreamState.IDLE;
        TaskSession activeSession = taskSessionManager.active();
        taskIdBeforeExpert = activeSession == null ? "" : activeSession.id();
        MaintenanceTask task = currentMaintenanceTask();
        taskSnapshotBeforeExpert = task == null ? null : task.snapshot();
        modeController.enterExpert();
    }

    private void exitExpertMode() {
        if (modeController != null) {
            modeController.exitExpert();
        }
        if (taskIdBeforeExpert.length() > 0) {
            taskSessionManager.resume(taskIdBeforeExpert);
        }
        MaintenanceTask task = currentMaintenanceTask();
        if (task != null && taskSnapshotBeforeExpert != null) {
            task.restore(taskSnapshotBeforeExpert);
        }
        taskSnapshotBeforeExpert = null;
        taskIdBeforeExpert = "";
        persistChatProjects();
        syncHudPresentation();
    }

    private void showExpertLayer() {
        screenMode = ScreenMode.EXPERT;
        chatLayer.setVisibility(View.GONE);
        if (hudLayer != null) {
            hudLayer.setVisibility(View.GONE);
        }
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
                applyImmersiveSystemUi();
                expertLayer.setVisibility(View.GONE);
                chatLayer.setVisibility(hudPresentation == null ? View.VISIBLE : View.GONE);
                if (hudLayer != null) {
                    hudLayer.setVisibility(View.VISIBLE);
                }
                hideCapabilityCenter();
                cameraOverlay.setVisibility(View.GONE);
                cameraBackButton.setVisibility(View.GONE);
                previewView.setVisibility(View.GONE);
                titleText.setText("叮当");
                updateHeaderStatus();
                renderProjectList();
                renderMessages();
                renderComposer();
                syncHudPresentation();
            }
        });
    }

    private void renderCameraScreen() {
        screenMode = ScreenMode.CAMERA;
        resetCameraPreviewStability();
        if (modeController != null) {
            modeController.setLegacyMode(IntegratedModeController.Mode.CAMERA);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyImmersiveSystemUi();
                expertLayer.setVisibility(View.GONE);
                if (hudLayer != null) {
                    hudLayer.setVisibility(View.GONE);
                }
                chatLayer.setVisibility(View.GONE);
                previewView.setAlpha(0f);
                previewView.setVisibility(View.VISIBLE);
                cameraOverlay.setVisibility(View.VISIBLE);
                cameraBackButton.setVisibility(View.VISIBLE);
                boolean hasPhoto = composerImageBytes != null;
                cameraStatusText.setText(sceneVideoRecording
                        ? "正在记录现场短视频，确认键可停止"
                        : hasPhoto
                        ? "已拍摄 · 说“使用照片”继续"
                        : "相机准备中");
                cameraCaptureButton.setText(sceneVideoRecording ? "■" : hasPhoto ? "✓" : "●");
                cameraCaptureButton.setContentDescription(sceneVideoRecording
                        ? "停止现场短视频"
                        : hasPhoto ? "使用当前现场照片" : "拍摄现场照片");
            }
        });
        if (cameraDevice == null) {
            requestCameraPermissionIfNeeded();
        }
        scheduleForegroundVoiceListening("camera-screen");
    }

    private void renderMessages() {
        chatMessagesColumn.removeAllViews();
        chatMessagesColumn.addView(assistantHomeHeader());
        View taskStateHud = buildTaskStateHud();
        if (taskStateHud != null) {
            LinearLayout.LayoutParams taskStateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            taskStateParams.topMargin = dp(14);
            taskStateParams.bottomMargin = dp(8);
            chatMessagesColumn.addView(taskStateHud, taskStateParams);
        }
        boolean hasUserConversation = hasUserConversation();
        for (ChatMessage message : chatMessages) {
            if (!hasUserConversation && "assistant".equals(message.role)) {
                continue;
            }
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
        syncHudPresentation();
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
        if (!hasUserConversation()) {
            return buildVoiceFirstHomeHeader();
        }
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!hasUserConversation()) {
            View spacer = new View(this);
            header.addView(spacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));

            TextView logo = new TextView(this);
            logo.setText("D");
            logo.setTextColor(Color.rgb(12, 139, 104));
            logo.setTextSize(30);
            logo.setTypeface(Typeface.DEFAULT_BOLD);
            logo.setGravity(Gravity.CENTER);
            logo.setBackground(roundRect(Color.rgb(232, 247, 240), Color.rgb(196, 227, 214), 12));
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(62), dp(62));
            logoParams.bottomMargin = dp(20);
            header.addView(logo, logoParams);

            TextView hello = new TextView(this);
            hello.setText("叮当，今天要处理什么问题？");
            hello.setTextColor(Color.rgb(29, 44, 41));
            hello.setTextSize(27);
            hello.setTypeface(Typeface.DEFAULT_BOLD);
            hello.setGravity(Gravity.CENTER);
            header.addView(hello, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(this);
            hint.setText("直接说出现场情况，或拍照后补充问题描述");
            hint.setTextColor(Color.rgb(111, 128, 123));
            hint.setTextSize(16);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = dp(12);
            header.addView(hint, hintParams);

            LinearLayout.LayoutParams askBarParams = new LinearLayout.LayoutParams(
                    dp(880), dp(72));
            askBarParams.topMargin = dp(30);
            header.addView(buildHomeAskBar(), askBarParams);

            LinearLayout quickActions = new LinearLayout(this);
            quickActions.setGravity(Gravity.CENTER);
            quickActions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            quickParams.topMargin = dp(12);
            header.addView(quickActions, quickParams);

            TextView photoAction = homeQuickAction("现场拍摄");
            photoAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    enterCameraScreen("home-quick-photo");
                }
            });
            quickActions.addView(photoAction);

            TextView expertAction = homeQuickAction("呼叫专家");
            expertAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    featureRegistry.require("expert_collab").enter(MainActivity.this);
                }
            });
            quickActions.addView(expertAction);

            TextView commandAction = homeQuickAction("语音命令");
            commandAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showCommandOverlay();
                }
            });
            quickActions.addView(commandAction);
        } else {
            LinearLayout brandRow = new LinearLayout(this);
            brandRow.setGravity(Gravity.CENTER_VERTICAL);
            brandRow.setPadding(0, 0, 0, dp(16));

            TextView logo = new TextView(this);
            logo.setText("D");
            logo.setTextColor(Color.WHITE);
            logo.setTextSize(16);
            logo.setTypeface(Typeface.DEFAULT_BOLD);
            logo.setGravity(Gravity.CENTER);
            logo.setBackground(roundRect(Color.rgb(12, 139, 104), Color.TRANSPARENT, 7));
            brandRow.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));

            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            copyParams.leftMargin = dp(10);
            brandRow.addView(copy, copyParams);

            TextView title = new TextView(this);
            title.setText("叮当 AI 运维助手");
            title.setTextColor(Color.rgb(28, 50, 44));
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            copy.addView(title);

            TextView subtitle = new TextView(this);
            subtitle.setText("本次现场诊断");
            subtitle.setTextColor(Color.rgb(111, 128, 123));
            subtitle.setTextSize(12);
            copy.addView(subtitle);
            header.addView(brandRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return header;
    }

    /** Shows only live task state derived from the current voice and AI request lifecycle. */
    private View buildTaskStateHud() {
        boolean analyzing = voiceStreamState == VoiceStreamState.AI_PENDING || streamingAssistantIndex >= 0;
        if (!recordingVoice && !analyzing) {
            return null;
        }
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(dp(18), dp(14), dp(18), dp(14));
        hud.setBackground(roundRect(Color.rgb(236, 247, 242), Color.rgb(183, 218, 204), 12));

        TextView title = new TextView(this);
        title.setText(recordingVoice ? "● 正在聆听" : "● AI 分析中");
        title.setTextColor(Color.rgb(4, 100, 76));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        hud.addView(title);

        TextView details = new TextView(this);
        details.setText(recordingVoice ? listeningHudDetail() : analysisHudDetail());
        details.setTextColor(Color.rgb(70, 104, 93));
        details.setTextSize(13);
        details.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(7);
        hud.addView(details, detailParams);
        return hud;
    }

    private String listeningHudDetail() {
        String transcript = composerTranscript == null ? "" : composerTranscript.trim();
        return "语音输入 · 实时转写中\n" + (transcript.length() == 0 ? "等待现场描述" : transcript);
    }

    private String analysisHudDetail() {
        String imageStatus = composerImageBytes == null ? "未附现场图片" : "现场图片已附加";
        String transcript = composerTranscript == null ? "" : composerTranscript.trim();
        String voiceStatus = transcript.length() == 0 ? "未附语音描述" : "语音描述已识别";
        return "✓ " + imageStatus + "\n✓ " + voiceStatus + "\n● 正在生成诊断建议";
    }

    private View buildVoiceFirstHomeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));

        TextView state = new TextView(this);
        boolean voicePermissionGranted = hasRecordAudioPermission();
        state.setText(voicePermissionGranted ? "● 语音待命" : "麦克风权限未开启");
        state.setTextColor(voicePermissionGranted ? Color.rgb(4, 93, 72) : Color.rgb(156, 94, 30));
        state.setTextSize(21);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setGravity(Gravity.CENTER);
        header.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText(voicePermissionGranted ? "请描述设备异常" : "开启语音权限后使用 AI 语音助手");
        hint.setTextColor(Color.rgb(81, 108, 99));
        hint.setTextSize(18);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(12);
        header.addView(hint, hintParams);

        TextView mic = new TextView(this);
        mic.setText("●");
        mic.setTextColor(voicePermissionGranted ? Color.rgb(7, 139, 104) : Color.rgb(177, 129, 63));
        mic.setTextSize(45);
        mic.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        micParams.topMargin = dp(18);
        header.addView(mic, micParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(14);
        header.addView(actions, actionsParams);

        TextView photoAction = homeQuickAction("现场拍摄");
        photoAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterCameraScreen("voice-first-home-photo");
            }
        });
        actions.addView(photoAction);

        TextView expertAction = homeQuickAction("呼叫专家");
        expertAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                featureRegistry.require("expert_collab").enter(MainActivity.this);
            }
        });
        actions.addView(expertAction);

        TextView commandAction = homeQuickAction("语音命令");
        commandAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCommandOverlay();
            }
        });
        actions.addView(commandAction);

        TextView voiceAction = homeQuickAction("语音提问");
        voiceAction.setContentDescription("语音提问");
        voiceAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (hasRecordAudioPermission()) {
                    startToggleVoiceRecording();
                } else {
                    openVoicePermissionSettings();
                }
            }
        });
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voiceParams.topMargin = dp(10);
        header.addView(voiceAction, voiceParams);
        if (!voicePermissionGranted) {
            TextView permissionAction = homeQuickAction("打开语音权限");
            permissionAction.setTextColor(Color.rgb(4, 106, 79));
            permissionAction.setTypeface(Typeface.DEFAULT_BOLD);
            permissionAction.setContentDescription("打开麦克风权限设置");
            permissionAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openVoicePermissionSettings();
                }
            });
            LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            permissionParams.topMargin = dp(12);
            header.addView(permissionAction, permissionParams);
        }
        return header;
    }

    private boolean hasUserConversation() {
        for (ChatMessage message : chatMessages) {
            if ("user".equals(message.role)) {
                return true;
            }
        }
        return false;
    }

    private View buildHomeAskBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(10), dp(7));
        bar.setBackground(roundRect(Color.WHITE, Color.rgb(215, 228, 224), 10));

        TextView add = new TextView(this);
        add.setText("+");
        add.setTextColor(Color.rgb(52, 71, 65));
        add.setTextSize(30);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("现场拍摄");
        add.setClickable(true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterCameraScreen("home-askbar-photo");
            }
        });
        bar.addView(add, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView prompt = new TextView(this);
        prompt.setText(hasRecordAudioPermission()
                ? "说“小叮当”后，直接开始描述"
                : "请先开启麦克风权限");
        prompt.setTextColor(Color.rgb(130, 144, 139));
        prompt.setTextSize(17);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(prompt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView mic = new TextView(this);
        mic.setText("🎙");
        mic.setTextColor(Color.WHITE);
        mic.setTextSize(20);
        mic.setGravity(Gravity.CENTER);
        mic.setContentDescription("语音提问");
        mic.setBackground(roundRect(hasRecordAudioPermission() ? Color.rgb(12, 139, 104) : Color.rgb(165, 120, 54), Color.TRANSPARENT, 28));
        mic.setClickable(true);
        mic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (hasRecordAudioPermission()) {
                    startToggleVoiceRecording();
                } else {
                    openVoicePermissionSettings();
                }
            }
        });
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        bar.addView(mic, micParams);
        return bar;
    }

    private TextView homeQuickAction(String text) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(Color.rgb(100, 120, 113));
        action.setTextSize(14);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), dp(9), dp(16), dp(9));
        action.setClickable(true);
        action.setDefaultFocusHighlightEnabled(false);
        return action;
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
        povFooter.setText(latestSnapshot == null ? "说“小叮当，拍照”开始采集" : "已同步到本次诊断");
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
        return hasUserConversation()
                || composerImageBytes != null
                || recordingVoice
                || hasLiveTranscriptMessage()
                || streamingAssistantIndex >= 0
                || (composerTranscript != null
                && composerTranscript.trim().length() > 0
                && !"语音提问".equals(composerTranscript.trim()));
    }

    private TextView assistantHomeAction(String label, boolean primary) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextColor(primary ? Color.WHITE : Color.rgb(51, 66, 63));
        action.setTextSize(18);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(18), 0, dp(18), 0);
        action.setBackground(primary
                ? roundRect(Color.rgb(12, 139, 104), Color.rgb(12, 139, 104), 7)
                : roundRect(Color.WHITE, Color.rgb(220, 231, 227), 7));
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
        boolean user = "user".equals(message.role);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(16), dp(14), dp(16), dp(14));
        bubble.setBackground(roundRect(
                user ? Color.rgb(246, 252, 249) : Color.WHITE,
                user ? Color.rgb(210, 230, 220) : Color.rgb(220, 231, 227), 7));

        TextView label = new TextView(this);
        label.setText(user ? "现场输入" : "叮当 AI 诊断助手");
        label.setTextColor(user ? Color.rgb(73, 109, 96) : Color.rgb(12, 116, 87));
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(message.text + (message.streaming ? "▌" : ""));
        body.setTextColor(Color.rgb(39, 53, 49));
        body.setTextSize(18);
        body.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(8);
        bubble.addView(body, bodyParams);
        return wrapMessageBubble(bubble, false);
    }

    private View imageMessageBubble(ChatMessage message) {
        boolean referenceGuide = SceneReferenceGuide.isReferenceImageId(message.imageId);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(12), dp(12), dp(12));
        bubble.setBackground(roundRect(Color.WHITE, Color.rgb(210, 230, 220), 7));

        TextView label = new TextView(this);
        label.setText(referenceGuide ? "叮当 AI · 检查点参考示意" : "现场输入 · 图片");
        label.setTextColor(referenceGuide ? Color.rgb(12, 116, 87) : Color.rgb(73, 109, 96));
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = dp(8);
        bubble.addView(label, labelParams);

        Bitmap preview = resolveChatImageBitmap(message);
        if (preview != null) {
            ImageView imageView = new ImageView(this);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(false);
            imageView.setContentDescription(referenceGuide ? "检查点参考示意" : "现场照片");
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
        return wrapMessageBubble(bubble, false);
    }

    private Bitmap resolveChatImageBitmap(ChatMessage message) {
        if (message == null) {
            return null;
        }
        if (message.imagePreviewBitmap == null && message.imagePreviewBase64.length() > 0) {
            message.imagePreviewBitmap = decodeImagePreviewBitmap(message.imagePreviewBase64);
        }
        if (message.imagePreviewBitmap != null
                || !SceneReferenceGuide.isReferenceImageId(message.imageId)) {
            return message.imagePreviewBitmap;
        }
        String assetPath = SceneReferenceGuide.assetPath(message.imageId);
        try (InputStream stream = getAssets().open(assetPath)) {
            message.imagePreviewBitmap = BitmapFactory.decodeStream(stream);
        } catch (IOException error) {
            Log.w(KEY_LOG_TAG, "Unable to load scene reference asset " + assetPath, error);
        }
        return message.imagePreviewBitmap;
    }

    private View wrapMessageBubble(View bubble, boolean user) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.LEFT);
        wrapper.setPadding(0, dp(6), 0, dp(6));
        int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.78f);
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
            attachmentPreviewImage.setVisibility(View.VISIBLE);
            if (composerImagePreviewBitmap == null && composerImagePreviewBase64.length() > 0) {
                composerImagePreviewBitmap = decodeImagePreviewBitmap(composerImagePreviewBase64);
            }
            attachmentPreviewImage.setImageBitmap(composerImagePreviewBitmap);
            attachmentPreviewText.setVisibility(View.VISIBLE);
            if (composerImageUploadFailed) {
                attachmentPreviewText.setText("照片上传失败，请检查后端或重新拍照");
            } else if (voiceEventStateMachine.state()
                    == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
                attachmentPreviewText.setText("照片待发送 · " + photoDescriptionPrompt());
            } else if ("local-photo".equals(composerImageId) || composerImageId.length() > 0) {
                attachmentPreviewText.setText("照片已加入本轮");
            } else {
                attachmentPreviewText.setText("照片待发送");
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
            voiceButton.setText("\u25A0");
            voiceButton.setContentDescription("结束提问");
            voiceButton.setTextSize(21);
            voiceButton.setTextColor(Color.WHITE);
            voiceButton.setBackground(roundRect(Color.rgb(18, 133, 96), Color.rgb(18, 133, 96), 24));
        } else {
            voiceWaveView.stop();
            voiceWaveView.setVisibility(View.GONE);
            if (composerTranscript.trim().length() == 0 || "语音提问".equals(composerTranscript.trim())) {
                transcriptDraftText.setVisibility(View.VISIBLE);
                transcriptDraftText.setText("小叮当待命中 · 说“小叮当”后直接开始描述");
            } else {
                transcriptDraftText.setVisibility(View.VISIBLE);
                transcriptDraftText.setText(composerTranscript);
            }
            transcriptDraftText.setTextColor(Color.rgb(120, 120, 120));
            voiceButton.setText("\uD83C\uDFA4");
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
        if (screenMode == ScreenMode.CAMERA && cameraDevice != null && captureSession != null
                && imageReader != null && !captureInFlight && cameraPreviewStable) {
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
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (jpegBytes != null) {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, bounds);
        }
        Log.i(KEY_LOG_TAG, "Photo captured bytes=" + (jpegBytes == null ? 0 : jpegBytes.length)
                + " dimensions=" + bounds.outWidth + "x" + bounds.outHeight);
        if (inspectionCapturePending && activeInspectionRun != null) {
            inspectionCapturePending = false;
            handleInspectionPhoto(jpegBytes);
            return;
        }
        if (workflowCapturePending) {
            handleWorkflowPhoto(jpegBytes);
            return;
        }
        composerImageGeneration++;
        composerImageBytes = jpegBytes;
        composerImageId = "";
        composerImagePreviewBase64 = createImagePreviewBase64(jpegBytes);
        composerImagePreviewBitmap = decodeImagePreviewBitmap(composerImagePreviewBase64);
        composerImageUploadFailed = false;
        MaintenanceTask task = ensureMaintenanceTask("请结合现场照片分析设备异常");
        activateHudTaskWorkspace();
        TaskSession session = taskSessionManager.active();
        File localPhoto;
        try {
            String reference = (session == null ? "task" : session.id())
                    + "-photo-" + System.currentTimeMillis();
            localPhoto = writeEvidenceFile(new File(getFilesDir(), "task-evidence"),
                    reference, jpegBytes);
        } catch (IOException exception) {
            Log.w(KEY_LOG_TAG, "Unable to persist task photo", exception);
            clearComposerImage();
            closeCamera();
            stopCameraThread();
            voiceStreamState = VoiceStreamState.IDLE;
            setChatStatus("照片保存失败，请重新拍摄");
            renderChatScreen();
            scheduleForegroundVoiceListening("photo-persist-failed");
            return;
        }
        if (task != null && session != null) {
            task.addEvidence("现场照片 " + (task.evidenceReferences().size() + 1),
                    "local-photo:" + localPhoto.getName());
            persistChatProjects();
            recordTaskSyncEvent(session, "photo_captured",
                    taskSyncPayload(
                            "localFileName", localPhoto.getName(),
                            "byteCount", localPhoto.length(),
                            "storageState", "local_saved"),
                    session.id() + ":photo_captured:" + localPhoto.getName());
        }
        showComposerAttachment("待发送 · " + photoDescriptionPrompt());
        // The evidence is already in memory. Release Camera2 before returning to the HUD so the
        // hidden preview cannot keep the glasses camera and thermal budget occupied.
        closeCamera();
        stopCameraThread();
        renderChatScreen();
        voiceStreamState = VoiceStreamState.IDLE;
        if (VOICE_WORKFLOW_ENABLED) {
            // Touch, hardware, and voice captures all follow the same evidence contract: keep
            // the photo visible and collect its spoken description before sending it to AI.
            if (voiceEventStateMachine.state() != VoiceEventStateMachine.State.CAPTURE_REQUESTED) {
                voiceEventStateMachine.beginPhotoCapture();
            }
            VoiceEventStateMachine.Signal signal = voiceEventStateMachine.onPhotoCaptured();
            if (shouldRefreshPhotoDraftAfterCapture(signal)) {
                beginVoiceEventDescription();
                syncHudPresentation();
                return;
            }
        }
        scheduleForegroundVoiceListening("photo-captured");
    }

    private void handleWorkflowPhoto(byte[] jpegBytes) {
        workflowCapturePending = false;
        WorkflowCapabilityRegistry.Callback callback = workflowPhotoCallback;
        workflowPhotoCallback = null;
        closeCamera();
        stopCameraThread();
        renderChatScreen();

        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
        WorkflowPackage.Node node = state == null
                ? null : snapshot.workflowPackage().node(state.currentNodeId());
        if (jpegBytes == null || jpegBytes.length == 0 || node == null
                || !"photo_capture".equals(node.type())
                || workflowExecutionCoordinator == null) {
            completeWorkflowPhotoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed("workflow_photo_invalid"));
            setChatStatus("工作流照片无效，请重新拍摄");
            openWorkflowExecution(activeWorkflowAssignmentId, false);
            return;
        }

        String localEvidenceId = "workflow-photo-" + UUID.randomUUID();
        File localPhoto;
        try {
            String reference = activeWorkflowAssignmentId + "-" + node.nodeId()
                    + "-" + System.currentTimeMillis();
            localPhoto = writeEvidenceFile(
                    new File(getFilesDir(), "task-evidence"), reference, jpegBytes);
        } catch (IOException exception) {
            Log.w(KEY_LOG_TAG, "Unable to persist workflow photo", exception);
            completeWorkflowPhotoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_photo_persist_failed"));
            setChatStatus("工作流照片保存失败，请重新拍摄");
            openWorkflowExecution(activeWorkflowAssignmentId, false);
            return;
        }

        String evidenceKey = node.config().optString("evidenceKey", node.nodeId()).trim();
        WorkflowEvidenceReference evidence;
        try {
            evidence = new WorkflowEvidenceReference(
                    localEvidenceId,
                    node.nodeId(),
                    evidenceKey.isEmpty() ? node.nodeId() : evidenceKey,
                    WorkflowStepContext.EvidenceType.PHOTO,
                    "task-evidence/" + localPhoto.getName(),
                    "",
                    0);
        } catch (RuntimeException exception) {
            if (!localPhoto.delete()) {
                Log.w(KEY_LOG_TAG, "Unable to remove invalid workflow photo "
                        + localPhoto.getName());
            }
            completeWorkflowPhotoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_photo_reference_invalid"));
            setChatStatus("工作流照片引用无效，请重新拍摄");
            openWorkflowExecution(activeWorkflowAssignmentId, false);
            return;
        }

        WorkflowExecutionCoordinator.ActionResult recorded =
                workflowExecutionCoordinator.recordEvidence(
                        activeWorkflowAssignmentId, evidence);
        if (recorded.code() != WorkflowExecutionCoordinator.ActionCode.RECORDED) {
            if (!localPhoto.delete()) {
                Log.w(KEY_LOG_TAG, "Unable to remove rejected workflow photo "
                        + localPhoto.getName());
            }
            completeWorkflowPhotoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_photo_record_failed"));
            showHudOperationDetail(
                    "workflow",
                    workflowHudPresenter.actionResult(activeWorkflowAssignmentId, recorded),
                    false);
            setChatStatus("工作流照片未登记：" + recorded.reason());
            return;
        }

        int capturedCount = 0;
        for (WorkflowEvidenceReference item : recorded.state().evidenceReferences()) {
            if (node.nodeId().equals(item.nodeId())
                    && item.type() == WorkflowStepContext.EvidenceType.PHOTO) {
                capturedCount++;
            }
        }
        JSONObject output = taskSyncPayload("capturedCount", capturedCount);
        WorkflowExecutionCoordinator.ActionResult advanced =
                workflowExecutionCoordinator.advance(
                        activeWorkflowAssignmentId,
                        new WorkflowStepContext(),
                        new JSONObject(),
                        output);
        completeWorkflowPhotoCallback(
                callback,
                advanced.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED
                        || advanced.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED
                        ? WorkflowCapabilityRegistry.Result.completed(output)
                        : WorkflowCapabilityRegistry.Result.failed(
                                "workflow_photo_advance_failed"));
        showHudOperationDetail(
                "workflow",
                workflowHudPresenter.actionResult(activeWorkflowAssignmentId, advanced),
                false);
        if (workflowEvidenceUploadCoordinator != null) {
            workflowEvidenceUploadCoordinator.request();
        }
        if (advanced.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED) {
            setChatStatus("照片已本地保存，等待安全上传");
        } else if (advanced.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED) {
            setChatStatus("照片已保存，请继续补全当前步骤");
        } else {
            setChatStatus("照片已保存，但工作流未推进：" + advanced.reason());
        }
    }

    private static void completeWorkflowPhotoCallback(
            WorkflowCapabilityRegistry.Callback callback,
            WorkflowCapabilityRegistry.Result result
    ) {
        if (callback != null) callback.complete(result);
    }

    private void handleInspectionPhoto(final byte[] jpegBytes) {
        closeCamera();
        stopCameraThread();
        renderChatScreen();
        if (jpegBytes == null || jpegBytes.length == 0 || activeInspectionRun == null) {
            setChatStatus("巡检照片无效，请重新拍摄");
            showHudOperationDetail("inspection");
            return;
        }
        File photoFile;
        try {
            String reference = activeInspectionRun.definition().id() + "-"
                    + activeInspectionRun.currentPoint().id() + "-" + System.currentTimeMillis();
            photoFile = writeEvidenceFile(new File(getFilesDir(), "inspection-evidence"),
                    reference, jpegBytes);
        } catch (IOException exception) {
            Log.w(KEY_LOG_TAG, "Unable to persist inspection photo", exception);
            setChatStatus("巡检照片保存失败，请重新拍摄");
            showHudOperationDetail("inspection");
            return;
        }
        activeInspectionRun.attachPhoto(photoFile.getAbsolutePath());
        final InspectionRun expectedRun = activeInspectionRun;
        final String expectedPointId = expectedRun.currentPoint().id();
        final long expectedGeneration = ++inspectionRequestGeneration;
        inspectionAiInFlight = true;
        persistChatProjects();
        showHudOperationDetail("inspection");
        setChatStatus("AI 正在识别当前巡检点位");
        if (DIRECT_GPT_ENABLED || backendChatClient == null) {
            sendInspectionPhotoToAi(jpegBytes, "local-photo", expectedRun,
                    expectedPointId, expectedGeneration);
            return;
        }
        backendChatClient.uploadImageForChat(backendSessionIdForActiveProject(), jpegBytes,
                new BackendImageUploadCallback() {
                    @Override
                    public void onUploaded(final String sessionId, final String imageId, int imageBytes) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (!isCurrentInspectionRequest(expectedRun, expectedPointId,
                                        expectedGeneration)) return;
                                ChatProject project = activeProject();
                                if (project == null) return;
                                project.backendSessionId = sessionId;
                                persistChatProjects();
                                sendInspectionPhotoToAi(jpegBytes, imageId, expectedRun,
                                        expectedPointId, expectedGeneration);
                            }
                        });
                    }

                    @Override
                    public void onError(final Exception error) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                failInspectionAi(expectedRun, expectedPointId,
                                        expectedGeneration, error);
                            }
                        });
                    }
                });
    }

    static File writeEvidenceFile(File directory, String reference, byte[] bytes) throws IOException {
        if (directory == null || bytes == null || bytes.length == 0) {
            throw new IOException("inspection evidence is empty");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("unable to create evidence directory");
        }
        String safeReference = reference == null ? "inspection-photo"
                : reference.replaceAll("[^A-Za-z0-9._-]", "-");
        File output = new File(directory, safeReference + ".jpg");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(bytes);
            stream.flush();
        }
        return output;
    }

    private void sendInspectionPhotoToAi(final byte[] jpegBytes, String imageId,
            final InspectionRun expectedRun, final String expectedPointId,
            final long expectedGeneration) {
        if (!isCurrentInspectionRequest(expectedRun, expectedPointId, expectedGeneration)) return;
        final StringBuilder response = new StringBuilder();
        String prompt = InspectionAiBridge.prompt(expectedRun.currentPoint())
                + "\n上次记录：" + expectedRun.currentPoint().previousValue()
                + "。请在可确认时说明与上次相比是否变化。";
        chatAiClient.send(prompt, imageId, jpegBytes, new StreamingCallback() {
            @Override
            public void onDelta(String text) {
                synchronized (response) {
                    if (text != null) response.append(text);
                }
            }

            @Override
            public void onComplete() {
                final String completed;
                synchronized (response) { completed = response.toString(); }
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!isCurrentInspectionRequest(expectedRun, expectedPointId,
                                expectedGeneration)) return;
                        InspectionAiBridge.ParsedResponse parsed = InspectionAiBridge.parse(completed);
                        if (parsed.observation().length() == 0) {
                            failInspectionAi(expectedRun, expectedPointId, expectedGeneration,
                                    new IOException("inspection_ai_empty_response"));
                            return;
                        }
                        String currentValue = parsed.currentValue().length() > 0
                                ? parsed.currentValue() : parsed.observation();
                        expectedRun.recordAiObservation(parsed.observation(),
                                expectedRun.currentPoint().previousValue(), currentValue);
                        inspectionAiInFlight = false;
                        persistChatProjects();
                        showHudOperationDetail("inspection");
                        setChatStatus("AI 识别完成，请确认正常或异常");
                        scheduleForegroundVoiceListening("inspection-ai-complete");
                    }
                });
            }

            @Override
            public void onError(final Exception error) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        failInspectionAi(expectedRun, expectedPointId, expectedGeneration, error);
                    }
                });
            }
        });
    }

    private boolean isCurrentInspectionRequest(InspectionRun expectedRun, String expectedPointId,
            long expectedGeneration) {
        return isCurrentInspectionRequest(expectedRun, expectedPointId, expectedGeneration,
                activeInspectionRun, inspectionAiInFlight, inspectionRequestGeneration);
    }

    static boolean isCurrentInspectionRequest(InspectionRun expectedRun, String expectedPointId,
            long expectedGeneration, InspectionRun currentRun, boolean currentInFlight,
            long currentGeneration) {
        return currentInFlight && currentRun == expectedRun && expectedRun != null
                && expectedRun.currentPoint() != null && expectedPointId != null
                && expectedPointId.equals(expectedRun.currentPoint().id())
                && expectedGeneration == currentGeneration;
    }

    private void invalidateInspectionAiRequest() {
        inspectionRequestGeneration++;
        inspectionAiInFlight = false;
        inspectionCapturePending = false;
    }

    private void failInspectionAi(InspectionRun expectedRun, String expectedPointId,
            long expectedGeneration, Exception error) {
        if (!isCurrentInspectionRequest(expectedRun, expectedPointId, expectedGeneration)) return;
        inspectionAiInFlight = false;
        Log.w(KEY_LOG_TAG, "Inspection AI failed: " + safeMessage(error));
        persistChatProjects();
        showHudOperationDetail("inspection");
        setChatStatus("巡检识别失败，照片已保留，请重新拍摄");
    }

    private void confirmInspectionPoint(InspectionRun.Outcome outcome) {
        if (activeInspectionRun == null || inspectionAiInFlight) {
            setChatStatus(inspectionAiInFlight ? "请等待 AI 完成识别" : "请先选择巡检任务");
            return;
        }
        activeInspectionRun.confirmCurrentPoint(outcome,
                outcome == InspectionRun.Outcome.NORMAL ? "现场确认正常" : "现场确认异常");
        if (!activeInspectionRun.completeCurrentPoint()) {
            setChatStatus("请先完成拍照和 AI 识别，再确认当前点位");
        } else if (activeInspectionRun.completedPointCount()
                == activeInspectionRun.definition().points().size()) {
            setChatStatus("巡检完成，所有点位记录已保存");
        } else {
            setChatStatus("已记录，进入下一巡检点位");
        }
        persistChatProjects();
        showHudOperationDetail("inspection");
    }

    private void beginVoiceEventDescription() {
        setChatStatus(photoDescriptionPrompt());
        composerTranscript = "";
        renderComposer();
        scheduleForegroundVoiceListening("photo-await-description");
        armVoiceEventDescriptionTimeout();
    }

    private void armVoiceEventDescriptionTimeout() {
        cancelVoiceEventDescriptionTimeout();
        voiceEventDescriptionTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (voiceEventStateMachine.state() != VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
                    return;
                }
                if (shouldDeferPhotoDraftAutoSubmit(recordingVoice,
                        voiceStreamState == VoiceStreamState.LISTENING,
                        voiceStreamState == VoiceStreamState.PARTIAL_READY,
                        composerTranscript)) {
                    armVoiceEventDescriptionTimeout();
                    return;
                }
                cancelForegroundVoiceListening();
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
                        boolean pendingAiRequest = shouldFailPendingImageUpload(
                                sendAfterImageUpload,
                                voiceStreamState == VoiceStreamState.AI_PENDING);
                        composerImageId = "";
                        composerImageUploadFailed = true;
                        sendAfterImageUpload = false;
                        showComposerAttachment("上传失败：" + safeMessage(error));
                        if (pendingAiRequest) {
                            failAssistantStreamingMessage(error, null, "照片上传阶段");
                        }
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
        if (shouldRenderChatForCurrentSurface(screenMode == ScreenMode.CHAT,
                isCapabilityCenterVisible(), hudVoiceGuideVisible || hudGlassesGuideVisible)) {
            renderChatScreen();
        }
        if (sendAfterImageUpload && composerTranscript.trim().length() > 0) {
            sendAfterImageUpload = false;
            sendComposerToAi();
        }
    }

    private void appendUserImageMessage(String imageId, String imagePreviewBase64) {
        chatMessages.add(new ChatMessage("user", "image", "现场照片已随问题发送", imageId, imagePreviewBase64, false));
    }

    private void appendSceneReferenceGuide(SceneReferenceGuide.Reference reference) {
        if (reference == null) {
            return;
        }
        chatMessages.add(new ChatMessage("assistant", "image", reference.caption(),
                reference.imageId(), sceneReferencePreviewBase64(reference.imageId()), false));
    }

    private String sceneReferencePreviewBase64(String imageId) {
        String assetPath = SceneReferenceGuide.assetPath(imageId);
        if (assetPath.length() == 0) {
            return "";
        }
        try (InputStream stream = getAssets().open(assetPath);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (IOException error) {
            Log.w(KEY_LOG_TAG, "Unable to encode scene reference asset " + assetPath, error);
            return "";
        }
    }

    private void attachDetectionMarkersToLatestTaskImage(String markersJson) {
        int start = Math.max(0, hudTaskMessageStartIndex);
        for (int i = chatMessages.size() - 1; i >= start; i--) {
            ChatMessage message = chatMessages.get(i);
            if ("user".equals(message.role) && "image".equals(message.kind)) {
                message.detectionMarkersJson = markersJson == null ? "[]" : markersJson;
                return;
            }
        }
    }

    private ChatMessage latestImageMessage() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = chatMessages.get(i);
            if (isUserEvidenceImage(message.role, message.kind, message.imageId)) {
                return message;
            }
        }
        return null;
    }

    static boolean isUserEvidenceImage(String role, String kind, String imageId) {
        return "user".equals(role) && "image".equals(kind)
                && imageId != null && imageId.length() > 0
                && !SceneReferenceGuide.isReferenceImageId(imageId);
    }

    static boolean isTaskHudDisplayImage(String role, String kind, String imageId) {
        if (!"image".equals(kind) || imageId == null || imageId.length() == 0) {
            return false;
        }
        return "user".equals(role)
                || ("assistant".equals(role) && SceneReferenceGuide.isReferenceImageId(imageId));
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
        if (shouldRenderChatForCurrentSurface(screenMode == ScreenMode.CHAT,
                isCapabilityCenterVisible(), hudVoiceGuideVisible || hudGlassesGuideVisible)) {
            renderChatScreen();
        }
    }

    static boolean shouldRenderChatForCurrentSurface(boolean chatSurfaceVisible,
            boolean capabilitySurfaceVisible, boolean guideSurfaceVisible) {
        return chatSurfaceVisible && !capabilitySurfaceVisible && !guideSurfaceVisible;
    }

    private boolean handleVoiceCommand(String text) {
        VoiceCommand command = classifyVoiceCommand(text);
        if (command == VoiceCommand.NONE) {
            return false;
        }
        clearLiveTranscriptMessageIfStreaming();
        Log.i(KEY_LOG_TAG, "Voice command=" + command + " screen=" + screenMode
                + " taskWorkspace=" + hudTaskWorkspaceActive + " progress=" + hudTaskProgress);
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
        boolean agentSkillCatalogVisible = isCapabilityCenterVisible()
                && capabilityDetailVisible && "agent_center".equals(hudOperationAbilityId);
        String agentSkillAction = agentSkillActionFromVoice(text, agentSkillCatalogVisible);
        if (agentSkillAction.length() > 0) {
            performHudOperation(agentSkillAction);
            return true;
        }
        boolean inspectionCatalogVisible = isCapabilityCenterVisible()
                && capabilityDetailVisible && "inspection".equals(hudOperationAbilityId)
                && activeInspectionRun == null;
        String spokenInspectionTaskId = inspectionTaskIdFromVoice(text, inspectionCatalogVisible);
        if (spokenInspectionTaskId.length() > 0) {
            performHudOperation("start_inspection:" + spokenInspectionTaskId);
            return true;
        }
        VoiceCommandRouter.Command command = voiceCommandRouter.route(text);
        if (command == VoiceCommandRouter.Command.NONE) {
            boolean capabilityVisible = isCapabilityCenterVisible();
            boolean hasPendingPhoto = composerImageBytes != null && composerImageBytes.length > 0;
            Log.i(KEY_LOG_TAG, "Voice freeform policy state=" + voiceEventStateMachine.state()
                    + " hasPendingPhoto=" + hasPendingPhoto
                    + " capabilityVisible=" + capabilityVisible
                    + " voiceGuideVisible=" + hudVoiceGuideVisible
                    + " glassesGuideVisible=" + hudGlassesGuideVisible
                    + " commandOverlayVisible=" + isCommandOverlayVisible()
                    + " expertVisible=" + (screenMode == ScreenMode.EXPERT));
            if (shouldConsumeVoiceAsPhotoDescription(
                    voiceEventStateMachine.state(), hasPendingPhoto)) {
                submitVoiceEvent(voiceEventStateMachine.onDescriptionFinal(text));
                return true;
            }
            if (voiceEventStateMachine.state()
                    == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
                Log.w(KEY_LOG_TAG, "Resetting stale photo-description state without pending photo");
                cancelVoiceEventDescriptionTimeout();
                voiceEventStateMachine.reset();
            }
            if (shouldForwardFreeformFromAgentSkillCatalog(agentSkillCatalogVisible, text)) {
                Log.i(KEY_LOG_TAG, "Forwarding capability-center freeform speech to AI");
                hideCapabilityCenter();
                activateHudTaskWorkspace();
                return false;
            }
            if (shouldContainUnrecognizedVoiceOnCurrentSurface(
                    hudVoiceGuideVisible || hudGlassesGuideVisible,
                    isCommandOverlayVisible(), capabilityVisible,
                    screenMode == ScreenMode.EXPERT)) {
                composerTranscript = "";
                clearLiveTranscriptMessageIfStreaming();
                setChatStatus("未识别当前页面命令，请按页面提示重试");
                syncHudPresentation();
                return true;
            }
            return false;
        }
        clearLiveTranscriptMessageIfStreaming();
        if (command == VoiceCommandRouter.Command.HOME) {
            returnToHudHomeFromVoice();
            return true;
        }
        Boolean environmentAgentEnabled = environmentAgentEnabledState(command);
        if (environmentAgentEnabled != null) {
            setEnvironmentAgentEnabled(environmentAgentEnabled.booleanValue());
            return true;
        }
        if (screenMode == ScreenMode.EXPERT && shouldKeepExpertSurface(command)) {
            showExpertStatus("专家等待中，可说“小叮当，返回首页”或使用挂断操作");
            return true;
        }
        if (command == VoiceCommandRouter.Command.AGENT_SKILL_ACTION) {
            setChatStatus("请先打开 AI运维技能，再说出完整的技能名称和启用或停用");
            return true;
        }
        if (command == VoiceCommandRouter.Command.CAPABILITY_CENTER) {
            showCapabilityCenter();
            return true;
        }
        if (activeInspectionRun != null && isCapabilityCenterVisible()) {
            if (command == VoiceCommandRouter.Command.PHOTO
                    || command == VoiceCommandRouter.Command.RETAKE) {
                performHudOperation("capture_inspection_photo");
                return true;
            }
            if (command == VoiceCommandRouter.Command.NORMAL
                    || command == VoiceCommandRouter.Command.CONFIRM
                    || command == VoiceCommandRouter.Command.NEXT
                    || command == VoiceCommandRouter.Command.FINISH) {
                performHudOperation("confirm_inspection_normal");
                return true;
            }
            if (command == VoiceCommandRouter.Command.ABNORMAL) {
                performHudOperation("confirm_inspection_abnormal");
                return true;
            }
        }
        if (hudVoiceGuideVisible || hudGlassesGuideVisible) {
            if (hudGlassesGuideVisible && (command == VoiceCommandRouter.Command.NEXT_PAGE
                    || command == VoiceCommandRouter.Command.NEXT)) {
                hudPresentation.changeTutorialPage(true);
            } else if (hudGlassesGuideVisible && (command == VoiceCommandRouter.Command.PREVIOUS_PAGE
                    || command == VoiceCommandRouter.Command.PREVIOUS)) {
                hudPresentation.changeTutorialPage(false);
            } else if (command == VoiceCommandRouter.Command.BACK
                    || command == VoiceCommandRouter.Command.CANCEL) {
                returnToHudStandby("语音待命");
            } else if (command == VoiceCommandRouter.Command.HELP) {
                openHudVoiceGuide();
            } else if (command == VoiceCommandRouter.Command.GLASSES_TUTORIAL) {
                openHudGlassesTutorial();
            } else if (command == VoiceCommandRouter.Command.REPEAT) {
                if (hudGlassesGuideVisible) {
                    openHudGlassesTutorial();
                } else {
                    openHudVoiceGuide();
                }
            } else {
                setChatStatus("可说‘返回’或‘返回首页’退出帮助");
            }
            return true;
        }
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
        if (isCapabilityCenterVisible() && capabilityDetailVisible
                && "workflow".equals(hudOperationAbilityId)
                && !activeWorkflowAssignmentId.isEmpty()) {
            if (command == VoiceCommandRouter.Command.BACK
                    || command == VoiceCommandRouter.Command.CANCEL) {
                performHudOperation(workflowNavigationBackAction(
                        hudOperationDetail == null
                                ? "" : hudOperationDetail.primaryAction(),
                        hudOperationDetail == null
                                ? "" : hudOperationDetail.secondaryAction()));
                return true;
            }
            if (command == VoiceCommandRouter.Command.PHOTO
                    || command == VoiceCommandRouter.Command.RETAKE) {
                performHudOperation("workflow_capture_photo");
                return true;
            }
            if (command == VoiceCommandRouter.Command.VIDEO_START) {
                performHudOperation("workflow_capture_video");
                return true;
            }
            if (command == VoiceCommandRouter.Command.NEXT) {
                performHudOperation("workflow_next");
                return true;
            }
            if (command == VoiceCommandRouter.Command.CONFIRM
                    || command == VoiceCommandRouter.Command.FINISH) {
                String primaryAction = hudOperationDetail == null
                        ? "" : hudOperationDetail.primaryAction();
                if (primaryAction.startsWith("workflow_")) {
                    performHudOperation(primaryAction);
                } else {
                    setChatStatus("当前工作流步骤没有可确认操作");
                }
                return true;
            }
        }
        if (isCapabilityCenterVisible()
                && (command == VoiceCommandRouter.Command.BACK
                || command == VoiceCommandRouter.Command.CANCEL)) {
            if (capabilityDetailVisible) {
                if (hudPresentation != null) {
                    capabilityDetailVisible = false;
                    hudPresentation.showState("capabilities");
                } else {
                    renderCapabilityHub();
                }
            } else {
                hideCapabilityCenter();
            }
            return true;
        }
        if (isCapabilityCenterVisible() && capabilityDetailVisible
                && command == VoiceCommandRouter.Command.NEXT_PAGE) {
            changeHudOperationPage(true);
            return true;
        }
        if (isCapabilityCenterVisible() && capabilityDetailVisible
                && command == VoiceCommandRouter.Command.PREVIOUS_PAGE) {
            changeHudOperationPage(false);
            return true;
        }
        if (screenMode == ScreenMode.EXPERT
                && (command == VoiceCommandRouter.Command.BACK
                || command == VoiceCommandRouter.Command.CANCEL)) {
            exitExpertMode();
            return true;
        }
        if (recoverableAiError.length() > 0
                && (command == VoiceCommandRouter.Command.RETRY
                || command == VoiceCommandRouter.Command.REPEAT
                || command == VoiceCommandRouter.Command.APPEND)) {
            retryAiWithVoice();
            return true;
        }
        if (handleHudGuidanceVoiceCommand(command)) {
            return true;
        }
        MaintenanceTask currentTask = currentMaintenanceTask();
        if (currentTask != null && command == VoiceCommandRouter.Command.NEXT_PAGE) {
            boolean moved = hudTaskWorkspaceActive
                    ? currentTask.nextConversationPage(HUD_CONVERSATION_PAGE_SIZE)
                    : currentTask.nextDiagnosisPage(HUD_DIAGNOSIS_PAGE_SIZE);
            if (moved) {
                setChatStatus(hudTaskWorkspaceActive ? "对话内容下一页" : "诊断内容下一页");
            } else {
                setChatStatus("已是最后一页");
            }
            syncHudPresentation();
            return true;
        }
        if (currentTask != null && command == VoiceCommandRouter.Command.PREVIOUS_PAGE) {
            boolean moved = hudTaskWorkspaceActive
                    ? currentTask.previousConversationPage(HUD_CONVERSATION_PAGE_SIZE)
                    : currentTask.previousDiagnosisPage();
            if (moved) {
                setChatStatus(hudTaskWorkspaceActive ? "对话内容上一页" : "诊断内容上一页");
            } else {
                setChatStatus("已是第一页");
            }
            syncHudPresentation();
            return true;
        }
        if (command == VoiceCommandRouter.Command.GUIDANCE) {
            startHudGuidance();
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
        if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION
                && (command == VoiceCommandRouter.Command.SUBMIT
                || command == VoiceCommandRouter.Command.CONFIRM
                || command == VoiceCommandRouter.Command.FINISH)) {
            setChatStatus("照片仍待发送，请先补充现场描述；无描述时请说“仅发送图片”");
            syncHudPresentation();
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
        if (command == VoiceCommandRouter.Command.DIAGNOSIS) {
            beginVoiceDiagnosisConversation();
            return true;
        }
        if (command == VoiceCommandRouter.Command.VIDEO_START) {
            startSceneVideoCapture();
            return true;
        }
        if (command == VoiceCommandRouter.Command.VIDEO_STOP) {
            stopSceneVideoCapture("voice-stop");
            return true;
        }
        if (command == VoiceCommandRouter.Command.EXPERT) {
            enterExpertMode();
            return true;
        }
        if (command == VoiceCommandRouter.Command.RESTART_TASK) {
            restartHudTask();
            return true;
        }
        if (command == VoiceCommandRouter.Command.BACK
                || command == VoiceCommandRouter.Command.CANCEL) {
            navigateHudBack();
            scheduleForegroundVoiceListening("voice-command-back");
            return true;
        }
        if (command == VoiceCommandRouter.Command.INSPECTION) {
            showCapabilityCenter();
            openAbilityById("inspection");
            return true;
        }
        if (command == VoiceCommandRouter.Command.PERCEPTION) {
            showCapabilityCenter();
            openAbilityById("perception");
            return true;
        }
        if (command == VoiceCommandRouter.Command.RECORD) {
            showCapabilityCenter();
            openAbilityById("tasks");
            return true;
        }
        if (command == VoiceCommandRouter.Command.DEVICE) {
            showCapabilityCenter();
            openAbilityById("device_brain");
            return true;
        }
        if (command == VoiceCommandRouter.Command.KNOWLEDGE) {
            showCapabilityCenter();
            openAbilityById("knowledge");
            return true;
        }
        if (command == VoiceCommandRouter.Command.SKILL_CENTER) {
            showCapabilityCenter();
            openAbilityById("skill_center");
            return true;
        }
        if (command == VoiceCommandRouter.Command.AGENT_CENTER) {
            showCapabilityCenter();
            openAbilityById("agent_center");
            return true;
        }
        if (command == VoiceCommandRouter.Command.TASK_CENTER) {
            showCapabilityCenter();
            openAbilityById("tasks");
            return true;
        }
        if (command == VoiceCommandRouter.Command.WORK_ORDER) {
            showCapabilityCenter();
            openAbilityById("tasks");
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
            openHudVoiceGuide();
            return true;
        }
        if (command == VoiceCommandRouter.Command.GLASSES_TUTORIAL) {
            openHudGlassesTutorial();
            return true;
        }
        if (command == VoiceCommandRouter.Command.CONFIRM) {
            setChatStatus("当前没有待提交的现场照片");
            return true;
        }
        if (command == VoiceCommandRouter.Command.NORMAL
                || command == VoiceCommandRouter.Command.ABNORMAL
                || command == VoiceCommandRouter.Command.NEXT
                || command == VoiceCommandRouter.Command.PREVIOUS
                || command == VoiceCommandRouter.Command.APPEND
                || command == VoiceCommandRouter.Command.RETRY
                || command == VoiceCommandRouter.Command.SAVE
                || command == VoiceCommandRouter.Command.REPEAT) {
            setChatStatus("该语音操作将在巡检与记录模块启用");
            return true;
        }
        return true;
    }

    static boolean shouldKeepExpertSurface(VoiceCommandRouter.Command command) {
        return command != VoiceCommandRouter.Command.HOME
                && command != VoiceCommandRouter.Command.BACK
                && command != VoiceCommandRouter.Command.CANCEL;
    }

    /** Finishes a camera or voice event flow before returning to the chat surface. */
    private void returnToChatFromCameraFlow() {
        if (shouldFinishSceneVideoBeforeCameraExit(
                sceneVideoRecording, sceneVideoStarting, pendingSceneVideoCapture)) {
            stopSceneVideoCapture("camera-back");
            return;
        }
        boolean returningToInspection = inspectionCapturePending && activeInspectionRun != null;
        boolean returningToWorkflow = workflowCapturePending;
        inspectionCapturePending = false;
        cancelWorkflowPhotoCapture("workflow_photo_cancelled");
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
        if (returningToInspection) {
            showHudOperationDetail("inspection");
        } else if (returningToWorkflow) {
            openWorkflowExecution(activeWorkflowAssignmentId, false);
        }
        scheduleForegroundVoiceListening("voice-command-back");
    }

    private void cancelWorkflowPhotoCapture(String reason) {
        if (!workflowCapturePending && workflowPhotoCallback == null) return;
        workflowCapturePending = false;
        WorkflowCapabilityRegistry.Callback callback = workflowPhotoCallback;
        workflowPhotoCallback = null;
        if (callback != null) {
            callback.complete(WorkflowCapabilityRegistry.Result.failed(reason));
        }
    }

    /** Returns to the standby HUD from any non-call surface without leaving camera or ASR alive. */
    private void returnToHudHomeFromVoice() {
        if (isCommandOverlayVisible()) {
            hideCommandOverlay();
        }
        if (isCapabilityCenterVisible()) {
            hideCapabilityCenter();
        }
        cancelVoiceEventDescriptionTimeout();
        stopVoiceRecording(false, "voice-home");
        voiceEventStateMachine.reset();
        pendingVoicePhotoCapture = false;
        cancelWorkflowPhotoCapture("workflow_photo_home");
        if (screenMode == ScreenMode.CAMERA) {
            closeCamera();
            stopCameraThread();
        } else if (screenMode == ScreenMode.EXPERT) {
            exitExpertMode();
        }
        voiceSessionPurpose = VoiceSessionPurpose.NONE;
        voiceStreamState = VoiceStreamState.IDLE;
        returnToHudStandby("语音待命");
        scheduleForegroundVoiceListening("voice-command-home");
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

    static boolean shouldContainUnrecognizedVoiceOnCurrentSurface(boolean guideVisible,
            boolean commandOverlayVisible, boolean capabilityVisible, boolean expertVisible) {
        return guideVisible || commandOverlayVisible || capabilityVisible || expertVisible;
    }

    static boolean shouldConsumeVoiceAsPhotoDescription(VoiceEventStateMachine.State state,
            boolean hasPendingPhoto) {
        return state == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION && hasPendingPhoto;
    }

    static boolean shouldForwardFreeformFromAgentSkillCatalog(boolean agentSkillCatalogVisible,
            String transcript) {
        return agentSkillCatalogVisible && !isInsufficientFreeformVoiceInput(transcript);
    }

    static Boolean environmentAgentEnabledState(VoiceCommandRouter.Command command) {
        if (command == VoiceCommandRouter.Command.ENABLE_ENVIRONMENT_AGENT) {
            return Boolean.TRUE;
        }
        if (command == VoiceCommandRouter.Command.DISABLE_ENVIRONMENT_AGENT) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void beginVoiceDiagnosisConversation() {
        hideCapabilityCenter();
        activateHudTaskWorkspace();
        clearHudTaskProgress();
        recoverableAiError = "";
        composerTranscript = "";
        voiceStreamState = VoiceStreamState.IDLE;
        // Reserve the follow-up ASR turn before the handled-command exit can rearm AIKit.
        voiceSessionPurpose = VoiceSessionPurpose.COMMAND;
        setChatStatus("请描述现场问题");
        renderChatScreen();
        // The wake command is already complete. Start the next capture as an explicit task turn,
        // so the worker can immediately describe the fault without repeating the wake phrase.
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recordingVoice && screenMode == ScreenMode.CHAT) {
                    startToggleVoiceRecording();
                }
            }
        }, 180L);
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
        return normalized.startsWith("\u5c0f\u53ee\u5f53")
                || normalized.startsWith("\u5c0f\u53ee")
                || normalized.startsWith("\u5c0f\u4e01");
    }

    private boolean isDingdangWakeOnly(String text) {
        String normalized = normalizeVoiceCommandText(text);
        return normalized.equals("\u5c0f\u53ee\u5f53")
                || normalized.equals("\u5c0f\u53ee")
                || normalized.equals("\u5c0f\u4e01");
    }

    private boolean hasWakePrefixGrace() {
        return wakePrefixGraceUntilMs > 0L && SystemClock.elapsedRealtime() <= wakePrefixGraceUntilMs;
    }

    private String stripDingdangWakePrefix(String text) {
        String normalized = normalizeVoiceCommandText(text);
        if (normalized.startsWith("\u5c0f\u53ee\u5f53")) {
            return normalized.substring(3);
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
                + " voiceFlowElapsedMs=" + voiceLatencyElapsedMs()
                + " deltaChars=" + (delta == null ? 0 : delta.length()));
    }

    private void updateAssistantStreamingMessage(String delta, boolean renderDelta) {
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
            ChatMessage message = chatMessages.get(streamingAssistantIndex);
            message.text = message.text + delta;
            if (renderDelta) {
                scrollChatToBottom = true;
                scheduleChatStreamRender();
            }
        }
    }

    private void finalizeAssistantStreamingMessage(boolean sceneEvidenceTurn,
            boolean sceneCandidateTurn, boolean requestHadNewImage) {
        String completedResponse = "";
        SceneReferenceGuide.Reference referenceGuide = null;
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
            ChatMessage message = chatMessages.get(streamingAssistantIndex);
            completedResponse = message.text == null ? "" : message.text.trim();
            if (isEmptyAiResponse(completedResponse)) {
                failAssistantStreamingMessage(new IOException("ai_empty_response"), null);
                return;
            }
            message.streaming = false;
        }
        MaintenanceTask task = currentMaintenanceTask();
        TaskSession activeSession = taskSessionManager.active();
        boolean sceneSkillActive = activeSession != null
                && HoneywellTempHumiditySkill.SKILL_ID.equals(activeSession.sceneSkillId());
        if (shouldParseVisualProtocol(sceneSkillActive, sceneCandidateTurn, requestHadNewImage)
                && completedResponse.length() > 0) {
            SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(completedResponse);
            completedResponse = parsed.visibleText();
            if (sceneCandidateTurn && honeywellTempHumiditySkill.activateCandidate(
                    activeSession, parsed.candidateSkillId())) {
                sceneSkillActive = true;
                sceneEvidenceTurn = true;
            }
            String evaluatedStep = activeSession == null ? "" : activeSession.sceneStepId();
            boolean useFixedReference = requestHadNewImage
                    && SceneReferenceGuide.usesFixedReference(evaluatedStep);
            java.util.List<SceneSkillAiBridge.DetectionMarker> stepMarkers =
                    useFixedReference
                            ? java.util.Collections.<SceneSkillAiBridge.DetectionMarker>emptyList()
                            : SceneSkillAiBridge.markersForStep(activeSession, parsed);
            if (requestHadNewImage) {
                attachDetectionMarkersToLatestTaskImage(
                        SceneSkillAiBridge.markersJson(stepMarkers));
            }
            if (shouldEvaluateSceneSkill(sceneSkillActive, sceneEvidenceTurn)) {
                String narration = latestTaskEvidenceText("");
                java.util.Set<String> reconciledEvidence = SceneSkillAiBridge.reconcileEvidence(
                        activeSession, narration, parsed, requestHadNewImage);
                Log.i(KEY_LOG_TAG, "Scene evidence step=" + activeSession.sceneStepId()
                        + " modelTags=" + parsed.evidenceTags()
                        + " reconciledTags=" + reconciledEvidence
                        + " markers=" + parsed.evidenceMarkers().size()
                        + " hudMarkers=" + stepMarkers.size());
                boolean hasReliableMarker = SceneSkillAiBridge.hasReliableEvidenceMarker(
                        stepMarkers);
                HoneywellTempHumiditySkill.Result skillResult = honeywellTempHumiditySkill.evaluate(
                        activeSession, narration, reconciledEvidence, requestHadNewImage,
                        hasReliableMarker);
                if (skillResult.matched()) {
                    completedResponse = skillResult.reply();
                    referenceGuide = SceneReferenceGuide.fallbackFor(evaluatedStep,
                            requestHadNewImage, hasReliableMarker,
                            skillResult.matched(), skillResult.accepted());
                    if (skillResult.accepted() && !skillResult.annotations().isEmpty()) {
                        activeSession.maintenanceTask().putFact(
                                "本步照片标注", String.join("、", skillResult.annotations()));
                    }
                }
            }
            if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
                chatMessages.get(streamingAssistantIndex).text = completedResponse;
            }
            appendSceneReferenceGuide(referenceGuide);
        }
        if (task != null && completedResponse.length() > 0) {
            task.addTurn("AI", completedResponse);
            task.setDiagnosis(extractDiagnosisTitle(completedResponse), completedResponse,
                    extractDiagnosisConfidence(completedResponse));
            recordStructuredTaskFacts(task, completedResponse);
            String[] steps = extractRepairSteps(completedResponse);
            if (steps.length > 0) {
                task.replaceRepairSteps(steps);
            }
        }
        if (shouldLeaveGuidanceAfterAiReply(
                hudTaskProgress == HudTaskProgress.GUIDANCE, completedResponse)) {
            clearHudTaskProgress();
        }
        streamingAssistantIndex = -1;
        setChatStatus("在线");
        persistChatProjects();
        recordCurrentTaskSyncEvent("ai_response",
                taskSyncPayload("text", completedResponse), "");
        scrollChatToBottom = true;
        cancelPendingChatStreamRender();
        renderChatStreamMessagesOnly();
        voiceStreamState = VoiceStreamState.IDLE;
        syncHudPresentation();
        scheduleForegroundVoiceListening("ai-complete");
    }

    static boolean shouldEvaluateSceneSkill(boolean sceneSkillActive, boolean sceneEvidenceTurn) {
        return sceneSkillActive && sceneEvidenceTurn;
    }

    static boolean shouldParseVisualProtocol(boolean sceneSkillActive, boolean sceneCandidateTurn,
            boolean requestHadNewImage) {
        return sceneSkillActive || sceneCandidateTurn || requestHadNewImage;
    }

    static boolean isEmptyAiResponse(String response) {
        return response == null || response.trim().length() == 0;
    }

    private boolean isActiveGptRequest(int requestGeneration) {
        return isActiveGptRequest(streamingAssistantIndex, gptRequestGeneration, requestGeneration);
    }

    static boolean isActiveGptRequest(int assistantIndex, int currentGeneration,
            int expectedGeneration) {
        return assistantIndex >= 0 && currentGeneration == expectedGeneration;
    }

    private void cancelActiveGptRequestForNavigation() {
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()
                && chatMessages.get(streamingAssistantIndex).streaming) {
            chatMessages.remove(streamingAssistantIndex);
        }
        streamingAssistantIndex = -1;
        gptRequestGeneration++;
        gptStreamStartedAtMs = 0L;
        cancelPendingChatStreamRender();
    }

    private void scheduleGptRequestWatchdog(final int requestGeneration) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!shouldFailStalledAiStream(isActiveGptRequest(requestGeneration),
                        voiceStreamState == VoiceStreamState.AI_PENDING)) {
                    return;
                }
                Log.w(KEY_LOG_TAG, "GPT stream terminal timeout requestGeneration=" + requestGeneration);
                failAssistantStreamingMessage(new IOException("ai_stream_terminal_timeout"), null);
            }
        }, GPT_STREAM_TERMINAL_TIMEOUT_MS);
    }

    static boolean shouldFailStalledAiStream(boolean requestActive, boolean aiPending) {
        return requestActive && aiPending;
    }

    static boolean shouldFailPendingImageUpload(boolean autoSendPending, boolean aiPending) {
        return autoSendPending && aiPending;
    }

    static String aiFailureNetworkState(String detail) {
        String value = detail == null ? "" : detail.trim().toLowerCase(Locale.ROOT);
        if (value.contains("credential") || value.contains("unauthorized")
                || value.contains("forbidden") || value.contains("_401")
                || value.contains("_403")) {
            return "后端未授权";
        }
        if (value.contains("timeout")) {
            return "网络超时";
        }
        if (value.contains("network") || value.contains("unknownhost")
                || value.contains("connect") || value.contains("socket")
                || value.contains("unreachable") || value.contains("dns")
                || value.contains("ssl")) {
            return "网络不可用";
        }
        return "服务异常";
    }

    static String recoverableAiFailureMessage(String stage, String detail, String requestId) {
        String safeStage = stage == null || stage.trim().length() == 0
                ? "AI 对话阶段" : stage.trim();
        String reference = requestId == null || requestId.trim().length() == 0
                ? "" : "；请求：" + requestId.trim();
        return "AI 服务请求失败（阶段：" + safeStage
                + "；状态：" + aiFailureNetworkState(detail) + reference
                + "）。当前照片和描述已保留，可说“重试”或重新拍摄。";
    }

    private void sendComposerToAi() {
        final String prompt = composerTranscript.trim();
        recoverableAiError = "";
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
                if (!sendAfterImageUpload) {
                    sendAfterImageUpload = true;
                    uploadImageForChat(image, composerImageGeneration);
                }
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
        MaintenanceTask task = ensureMaintenanceTask(prompt);
        activateHudTaskWorkspace();
        boolean sceneEvidenceTurn = false;
        boolean sceneCandidateTurn = false;
        if (task != null) {
            boolean environmentAgentEnabled = operationDetailFactory
                    .isAgentPackageAuthorized("environment_ops");
            boolean requestHasPhoto = image != null && image.length > 0;
            if (environmentAgentEnabled) {
                boolean activatedByPrompt = honeywellTempHumiditySkill.tryActivate(
                        taskSessionManager.active(), prompt, requestHasPhoto);
                sceneEvidenceTurn = activatedByPrompt;
                sceneCandidateTurn = !activatedByPrompt
                        && honeywellTempHumiditySkill.isCandidateTurn(
                                taskSessionManager.active(), prompt, requestHasPhoto);
            }
            sceneEvidenceTurn = sceneEvidenceTurn || honeywellTempHumiditySkill.shouldHandleTurn(
                    taskSessionManager.active(), prompt, requestHasPhoto);
            task.addTurn("现场人员", prompt);
            if (image != null && image.length > 0) {
                task.putFact("最新输入", "已补充现场照片和语音/文字描述");
            }
        }
        String localReply = localAssistantReply(prompt, Calendar.getInstance());
        if (localReply.length() > 0) {
            updateCurrentProjectTitle(prompt);
            if (hasLiveTranscriptMessage()) {
                updateLiveTranscriptMessage(prompt, true);
                liveTranscriptMessageIndex = -1;
            } else {
                appendUserTranscriptMessage(prompt);
            }
            persistAndRecordUserTurn(prompt, image != null && image.length > 0);
            composerTranscript = "";
            renderComposer();
            completeLocalAssistantTurn(task, prompt, localReply);
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
        persistAndRecordUserTurn(prompt, image != null && image.length > 0);
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
        final String requestId = UUID.randomUUID().toString().substring(0, 8);
        Log.i(KEY_LOG_TAG, "AI request accepted id=" + requestId + " generation=" + requestGeneration
                + " promptChars=" + prompt.length() + " image=" + (imageForAi != null));
        scheduleGptRequestWatchdog(requestGeneration);
        final boolean currentSceneEvidenceTurn = sceneEvidenceTurn;
        final boolean currentSceneCandidateTurn = sceneCandidateTurn;
        final boolean currentRequestHadNewImage = image != null;
        final String requestPrompt = buildDirectAiRequestPrompt(
                prompt, currentSceneEvidenceTurn, currentSceneCandidateTurn,
                currentRequestHadNewImage);
        chatAiClient.send(requestPrompt, effectiveImageId, imageForAi, new StreamingCallback() {
            @Override
            public void onDelta(String text) {
                final String delta = text;
                logGptFirstDeltaIfNeeded(delta);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isActiveGptRequest(requestGeneration)) {
                            return;
                        }
                        updateAssistantStreamingMessage(delta,
                                !currentSceneEvidenceTurn && !currentSceneCandidateTurn);
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
                        Log.i(KEY_LOG_TAG, "GPT stream complete id=" + requestId);
                        finalizeAssistantStreamingMessage(
                                currentSceneEvidenceTurn, currentSceneCandidateTurn,
                                currentRequestHadNewImage);
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
                        Log.w(KEY_LOG_TAG, "GPT stream error id=" + requestId + " " + safeMessage(error));
                        failAssistantStreamingMessage(error, requestId);
                    }
                });
            }
        });
        persistChatProjects();
    }

    /** Keeps a transport failure out of the diagnosis record and leaves the current task recoverable. */
    private void failAssistantStreamingMessage(Exception error, String requestId) {
        failAssistantStreamingMessage(error, requestId, "AI 对话阶段");
    }

    private void failAssistantStreamingMessage(Exception error, String requestId, String stage) {
        String detail = safeMessage(error);
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
            chatMessages.remove(streamingAssistantIndex);
        }
        streamingAssistantIndex = -1;
        gptRequestGeneration++;
        voiceStreamState = VoiceStreamState.IDLE;
        recoverableAiError = recoverableAiFailureMessage(stage, detail, requestId);
        setChatStatus("AI 请求失败 · " + aiFailureNetworkState(detail));
        persistChatProjects();
        renderChatStreamMessagesOnly();
        syncHudPresentation();
    }

    private String buildDirectAiRequestPrompt(String prompt, boolean sceneEvidenceTurn,
            boolean sceneCandidateTurn, boolean requestHadNewImage) {
        StringBuilder context = new StringBuilder();
        MaintenanceTask task = currentMaintenanceTask();
        if (task != null) {
            String taskMemory = task.buildPromptMemory(6);
            if (taskMemory.length() > 0) {
                context.append("以下是同一维修任务的结构化记忆，请以它为准：\n")
                        .append(taskMemory).append('\n');
            }
        }
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
        String sceneInstruction = sceneEvidenceTurn
                ? SceneSkillAiBridge.instruction(taskSessionManager.active(), requestHadNewImage)
                : (sceneCandidateTurn ? SceneSkillAiBridge.candidateInstruction() : "");
        if (requestHadNewImage && !sceneEvidenceTurn && !sceneCandidateTurn) {
            sceneInstruction = SceneSkillAiBridge.imageMarkerInstruction();
        }
        if (context.length() == 0) {
            return buildCurrentQuestionInstruction(prompt) + sceneInstruction;
        }
        return "以下是同一现场事件的已确认上下文，仅在与当前问题相关时参考：\n"
                + context + "\n" + buildCurrentQuestionInstruction(prompt)
                + sceneInstruction;
    }

    static String buildCurrentQuestionInstruction(String prompt) {
        String current = prompt == null ? "" : prompt.trim();
        return "当前问题：" + current
                + "\n\n请直接、简洁回答当前问题，不展示思维过程，也不要套用固定栏目。"
                + "回答必须与当前问题直接相关，不得被历史任务或当前检测步骤带偏。"
                + "当前问题未提交检测结果时，不得输出检查状态或下一步模板，也不得推进当前步骤。"
                + "用户询问原因或状态时直接说明；询问操作或维修时，只给出一个当前最需要执行的下一步，"
                + "该步骤必须是一项可立即执行并验证的原子操作，只能包含一个动作和一个检查对象。"
                + "不得使用“及、或、以及、顿号”等并列结构；其余排查项留到用户反馈后再问，也不要罗列多个可能原因。"
                + "单轮可见回复尽量控制在 180 个汉字以内，确有必要的长内容由界面分页展示。"
                + "只有证据不足时才说明无法确认及需要补拍的具体位置。";
    }

    static String inspectionTaskIdFromVoice(String text) {
        return inspectionTaskIdFromVoice(text, false);
    }

    static String inspectionTaskIdFromVoice(String text, boolean inspectionCatalogVisible) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace("小叮当", "")
                .replaceAll("[\\s，。！？,.!?]", "");
        if (inspectionCatalogVisible
                && ("进入第一个选项".equals(value) || "开始第一个巡检任务".equals(value))) {
            return "lab-training-room";
        }
        if (!value.contains("巡检")) return "";
        if (value.contains("实训室")) return "lab-training-room";
        if (value.contains("水电暖") || value.contains("给排水") || value.contains("供暖")
                || value.contains("水泵") || value.contains("配电")) return "water-power-heating";
        if (value.contains("空调") || value.contains("暖通")) return "air-conditioning";
        if (value.contains("消防")) return "fire-safety";
        return "";
    }

    static String agentSkillActionFromVoice(String text, boolean agentSkillCatalogVisible) {
        if (!agentSkillCatalogVisible) return "";
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace("小叮当", "")
                .replaceAll("[\\s，。！？,.!?]", "");
        String state = "";
        String skillName = value;
        String[] enablePrefixes = new String[]{"启用", "打开"};
        String[] disablePrefixes = new String[]{"停用", "关闭", "禁用"};
        for (String prefix : enablePrefixes) {
            if (value.startsWith(prefix)) {
                state = "enabled";
                skillName = value.substring(prefix.length());
                break;
            }
        }
        if (state.length() == 0) {
            for (String prefix : disablePrefixes) {
                if (value.startsWith(prefix)) {
                    state = "disabled";
                    skillName = value.substring(prefix.length());
                    break;
                }
            }
        }
        if (state.length() == 0) return "";
        String agentId = "";
        if ("网络运维技能".equals(skillName)) agentId = "network_ops";
        else if ("水电暖巡检技能".equals(skillName)) agentId = "water_ops";
        else if ("暖通空调技能".equals(skillName)) agentId = "hvac_ops";
        else if ("消防巡检技能".equals(skillName)) agentId = "fire_ops";
        else if ("环境诊断技能".equals(skillName) || "环境诊断".equals(skillName)) agentId = "environment_ops";
        else if ("安全作业技能".equals(skillName)) agentId = "safety_ops";
        return agentId.length() == 0 ? "" : "set_agent:" + agentId + ":" + state;
    }

    private static String extractDiagnosisTitle(String response) {
        String text = response == null ? "" : response.trim();
        String structuredTitle = extractStructuredSection(text, "诊断结论");
        if (structuredTitle.length() > 0) {
            return structuredTitle;
        }
        String[] labels = new String[]{"初步判断：", "初步判断", "结论：", "结论"};
        for (String label : labels) {
            int index = text.indexOf(label);
            if (index < 0) {
                continue;
            }
            String candidate = text.substring(index + label.length()).trim();
            int end = candidate.indexOf('\n');
            return end >= 0 ? candidate.substring(0, end).trim() : candidate;
        }
        int end = text.indexOf('\n');
        return end >= 0 ? text.substring(0, end).trim() : (text.length() > 32 ? text.substring(0, 32) : text);
    }

    private static void recordStructuredTaskFacts(MaintenanceTask task, String response) {
        if (task == null || response == null || response.trim().length() == 0) {
            return;
        }
        putStructuredTaskFact(task, "判断依据", extractStructuredSection(response, "判断依据"));
        putStructuredTaskFact(task, "安全风险", extractStructuredSection(response, "安全风险"));
        putStructuredTaskFact(task, "需补拍", extractStructuredSection(response, "需补拍"));
        putStructuredTaskFact(task, "下一问题", extractStructuredSection(response, "下一问题"));
        putStructuredTaskFact(task, "专家协同建议", extractStructuredSection(response, "专家协同建议"));
    }

    private static void putStructuredTaskFact(MaintenanceTask task, String label, String value) {
        if (value != null && value.trim().length() > 0 && !"待确认".equals(value.trim())) {
            task.putFact(label, value.trim());
        }
    }

    static String extractStructuredSection(String response, String label) {
        String[] lines = response.split("\\r?\\n");
        String marker = "【" + label + "】";
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index] == null ? "" : lines[index].trim();
            if (!line.startsWith(marker)) {
                continue;
            }
            String value = line.substring(marker.length()).trim();
            if (value.length() > 0) {
                return value;
            }
            for (int next = index + 1; next < lines.length; next++) {
                String candidate = lines[next] == null ? "" : lines[next].trim();
                if (candidate.startsWith("【") || candidate.length() == 0) {
                    break;
                }
                return candidate;
            }
        }
        return "";
    }

    /** A clarification question is still a conversation turn, not a diagnostic conclusion. */
    static boolean isActionableDiagnosisResponse(String response) {
        String text = response == null ? "" : response.trim();
        if (text.length() == 0) {
            return false;
        }
        if (text.contains("信息不足")
                || text.contains("证据不足")
                || text.contains("无法判断")
                || text.contains("需要补充")
                || text.contains("请补充")
                || text.contains("请提供")) {
            return false;
        }
        if (extractDiagnosisConfidence(text) > 0 || extractRepairSteps(text).length > 0) {
            return true;
        }
        return text.contains("初步判断")
                || text.contains("诊断结论")
                || text.contains("故障原因")
                || text.contains("可能原因")
                || text.contains("维修步骤")
                || text.contains("安全风险")
                || text.contains("建议立即");
    }

    private static int extractDiagnosisConfidence(String response) {
        String text = response == null ? "" : response;
        int percent = text.indexOf('%');
        if (percent <= 0) {
            return 0;
        }
        int start = percent - 1;
        while (start >= 0 && Character.isDigit(text.charAt(start))) {
            start--;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(text.substring(start + 1, percent))));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String[] extractRepairSteps(String response) {
        ArrayList<String> steps = new ArrayList<>();
        String[] lines = (response == null ? "" : response).split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!line.matches("^(?:[0-9]{1,2}[.、:：]|[-*])\\s*.+")) {
                continue;
            }
            line = line.replaceFirst("^(?:[0-9]{1,2}[.、:：]|[-*])\\s*", "").trim();
            if (line.length() >= 2 && line.length() <= 60) {
                steps.add(line);
            }
            if (steps.size() == 6) {
                break;
            }
        }
        return steps.toArray(new String[steps.size()]);
    }

    private static boolean isIdentityQuestion(String text) {
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

    static String localAssistantReply(String text, Calendar now) {
        if (isIdentityQuestion(text)) {
            return AI_IDENTITY_RESPONSE;
        }
        String normalized = text == null ? "" : text.replace(" ", "").replace("？", "?");
        boolean asksWeekday = normalized.contains("星期几")
                || normalized.contains("周几")
                || normalized.contains("礼拜几");
        boolean asksDate = normalized.contains("今天几号")
                || normalized.contains("今天日期")
                || normalized.contains("今天是什么日子");
        if (!asksWeekday && !asksDate) {
            return "";
        }
        Calendar value = now == null ? Calendar.getInstance() : now;
        String[] weekdays = {
                "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"
        };
        String weekday = weekdays[value.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY];
        if (asksDate) {
            return "今天是" + value.get(Calendar.YEAR) + "年"
                    + (value.get(Calendar.MONTH) + 1) + "月"
                    + value.get(Calendar.DAY_OF_MONTH) + "日，" + weekday + "。";
        }
        return "今天是" + weekday + "。";
    }

    private void completeLocalAssistantTurn(MaintenanceTask task, String prompt, String response) {
        if (task != null) {
            task.addTurn("AI", response);
        }
        appendAssistantMessage(response);
        scrollChatToBottom = true;
        voiceStreamState = VoiceStreamState.IDLE;
        setChatStatus("在线");
        persistChatProjects();
        recordCurrentTaskSyncEvent("ai_response",
                taskSyncPayload("text", response), "");
        renderChatScreen();
        syncHudPresentation();
        scheduleForegroundVoiceListening("local-answer-complete");
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
        if (SECURE_RUNTIME) {
            backendChatClient = new BackendChatClient(
                    runtimeConfiguration.backendBaseUrl(),
                    deviceSessionManager);
        } else {
            backendChatClient = new BackendChatClient(
                    runtimeConfiguration.backendBaseUrl(),
                    runtimeConfiguration.backendCredential());
        }
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
        RealtimeAsrClient primary;
        if (!DIRECT_GPT_ENABLED && backendChatClient != null) {
            primary = backendChatClient.withSessionProvider(new BackendChatClient.SessionProvider() {
                @Override
                public String sessionId() {
                    return backendSessionIdForActiveProject();
                }
            });
        } else {
            primary = new DirectAsrClient(DIRECT_ASR_ENDPOINT, DIRECT_ASR_API_KEY, DIRECT_ASR_MODEL);
        }
        if (!GeneratedConfig.LOCAL_ASR_FALLBACK_ENABLED) {
            return primary;
        }
        return new ResilientRealtimeAsrClient(
                primary,
                LocalAsrEngineFactory.create(this, true));
    }

    private void scheduleForegroundVoiceListening(String reason) {
        if (!isForegroundWakeListeningEnabled()) {
            cancelForegroundVoiceListening();
            return;
        }
        if (OFFLINE_WAKE_ENABLED) {
            voiceAutoListenArmed = false;
            boolean shouldListen = shouldStartOfflineWakeListening();
            boolean running = wakeWordEngine != null && wakeWordEngine.isRunning();
            WakeListeningSchedulePolicy.Action action = WakeListeningSchedulePolicy.decide(
                    true, shouldListen, running);
            if (action == WakeListeningSchedulePolicy.Action.STOP) {
                cancelForegroundVoiceListening();
                return;
            }
            if (wakeWordEngine == null) {
                setChatStatus("离线唤醒不可用，请重新打开应用");
                return;
            }
            if (action == WakeListeningSchedulePolicy.Action.KEEP_RUNNING) {
                Log.d(KEY_LOG_TAG, "Offline wake already running reason=" + reason);
                return;
            }
            if (shouldListen) {
                Log.i(KEY_LOG_TAG, "Offline wake start reason=" + reason);
                setChatStatus("小叮当待命中");
                wakeWordEngine.start(new WakeWordEngine.Listener() {
                    @Override
                    public void onWakeWordDetected() {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (shouldStartOfflineWakeListening()) {
                                    long now = SystemClock.elapsedRealtime();
                                    if (!shouldAcceptOfflineWakeDetection(
                                            now, offlineWakeSuppressedUntilMs)) {
                                        long delay = offlineWakeSuppressedUntilMs - now;
                                        Log.w(KEY_LOG_TAG,
                                                "Offline wake suppressed during media cooldown delayMs="
                                                        + delay);
                                        wakeWordEngine.stop();
                                        mainHandler.postDelayed(new Runnable() {
                                            @Override public void run() {
                                                scheduleForegroundVoiceListening(
                                                        "media-cooldown-complete");
                                            }
                                        }, Math.max(1L, delay));
                                        return;
                                    }
                                    voiceInteractionStartedAtMs = SystemClock.elapsedRealtime();
                                    Log.i(KEY_LOG_TAG, "Voice latency stage=wake_detected elapsedMs=0");
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
            }
            return;
        }
        cancelForegroundVoiceListening();
        // A cloud ASR recording window is not a wake-word engine. Keep the v7 preview
        // voice-first without silently recording until the verified offline engine is bundled.
        if (VOICE_PREVIEW_ENABLED) {
            setChatStatus("请使用语音提问开始");
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
                && !pendingSceneVideoCapture
                && !sceneVideoStarting
                && !sceneVideoRecording
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
                && !pendingSceneVideoCapture
                && !sceneVideoStarting
                && !sceneVideoRecording
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

    static boolean isVoicePreviewPackage(String appId) {
        return appId != null
                && (appId.startsWith("com.codex.air3nativecamera.dingdangexpert.follow.preview.voice")
                || appId.startsWith("com.codex.air3nativecamera.dingdangexpert.follow.preview.v7"));
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
        boolean offlineWakeCommandStart = voiceSessionPurpose == VoiceSessionPurpose.OFFLINE_WAKE_COMMAND;
        voiceStartedFromAutoWindow = false;
        if (recordingVoice) {
            finishToggleVoiceRecording("manual_finish");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recoverableAiError = "麦克风权限未开启。请在系统设置中开启后使用语音助手。";
            renderChatScreen();
            return;
        }
        if (!offlineWakeCommandStart) {
            activateHudTaskWorkspace();
        }
        // A manual voice turn must have exclusive microphone ownership as well. The wake path
        // already stops AIKit, but touch and hardware shortcuts arrive here directly.
        if (OFFLINE_WAKE_ENABLED && wakeWordEngine != null && wakeWordEngine.isRunning()) {
            Log.i(KEY_LOG_TAG, "Stopping offline wake before ASR capture");
            cancelForegroundVoiceListening();
            waitForWakeAudioReleaseThenStartAsr(0);
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
            Log.i(KEY_LOG_TAG, "Voice latency stage=audio_record_started elapsedMs="
                    + voiceLatencyElapsedMs());
            if (offlineWakeCommandStart) {
                setChatStatus("已唤醒，请说指令");
                playWakeFeedbackTone();
            } else {
                setChatStatus("语音识别中");
            }
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
        cancelVoiceAsrFinishTimeout();
        voiceStreamState = VoiceStreamState.LISTENING;
        currentAsrStartedAtMs = SystemClock.elapsedRealtime();
        currentAsrFirstPartialLogged = false;
        final long asrSessionId = voiceAsrSessionGate.begin();
        activeVoiceAsrSessionId = asrSessionId;
        Log.i(KEY_LOG_TAG, "Voice latency stage=asr_started session=" + asrSessionId
                + " elapsedMs=" + voiceLatencyElapsedMs());
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
        final long stableDelayMs = voiceTranscriptStableStopDelayMs(
                voiceCommandRouter.isFastControlCommand(cleaned));
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
                if (now - transcriptAtMs < stableDelayMs) {
                    return;
                }
                voiceAutoStopRequested = true;
                Log.i(KEY_LOG_TAG, "Voice auto stop by transcript stable stableMs="
                        + (now - transcriptAtMs) + " delayMs=" + stableDelayMs);
                finishToggleVoiceRecording("transcript_stable_auto_stop");
            }
        };
        mainHandler.postDelayed(voiceTranscriptStableStopRunnable, stableDelayMs);
    }

    static long voiceTranscriptStableStopDelayMs(boolean fastControlCommand) {
        return fastControlCommand
                ? VOICE_AUTO_STOP_COMMAND_STABLE_MS
                : VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS;
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
        scheduleVoiceAsrFinishTimeout(activeVoiceAsrSessionId);
    }

    private void scheduleVoiceAsrFinishTimeout(final long asrSessionId) {
        cancelVoiceAsrFinishTimeout();
        if (asrSessionId <= 0L) {
            return;
        }
        voiceAsrFinishTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                voiceAsrFinishTimeoutRunnable = null;
                if (recordingVoice || !voiceAsrSessionGate.accepts(asrSessionId)
                        || (voiceStreamState != VoiceStreamState.LISTENING
                        && voiceStreamState != VoiceStreamState.PARTIAL_READY)) {
                    return;
                }
                Log.w(KEY_LOG_TAG, "Realtime ASR finish timeout session=" + asrSessionId);
                voiceAsrSessionGate.invalidate();
                if (realtimeAsrClient != null) {
                    realtimeAsrClient.cancel();
                }
                realtimeAsrFinished = false;
                voiceStartedFromAutoWindow = false;
                voiceSessionPurpose = VoiceSessionPurpose.NONE;
                composerTranscript = "";
                clearLiveTranscriptMessageIfStreaming();
                voiceStreamState = VoiceStreamState.IDLE;
                setChatStatus(hudTaskProgress == HudTaskProgress.GUIDANCE
                        ? "继续当前维修步骤"
                        : "没有听清，请重新提问");
                renderComposer();
                scheduleForegroundVoiceListening("asr-finish-timeout");
            }
        };
        mainHandler.postDelayed(voiceAsrFinishTimeoutRunnable, VOICE_ASR_FINISH_TIMEOUT_MS);
    }

    private void cancelVoiceAsrFinishTimeout() {
        if (voiceAsrFinishTimeoutRunnable != null) {
            mainHandler.removeCallbacks(voiceAsrFinishTimeoutRunnable);
            voiceAsrFinishTimeoutRunnable = null;
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
        if (!transcribe) {
            cancelVoiceAsrFinishTimeout();
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
        cancelVoiceAsrFinishTimeout();
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
        if (!currentAsrFirstPartialLogged) {
            currentAsrFirstPartialLogged = true;
            Log.i(KEY_LOG_TAG, "Voice latency stage=asr_first_partial session=" + asrSessionId
                    + " asrElapsedMs=" + elapsedSince(currentAsrStartedAtMs)
                    + " totalElapsedMs=" + voiceLatencyElapsedMs());
        }
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
                String displayPartial = sanitizeTaskNarration(partial);
                // A wake phrase is a control event, never a visible task draft or evidence item.
                if (displayPartial.length() == 0) {
                    composerTranscript = "";
                    renderComposer();
                    return;
                }
                voiceStreamState = VoiceStreamState.PARTIAL_READY;
                composerTranscript = displayPartial;
                setChatStatus("正在听");
                updateLiveTranscriptDraft(displayPartial);
                scheduleTranscriptStableAutoStop(displayPartial);
                renderComposer();
            }
        });
    }

    private void onAsrFinal(final long asrSessionId, final String text) {
        final String finalText = sanitizeTranscriptForDisplay(text);
        Log.i(KEY_LOG_TAG, "Voice latency stage=asr_final session=" + asrSessionId
                + " asrElapsedMs=" + elapsedSince(currentAsrStartedAtMs)
                + " totalElapsedMs=" + voiceLatencyElapsedMs()
                + " textChars=" + finalText.length());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!voiceAsrSessionGate.accepts(asrSessionId) || realtimeAsrFinished) {
                    return;
                }
                cancelVoiceAsrFinishTimeout();
                realtimeAsrFinished = true;
                boolean autoWindowFinal = voiceStartedFromAutoWindow;
                boolean offlineWakeCommandFinal =
                        voiceSessionPurpose == VoiceSessionPurpose.OFFLINE_WAKE_COMMAND;
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
                effectiveFinalText = sanitizeTaskNarration(effectiveFinalText);
                if (effectiveFinalText.length() == 0) {
                    composerTranscript = "";
                    voiceStreamState = VoiceStreamState.IDLE;
                    clearLiveTranscriptMessageIfStreaming();
                    scheduleForegroundVoiceListening("wake-only-final");
                    return;
                }
                if (graceCommand) {
                    wakePrefixGraceUntilMs = 0L;
                }
                composerTranscript = effectiveFinalText;
                VoiceCommandRouter.Command routedCommand = voiceCommandRouter.route(effectiveFinalText);
                Log.i(KEY_LOG_TAG, "ASR final purpose="
                        + (offlineWakeCommandFinal ? "offline-wake-command" : "conversation")
                        + " route=" + routedCommand + " text=" + effectiveFinalText);
                boolean standbySurface = screenMode == ScreenMode.CHAT
                        && !hudTaskWorkspaceActive
                        && !hudVoiceGuideVisible
                        && !hudGlassesGuideVisible
                        && !isCapabilityCenterVisible();
                if (shouldRejectOfflineWakeAtStandby(
                        offlineWakeCommandFinal, standbySurface, routedCommand,
                        effectiveFinalText)) {
                    Log.w(KEY_LOG_TAG, "Rejected offline wake utterance at standby text="
                            + effectiveFinalText);
                    composerTranscript = "";
                    voiceStreamState = VoiceStreamState.IDLE;
                    clearLiveTranscriptMessageIfStreaming();
                    setChatStatus("未识别到明确指令，请说‘小叮当，开始诊断’");
                    renderComposer();
                    scheduleForegroundVoiceListening("offline-wake-command-rejected");
                    return;
                }
                if (routedCommand == VoiceCommandRouter.Command.NONE
                        && isInsufficientFreeformVoiceInput(effectiveFinalText)) {
                    composerTranscript = "";
                    voiceStreamState = VoiceStreamState.IDLE;
                    clearLiveTranscriptMessageIfStreaming();
                    setChatStatus("语音内容过短，请补充设备和异常现象");
                    renderComposer();
                    syncHudPresentation();
                    scheduleForegroundVoiceListening("insufficient-freeform-voice");
                    return;
                }
                if (VOICE_WORKFLOW_ENABLED && handleVoicePreviewInteraction(effectiveFinalText)) {
                    finishHandledVoiceCommand("preview-command");
                    return;
                }
                if (handleVoiceCommand(effectiveFinalText)) {
                    finishHandledVoiceCommand("legacy-command");
                    return;
                }
                updateLiveTranscriptMessage(effectiveFinalText, true);
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
                cancelVoiceAsrFinishTimeout();
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
                // An empty first utterance belongs to the landing page, not a second composer.
                String diagnosticStatus = voiceStatusForDiagnostic(code);
                composerTranscript = hasUserConversation() || composerImageBytes != null
                        ? diagnosticStatus
                        : "";
                clearLiveTranscriptMessageIfStreaming();
                setChatStatus(isActionableAsrServiceFailure(code)
                        ? diagnosticStatus
                        : "在线");
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
        if (voiceEventStateMachine.state() == VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION) {
            armVoiceEventDescriptionTimeout();
        }
        voiceSessionPurpose = VoiceSessionPurpose.OFFLINE_WAKE_COMMAND;
        wakeFeedbackDelivered = true;
        setChatStatus("已唤醒，正在准备语音输入");
        waitForWakeAudioReleaseThenStartAsr(0);
    }

    static boolean shouldRearmWakeAfterHandledCommand(boolean recording, boolean aiInFlight,
            boolean commandSessionActive) {
        return !recording && !aiInFlight && !commandSessionActive;
    }

    static boolean shouldRejectOfflineWakeAtStandby(boolean offlineWakeCommand,
            boolean standbySurface, VoiceCommandRouter.Command command) {
        if (!offlineWakeCommand || !standbySurface) {
            return false;
        }
        return command != VoiceCommandRouter.Command.DIAGNOSIS
                && command != VoiceCommandRouter.Command.PHOTO
                && command != VoiceCommandRouter.Command.VIDEO_START
                && command != VoiceCommandRouter.Command.EXPERT
                && command != VoiceCommandRouter.Command.CAPABILITY_CENTER
                && command != VoiceCommandRouter.Command.INSPECTION
                && command != VoiceCommandRouter.Command.TASK_CENTER
                && command != VoiceCommandRouter.Command.WORK_ORDER
                && command != VoiceCommandRouter.Command.KNOWLEDGE
                && command != VoiceCommandRouter.Command.DEVICE
                && command != VoiceCommandRouter.Command.AGENT_CENTER
                && command != VoiceCommandRouter.Command.ENABLE_ENVIRONMENT_AGENT
                && command != VoiceCommandRouter.Command.DISABLE_ENVIRONMENT_AGENT
                && command != VoiceCommandRouter.Command.HELP
                && command != VoiceCommandRouter.Command.GLASSES_TUTORIAL;
    }

    static boolean shouldRejectOfflineWakeAtStandby(boolean offlineWakeCommand,
            boolean standbySurface, VoiceCommandRouter.Command command, String transcript) {
        if (!offlineWakeCommand || !standbySurface) {
            return false;
        }
        if (command != VoiceCommandRouter.Command.NONE) {
            return false;
        }
        return isInsufficientFreeformVoiceInput(transcript);
    }

    private void finishHandledVoiceCommand(String reason) {
        boolean aiInFlight = voiceStreamState == VoiceStreamState.AI_PENDING
                || streamingAssistantIndex >= 0;
        boolean shouldRearm = shouldRearmWakeAfterHandledCommand(recordingVoice, aiInFlight,
                voiceSessionPurpose == VoiceSessionPurpose.COMMAND);
        composerTranscript = transcriptAfterHandledVoiceCommand(shouldRearm, composerTranscript);
        if (!shouldRearm) {
            return;
        }
        clearLiveTranscriptMessageIfStreaming();
        voiceStreamState = VoiceStreamState.IDLE;
        scheduleForegroundVoiceListening(reason);
    }

    static String transcriptAfterHandledVoiceCommand(boolean shouldRearm, String transcript) {
        if (shouldRearm) {
            return "";
        }
        return transcript == null ? "" : transcript;
    }

    static boolean shouldWaitForWakeAudioRelease(boolean wakeAudioRunning, int attempt) {
        return wakeAudioRunning && attempt < 20;
    }

    private void waitForWakeAudioReleaseThenStartAsr(final int attempt) {
        boolean wakeAudioRunning = OFFLINE_WAKE_ENABLED
                && wakeWordEngine != null
                && wakeWordEngine.isRunning();
        if (shouldWaitForWakeAudioRelease(wakeAudioRunning, attempt)) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForWakeAudioReleaseThenStartAsr(attempt + 1);
                }
            }, 50L);
            return;
        }
        if (wakeAudioRunning) {
            Log.w(KEY_LOG_TAG, "Offline wake microphone release timed out");
            voiceSessionPurpose = VoiceSessionPurpose.NONE;
            wakeFeedbackDelivered = false;
            setChatStatus("麦克风切换未完成，请再说‘小叮当’");
            scheduleForegroundVoiceListening("wake-release-timeout");
            return;
        }
        Log.i(KEY_LOG_TAG, "Voice latency stage=wake_audio_released attempts=" + attempt
                + " elapsedMs=" + voiceLatencyElapsedMs());
        if (!isVoiceControlAvailableOnCurrentScreen() || recordingVoice
                || (voiceSessionPurpose != VoiceSessionPurpose.COMMAND
                && voiceSessionPurpose != VoiceSessionPurpose.OFFLINE_WAKE_COMMAND
                && voiceSessionPurpose != VoiceSessionPurpose.NONE)) {
            return;
        }
        startToggleVoiceRecording();
    }

    private long voiceLatencyElapsedMs() {
        return elapsedSince(voiceInteractionStartedAtMs);
    }

    private static long elapsedSince(long startedAtMs) {
        return startedAtMs <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
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
        if ("asr_provider_account_unavailable".equals(safeCode)) {
            return "语音转写服务账户不可用，请检查服务配置";
        }
        if ("asr_provider_quota_exhausted".equals(safeCode)) {
            return "语音转写额度不足，请检查服务配置";
        }
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

    private static boolean isActionableAsrServiceFailure(String code) {
        String safeCode = code == null ? "" : code;
        return "asr_provider_account_unavailable".equals(safeCode)
                || "asr_provider_quota_exhausted".equals(safeCode)
                || "asr_endpoint_missing".equals(safeCode)
                || "asr_realtime_unavailable".equals(safeCode)
                || safeCode.startsWith("asr_error:");
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
                resetCameraPreviewStability();
                configurePreviewTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (!cameraPreviewTransformReady || cameraPreviewStable) {
                    return;
                }
                cameraPreviewFrameCount++;
                long elapsed = SystemClock.elapsedRealtime() - cameraPreviewTransformReadyAtMs;
                if (!isCameraFrameStableForCapture(
                        cameraPreviewTransformReady, cameraPreviewFrameCount, elapsed)) {
                    return;
                }
                cameraPreviewStable = true;
                previewView.setAlpha(1f);
                cameraStatusText.setText("取景中 · 说“拍照”");
                capturePendingVoicePhotoIfReady();
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
            sensorOrientation = readSensorOrientation(manager, cameraId);
            cameraLensFacing = readLensFacing(manager, cameraId);
            previewSize = choosePreviewSize(manager, cameraId);
            captureSize = chooseCaptureSize(manager, cameraId);
            videoSize = chooseVideoSize(manager, cameraId);
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
            final long expectedDeviceGeneration = ++cameraDeviceGeneration;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (!isCurrentCameraCallback(expectedDeviceGeneration,
                            cameraDeviceGeneration,
                            screenMode == ScreenMode.CAMERA && cameraHandler != null)) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
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
        boolean swapped = cameraDimensionsSwapped();
        Size fallback = swapped ? new Size(720, 1280) : new Size(1280, 720);
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        return chooseBestPreviewSize(sizes, fallback, cameraDimensionsSwapped());
    }

    private Size chooseCaptureSize(CameraManager manager, String id) throws CameraAccessException {
        StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        boolean swapped = cameraDimensionsSwapped();
        Size fallback = swapped ? new Size(720, 1280) : new Size(1280, 720);
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        return chooseBestCaptureSize(sizes, fallback, swapped);
    }

    private Size chooseBestPreviewSize(Size[] sizes, Size fallback, boolean swapped) {
        if (swapped) {
            Size exactPortrait = findExactSize(sizes, 720, 1280);
            if (exactPortrait != null) {
                return exactPortrait;
            }
            Size portrait16By9 = chooseLargestMatchingAspect(
                    sizes, 1080, 1920, 9f / 16f);
            if (portrait16By9 != null) {
                return portrait16By9;
            }
        }
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

    private Size chooseBestCaptureSize(Size[] sizes, Size fallback, boolean swapped) {
        if (swapped) {
            if (isAir3Hardware(Build.MANUFACTURER, Build.MODEL)) {
                Size highResolutionSensor = chooseLargestMatchingAspect(
                        sizes, 4608, 3456, 4f / 3f);
                if (highResolutionSensor != null) {
                    return highResolutionSensor;
                }
            }
            Size exactPortrait = findExactSize(sizes, 720, 1280);
            if (exactPortrait != null) {
                return exactPortrait;
            }
            Size portrait16By9 = chooseLargestMatchingAspect(
                    sizes, 1440, 2560, 9f / 16f);
            if (portrait16By9 != null) {
                return portrait16By9;
            }
        }
        Size highQuality16By9 = chooseLargestMatchingAspect(sizes, 2560, 1440, 16f / 9f);
        if (highQuality16By9 != null) {
            return highQuality16By9;
        }
        return chooseLargestUnder(sizes, 2560, 1440, fallback);
    }

    private Size chooseVideoSize(CameraManager manager, String id) throws CameraAccessException {
        StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        boolean swapped = cameraDimensionsSwapped();
        Size fallback = previewSize == null
                ? (swapped ? new Size(720, 1280) : new Size(1280, 720))
                : previewSize;
        if (map == null) {
            return fallback;
        }
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (swapped) {
            Size exactPortrait = findExactSize(sizes, 720, 1280);
            if (exactPortrait != null) {
                return exactPortrait;
            }
            Size portrait = chooseLargestMatchingAspect(sizes, 720, 1280, 9f / 16f);
            return portrait == null ? chooseLargestUnder(sizes, 720, 1280, fallback) : portrait;
        }
        Size landscape = chooseLargestMatchingAspect(sizes, 1280, 720, 16f / 9f);
        return landscape == null ? chooseLargestUnder(sizes, 1280, 720, fallback) : landscape;
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

    private int readLensFacing(CameraManager manager, String id) throws CameraAccessException {
        Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        return facing == null ? CameraCharacteristics.LENS_FACING_BACK : facing;
    }

    static int relativeCameraRotationDegrees(int sensorDegrees, int displayDegrees,
            boolean frontFacing) {
        int normalizedSensor = ((sensorDegrees % 360) + 360) % 360;
        int normalizedDisplay = ((displayDegrees % 360) + 360) % 360;
        return frontFacing
                ? (normalizedSensor + normalizedDisplay) % 360
                : (normalizedSensor - normalizedDisplay + 360) % 360;
    }

    static boolean cameraDimensionsAreSwapped(int rotationDegrees) {
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        return normalized == 90 || normalized == 270;
    }

    static boolean isAir3Hardware(String manufacturer, String model) {
        String maker = manufacturer == null ? "" : manufacturer.trim();
        String deviceModel = model == null ? "" : model.trim();
        return "inmo".equalsIgnoreCase(maker) && "ima301".equalsIgnoreCase(deviceModel);
    }

    static boolean isCameraFrameStableForCapture(boolean transformReady, int frameCount,
            long elapsedSinceTransformMs) {
        return transformReady && frameCount >= CAMERA_MIN_STABLE_FRAMES
                && elapsedSinceTransformMs >= CAMERA_MIN_STABLE_DURATION_MS;
    }

    private void resetCameraPreviewStability() {
        cameraPreviewTransformReady = false;
        cameraPreviewStable = false;
        cameraPreviewFrameCount = 0;
        cameraPreviewTransformReadyAtMs = 0L;
    }

    static float displayAspectRatioForBuffer(int width, int height, int rotationDegrees) {
        if (width <= 0 || height <= 0) {
            return 1f;
        }
        return cameraDimensionsAreSwapped(rotationDegrees)
                ? height / (float) width
                : width / (float) height;
    }

    private int displayRotationDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private int relativeCameraRotationDegrees() {
        return relativeCameraRotationDegrees(sensorOrientation, displayRotationDegrees(),
                cameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT);
    }

    private boolean cameraDimensionsSwapped() {
        return cameraDimensionsAreSwapped(relativeCameraRotationDegrees());
    }

    private void createPreviewSession() {
        if (cameraDevice == null || previewSize == null || !previewView.isAvailable()) {
            return;
        }
        try {
            final CameraDevice expectedCamera = cameraDevice;
            final Handler expectedHandler = cameraHandler;
            final ImageReader expectedReader = imageReader;
            final long expectedSessionGeneration = ++cameraSessionGeneration;
            if (expectedHandler == null || expectedReader == null) {
                return;
            }
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            CaptureRequest.Builder request = expectedCamera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            expectedCamera.createCaptureSession(
                    Arrays.asList(previewSurface, expectedReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!isCurrentCameraSession(expectedSessionGeneration,
                                    cameraSessionGeneration,
                                    cameraDevice == expectedCamera
                                            && screenMode == ScreenMode.CAMERA)) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, expectedHandler);
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        configurePreviewTransform(
                                                previewView.getWidth(), previewView.getHeight());
                                    }
                                });
                                if (pendingVoicePhotoCapture) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            capturePendingVoicePhotoIfReady();
                                        }
                                    });
                                }
                                if (pendingSceneVideoCapture) {
                                    mainHandler.post(new Runnable() {
                                        @Override public void run() { startSceneVideoCaptureIfReady(); }
                                    });
                                }
                            } catch (CameraAccessException | IllegalStateException ignored) {
                                session.close();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            if (isCurrentCameraSession(expectedSessionGeneration,
                                    cameraSessionGeneration, cameraDevice == expectedCamera)) {
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        cameraStatusText.setText("预览失败");
                                    }
                                });
                            }
                        }
                    },
                    expectedHandler);
        } catch (Exception error) {
            cameraStatusText.setText("预览失败：" + safeMessage(error));
        }
    }

    private void configurePreviewTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int relativeRotation = relativeCameraRotationDegrees();
        boolean swapped = cameraDimensionsAreSwapped(relativeRotation);
        float bufferWidth = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float bufferHeight = swapped ? previewSize.getWidth() : previewSize.getHeight();
        if (bufferWidth <= 0f || bufferHeight <= 0f) {
            return;
        }
        float viewRatio = viewWidth / (float) viewHeight;
        float bufferRatio = bufferWidth / bufferHeight;
        float scale = Math.max(viewWidth / bufferWidth, viewHeight / bufferHeight);
        float offsetX = (viewWidth - bufferWidth * scale) / 2f;
        float offsetY = (viewHeight - bufferHeight * scale) / 2f;
        float rawWidth = previewSize.getWidth();
        float rawHeight = previewSize.getHeight();
        float[] source = {0f, 0f, viewWidth, 0f, viewWidth, viewHeight, 0f, viewHeight};
        float[] target = new float[8];
        mapCameraCorner(target, 0, 0f, 0f, rawWidth, rawHeight,
                relativeRotation, scale, offsetX, offsetY);
        mapCameraCorner(target, 2, rawWidth, 0f, rawWidth, rawHeight,
                relativeRotation, scale, offsetX, offsetY);
        mapCameraCorner(target, 4, rawWidth, rawHeight, rawWidth, rawHeight,
                relativeRotation, scale, offsetX, offsetY);
        mapCameraCorner(target, 6, 0f, rawHeight, rawWidth, rawHeight,
                relativeRotation, scale, offsetX, offsetY);
        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(source, 0, target, 0, 4);
        previewView.setTransform(matrix);
        cameraPreviewTransformReady = true;
        cameraPreviewFrameCount = 0;
        cameraPreviewTransformReadyAtMs = SystemClock.elapsedRealtime();
        Log.i(KEY_LOG_TAG, "Camera preview transform view=" + viewWidth + "x" + viewHeight
                + " preview=" + previewSize.getWidth() + "x" + previewSize.getHeight()
                + " viewRatio=" + viewRatio + " bufferRatio=" + bufferRatio
                + " relativeRotation=" + relativeRotation + " uniformScale=" + scale);
    }

    private static void mapCameraCorner(float[] target, int offset, float x, float y,
            float rawWidth, float rawHeight, int rotationDegrees, float scale,
            float offsetX, float offsetY) {
        float rotatedX;
        float rotatedY;
        switch (((rotationDegrees % 360) + 360) % 360) {
            case 90:
                rotatedX = rawHeight - y;
                rotatedY = x;
                break;
            case 180:
                rotatedX = rawWidth - x;
                rotatedY = rawHeight - y;
                break;
            case 270:
                rotatedX = y;
                rotatedY = rawWidth - x;
                break;
            case 0:
            default:
                rotatedX = x;
                rotatedY = y;
                break;
        }
        target[offset] = offsetX + rotatedX * scale;
        target[offset + 1] = offsetY + rotatedY * scale;
    }

    static float[] previewTransformCorrections(float viewWidth, float viewHeight,
            float bufferWidth, float bufferHeight) {
        if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0f || bufferHeight <= 0f) {
            return new float[]{1f, 1f};
        }
        float defaultScaleX = viewWidth / bufferWidth;
        float defaultScaleY = viewHeight / bufferHeight;
        float cropScale = Math.max(defaultScaleX, defaultScaleY);
        return new float[]{cropScale / defaultScaleX, cropScale / defaultScaleY};
    }

    private void captureStillImage() {
        if (sceneVideoRecording || sceneVideoStarting || pendingSceneVideoCapture) {
            cameraStatusText.setText("短视频取证进行中，请先停止录像");
            return;
        }
        if (captureInFlight || cameraDevice == null || captureSession == null || imageReader == null) {
            cameraStatusText.setText("相机还没准备好");
            return;
        }
        if (!cameraPreviewStable) {
            cameraStatusText.setText("相机正在稳定，请稍候");
            return;
        }
        try {
            captureInFlight = true;
            cameraStatusText.setText("正在拍照");
            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            request.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
            captureSession.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cameraStatusText.setText("照片处理中");
                        }
                    });
                }
            }, cameraHandler);
        } catch (Exception error) {
            captureInFlight = false;
            cameraStatusText.setText("拍照失败：" + safeMessage(error));
        }
    }

    /** Records a short visual evidence clip locally. AI analysis remains photo-and-voice only. */
    private void startSceneVideoCapture() {
        if (sceneVideoRecording || sceneVideoStarting || pendingSceneVideoCapture) {
            return;
        }
        sceneVideoReturnSurface = sceneVideoReturnTarget(hudCapabilityVisible,
                capabilityDetailVisible, hudOperationAbilityId, hudTaskWorkspaceActive);
        hideCapabilityCenter();
        activateHudTaskWorkspace();
        pendingSceneVideoCapture = true;
        cancelForegroundVoiceListening();
        stopVoiceRecording(false, "scene_video_start");
        resetVoiceSessionForForegroundWake();
        if (screenMode != ScreenMode.CAMERA) {
            enterCameraScreen("scene-video");
        }
        cameraStatusText.setText("正在准备现场短视频取证");
        startSceneVideoCaptureIfReady();
    }

    private void startSceneVideoCaptureIfReady() {
        if (!pendingSceneVideoCapture || sceneVideoRecording || sceneVideoStarting) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingSceneVideoCapture = false;
            cameraStatusText.setText("相机权限未开启，请授权后再开始短视频取证");
            failWorkflowVideoCaptureAndReturn(
                    "workflow_video_camera_permission_missing",
                    "相机权限未开启，工作流录像未开始");
            return;
        }
        if (cameraDevice == null || previewSize == null || videoSize == null
                || !previewView.isAvailable() || !cameraPreviewStable) {
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { startSceneVideoCaptureIfReady(); }
            }, 250L);
            return;
        }
        try {
            sceneVideoStarting = true;
            releaseSceneVideoRecorder();
            boolean workflowVideo = workflowVideoCapturePlan != null;
            File evidenceDirectory = new File(
                    getFilesDir(), workflowVideo ? "task-evidence" : "evidence");
            if (!evidenceDirectory.exists() && !evidenceDirectory.mkdirs()) {
                throw new IOException("无法创建现场证据目录");
            }
            sceneVideoFile = new File(evidenceDirectory,
                    (workflowVideo
                            ? activeWorkflowAssignmentId + "-"
                            + workflowVideoCapturePlan.nodeId() + "-"
                            : "scene-")
                            + System.currentTimeMillis() + ".mp4");
            sceneVideoRecorder = new MediaRecorder();
            sceneVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            sceneVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            sceneVideoRecorder.setOutputFile(sceneVideoFile.getAbsolutePath());
            sceneVideoRecorder.setVideoEncodingBitRate(SCENE_VIDEO_BIT_RATE);
            sceneVideoRecorder.setVideoFrameRate(SCENE_VIDEO_FRAME_RATE);
            sceneVideoRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            sceneVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            sceneVideoRecorder.setOrientationHint(jpegOrientation());
            sceneVideoRecorder.prepare();
            createSceneVideoRecordingSession();
        } catch (Exception error) {
            pendingSceneVideoCapture = false;
            sceneVideoStarting = false;
            releaseSceneVideoRecorder();
            if (sceneVideoFile != null && sceneVideoFile.exists()) {
                sceneVideoFile.delete();
            }
            sceneVideoFile = null;
            cameraStatusText.setText("短视频启动失败：" + safeMessage(error));
            if (workflowVideoCapturePlan != null) {
                failWorkflowVideoCaptureAndReturn(
                        "workflow_video_start_failed",
                        "工作流录像启动失败，请重试");
            } else {
                scheduleForegroundVoiceListening("scene-video-failed");
            }
        }
    }

    private void createSceneVideoRecordingSession() throws CameraAccessException {
        if (cameraDevice == null || sceneVideoRecorder == null || !previewView.isAvailable()) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        }
        final CameraDevice expectedCamera = cameraDevice;
        final Handler expectedHandler = cameraHandler;
        final long expectedSessionGeneration = ++cameraSessionGeneration;
        if (expectedHandler == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        }
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        SurfaceTexture texture = previewView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(texture);
        Surface recorderSurface = sceneVideoRecorder.getSurface();
        CaptureRequest.Builder request = expectedCamera.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD);
        request.addTarget(previewSurface);
        request.addTarget(recorderSurface);
        expectedCamera.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (!isCurrentCameraSession(expectedSessionGeneration,
                                cameraSessionGeneration,
                                cameraDevice == expectedCamera && screenMode == ScreenMode.CAMERA)
                                || !sceneVideoStarting || sceneVideoRecorder == null) {
                            session.close();
                            return;
                        }
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(request.build(), null, expectedHandler);
                            sceneVideoRecorder.start();
                            pendingSceneVideoCapture = false;
                            sceneVideoStarting = false;
                            sceneVideoRecording = true;
                            sceneVideoStartedAtMs = SystemClock.elapsedRealtime();
                            mainHandler.post(new Runnable() {
                                @Override public void run() {
                                    cameraStatusText.setText("正在记录现场短视频，确认键可停止");
                                    cameraCaptureButton.setText("■");
                                    cameraCaptureButton.setContentDescription("停止现场短视频");
                                    sceneVideoStopRunnable = new Runnable() {
                                        @Override public void run() {
                                            stopSceneVideoCapture("max-duration");
                                        }
                                    };
                                    mainHandler.postDelayed(
                                            sceneVideoStopRunnable,
                                            sceneVideoMaximumDurationMillis());
                                }
                            });
                        } catch (Exception error) {
                            final String message = "短视频录制失败：" + safeMessage(error);
                            mainHandler.post(new Runnable() {
                                @Override public void run() {
                                    cameraStatusText.setText(message);
                                    stopSceneVideoCapture("start-failed");
                                }
                            });
                        }
                    }

                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        session.close();
                        if (!isCurrentCameraSession(expectedSessionGeneration,
                                cameraSessionGeneration, cameraDevice == expectedCamera)) {
                            return;
                        }
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                pendingSceneVideoCapture = false;
                                sceneVideoStarting = false;
                                cameraStatusText.setText("短视频相机会话创建失败");
                                releaseSceneVideoRecorder();
                                if (workflowVideoCapturePlan != null) {
                                    failWorkflowVideoCaptureAndReturn(
                                            "workflow_video_session_failed",
                                            "工作流录像相机会话创建失败，请重试");
                                } else {
                                    createPreviewSession();
                                    scheduleForegroundVoiceListening(
                                            "scene-video-session-failed");
                                }
                            }
                        });
                    }
                }, expectedHandler);
    }

    private void stopSceneVideoCapture(String reason) {
        if (shouldPostVideoStopToMainThread(Looper.myLooper() == Looper.getMainLooper())) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    stopSceneVideoCapture(reason);
                }
            });
            return;
        }
        if (sceneVideoStopRunnable != null) {
            mainHandler.removeCallbacks(sceneVideoStopRunnable);
            sceneVideoStopRunnable = null;
        }
        pendingSceneVideoCapture = false;
        boolean wasRecording = sceneVideoRecording;
        WorkflowVideoCapturePlan completedWorkflowPlan = workflowVideoCapturePlan;
        WorkflowCapabilityRegistry.Callback completedWorkflowCallback = workflowVideoCallback;
        sceneVideoRecording = false;
        sceneVideoStarting = false;
        File completedVideo = sceneVideoFile;
        String completionMessage = "";
        try {
            if (sceneVideoRecorder != null && wasRecording) {
                sceneVideoRecorder.stop();
            }
        } catch (RuntimeException error) {
            if (completedVideo != null) {
                completedVideo.delete();
            }
            completedVideo = null;
            Log.w(KEY_LOG_TAG, "Scene video recorder stop failed reason=" + reason, error);
        }
        int completedDurationSeconds = completedWorkflowPlan == null
                ? 0
                : completedSceneVideoDurationSeconds(
                        completedVideo, completedWorkflowPlan);
        releaseSceneVideoRecorder();
        sceneVideoFile = null;
        sceneVideoStartedAtMs = 0L;
        if (completedWorkflowPlan != null) {
            workflowVideoCapturePlan = null;
            workflowVideoCallback = null;
            sceneVideoReturnSurface = "standby";
            handleCompletedWorkflowVideo(
                    completedWorkflowPlan,
                    completedWorkflowCallback,
                    completedVideo,
                    wasRecording,
                    completedDurationSeconds);
            return;
        }
        if (retainCompletedSceneVideo(completedVideo)) {
            MaintenanceTask task = ensureMaintenanceTask("请结合现场短视频和语音描述继续分析设备异常");
            TaskSession session = taskSessionManager.active();
            if (task != null && session != null) {
                task.addEvidence("现场短视频 " + (task.evidenceReferences().size() + 1),
                        "local-video:" + completedVideo.getName());
                persistChatProjects();
                recordTaskSyncEvent(session, "video_recorded",
                        taskSyncPayload(
                                "localFileName", completedVideo.getName(),
                                "byteCount", completedVideo.length(),
                                "storageState", "local_saved"),
                        session.id() + ":video_recorded:" + completedVideo.getName());
            }
            completionMessage = "短视频已保存；AI 分析当前支持照片和语音描述";
        } else if (wasRecording) {
            completionMessage = "短视频未保存，请重新录制";
        }
        // Video evidence is complete. Return ownership of Camera2 before going back to the
        // voice-first HUD so ASR and expert collaboration never compete with a hidden preview.
        closeCamera();
        stopCameraThread();
        final String returnSurface = sceneVideoReturnSurface;
        sceneVideoReturnSurface = "standby";
        renderChatScreen();
        if (completionMessage.length() > 0) {
            setChatStatus(completionMessage);
        }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                restoreSceneVideoReturnSurface(returnSurface);
            }
        });
        offlineWakeSuppressedUntilMs = SystemClock.elapsedRealtime()
                + MEDIA_WAKE_REARM_COOLDOWN_MS;
        scheduleForegroundVoiceListening("scene-video-complete");
    }

    private void handleCompletedWorkflowVideo(
            WorkflowVideoCapturePlan plan,
            WorkflowCapabilityRegistry.Callback callback,
            File completedVideo,
            boolean wasRecording,
            int durationSeconds
    ) {
        WorkflowSnapshot snapshot = activeWorkflowSnapshot();
        WorkflowRuntimeState state = snapshot == null ? null : snapshot.runtimeState();
        WorkflowPackage.Node node = state == null
                ? null : snapshot.workflowPackage().node(state.currentNodeId());
        if (!wasRecording || !retainCompletedSceneVideo(completedVideo)
                || durationSeconds < plan.minimumDurationSeconds()) {
            if (completedVideo != null && completedVideo.exists()) completedVideo.delete();
            String failureCode = durationSeconds <= 0
                    ? "workflow_video_duration_unverified"
                    : (durationSeconds < plan.minimumDurationSeconds()
                    ? "workflow_video_too_short" : "workflow_video_invalid");
            completeWorkflowVideoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(failureCode));
            returnFromWorkflowVideoCapture(
                    durationSeconds <= 0
                            ? "无法验证录像时长，请重新录制"
                            : durationSeconds < plan.minimumDurationSeconds()
                            ? "录像不足 " + plan.minimumDurationSeconds() + " 秒，请重新录制"
                            : "工作流录像未保存，请重新录制");
            return;
        }
        if (node == null || !plan.nodeId().equals(node.nodeId())
                || !"video_capture".equals(node.type())
                || workflowExecutionCoordinator == null) {
            if (!completedVideo.delete()) {
                Log.w(KEY_LOG_TAG, "Unable to remove stale workflow video "
                        + completedVideo.getName());
            }
            completeWorkflowVideoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_video_state_changed"));
            returnFromWorkflowVideoCapture("工作流步骤已变化，录像未登记");
            return;
        }

        WorkflowEvidenceReference evidence;
        try {
            evidence = new WorkflowEvidenceReference(
                    "workflow-video-" + UUID.randomUUID(),
                    node.nodeId(),
                    plan.evidenceKey(),
                    WorkflowStepContext.EvidenceType.VIDEO,
                    "task-evidence/" + completedVideo.getName(),
                    "",
                    durationSeconds);
        } catch (RuntimeException exception) {
            if (!completedVideo.delete()) {
                Log.w(KEY_LOG_TAG, "Unable to remove invalid workflow video "
                        + completedVideo.getName());
            }
            completeWorkflowVideoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_video_reference_invalid"));
            returnFromWorkflowVideoCapture("工作流录像引用无效，请重新录制");
            return;
        }

        WorkflowExecutionCoordinator.ActionResult recorded =
                workflowExecutionCoordinator.recordEvidence(
                        activeWorkflowAssignmentId, evidence);
        if (recorded.code() != WorkflowExecutionCoordinator.ActionCode.RECORDED) {
            if (!completedVideo.delete()) {
                Log.w(KEY_LOG_TAG, "Unable to remove rejected workflow video "
                        + completedVideo.getName());
            }
            completeWorkflowVideoCallback(
                    callback,
                    WorkflowCapabilityRegistry.Result.failed(
                            "workflow_video_record_failed"));
            returnFromWorkflowVideoCapture(
                    "工作流录像未登记：" + recorded.reason());
            return;
        }

        int capturedCount = 0;
        for (WorkflowEvidenceReference item : recorded.state().evidenceReferences()) {
            if (node.nodeId().equals(item.nodeId())
                    && item.type() == WorkflowStepContext.EvidenceType.VIDEO) {
                capturedCount++;
            }
        }
        JSONObject output = taskSyncPayload(
                "capturedCount", capturedCount,
                "durationSeconds", durationSeconds);
        WorkflowExecutionCoordinator.ActionResult advanced =
                workflowExecutionCoordinator.advance(
                        activeWorkflowAssignmentId,
                        new WorkflowStepContext(),
                        new JSONObject(),
                        output);
        completeWorkflowVideoCallback(
                callback,
                advanced.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED
                        || advanced.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED
                        ? WorkflowCapabilityRegistry.Result.completed(output)
                        : WorkflowCapabilityRegistry.Result.failed(
                                "workflow_video_advance_failed"));
        closeCamera();
        stopCameraThread();
        renderChatScreen();
        showHudOperationDetail(
                "workflow",
                workflowHudPresenter.actionResult(activeWorkflowAssignmentId, advanced),
                false);
        if (workflowEvidenceUploadCoordinator != null) {
            workflowEvidenceUploadCoordinator.request();
        }
        if (advanced.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED) {
            setChatStatus("录像已本地保存，等待安全上传");
        } else if (advanced.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED) {
            setChatStatus("录像已保存，请继续补全当前步骤");
        } else {
            setChatStatus("录像已保存，但工作流未推进：" + advanced.reason());
        }
        offlineWakeSuppressedUntilMs = SystemClock.elapsedRealtime()
                + MEDIA_WAKE_REARM_COOLDOWN_MS;
        scheduleForegroundVoiceListening("workflow-video-complete");
    }

    private void returnFromWorkflowVideoCapture(String message) {
        closeCamera();
        stopCameraThread();
        renderChatScreen();
        openWorkflowExecution(activeWorkflowAssignmentId, false);
        setChatStatus(message);
        offlineWakeSuppressedUntilMs = SystemClock.elapsedRealtime()
                + MEDIA_WAKE_REARM_COOLDOWN_MS;
        scheduleForegroundVoiceListening("workflow-video-return");
    }

    private long sceneVideoMaximumDurationMillis() {
        WorkflowVideoCapturePlan plan = workflowVideoCapturePlan;
        return plan == null ? SCENE_VIDEO_MAX_DURATION_MS : plan.maximumDurationMillis();
    }

    private int completedSceneVideoDurationSeconds(
            File videoFile,
            WorkflowVideoCapturePlan plan
    ) {
        if (videoFile == null || !videoFile.isFile() || plan == null) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            String rawDuration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMillis = rawDuration == null ? 0L : Long.parseLong(rawDuration);
            return (int) Math.min(
                    plan.maximumDurationSeconds(),
                    Math.max(0L, durationMillis / 1000L));
        } catch (RuntimeException exception) {
            Log.w(KEY_LOG_TAG, "Unable to verify workflow video duration", exception);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void failWorkflowVideoCaptureAndReturn(String reason, String message) {
        if (workflowVideoCapturePlan == null && workflowVideoCallback == null) return;
        cancelWorkflowVideoCapture(reason);
        pendingSceneVideoCapture = false;
        sceneVideoStarting = false;
        sceneVideoRecording = false;
        sceneVideoStartedAtMs = 0L;
        releaseSceneVideoRecorder();
        if (sceneVideoFile != null && sceneVideoFile.exists()) sceneVideoFile.delete();
        sceneVideoFile = null;
        returnFromWorkflowVideoCapture(message);
    }

    private void cancelWorkflowVideoCapture(String reason) {
        WorkflowCapabilityRegistry.Callback callback = workflowVideoCallback;
        workflowVideoCallback = null;
        workflowVideoCapturePlan = null;
        if (callback != null) {
            callback.complete(WorkflowCapabilityRegistry.Result.failed(reason));
        }
    }

    private static void completeWorkflowVideoCallback(
            WorkflowCapabilityRegistry.Callback callback,
            WorkflowCapabilityRegistry.Result result
    ) {
        if (callback != null) callback.complete(result);
    }

    static boolean retainCompletedSceneVideo(File videoFile) {
        if (videoFile != null && videoFile.isFile() && videoFile.length() > 0L) {
            return true;
        }
        if (videoFile != null && videoFile.exists()) {
            videoFile.delete();
        }
        return false;
    }

    static int purgeIncompleteSceneVideos(File evidenceDirectory) {
        if (evidenceDirectory == null || !evidenceDirectory.isDirectory()) {
            return 0;
        }
        File[] candidates = evidenceDirectory.listFiles();
        if (candidates == null) {
            return 0;
        }
        int removed = 0;
        for (File candidate : candidates) {
            String name = candidate == null ? "" : candidate.getName();
            if (candidate != null && candidate.isFile() && candidate.length() == 0L
                    && name.startsWith("scene-") && name.endsWith(".mp4")
                    && candidate.delete()) {
                removed++;
            }
        }
        return removed;
    }

    private void restoreSceneVideoReturnSurface(String returnSurface) {
        String target = returnSurface == null ? "" : returnSurface;
        if (target.startsWith("operation:")) {
            showHudOperationDetail(target.substring("operation:".length()));
            return;
        }
        if ("capabilities".equals(target)) {
            showCapabilityCenter();
            return;
        }
        if ("task".equals(target)) {
            activateHudTaskWorkspace();
            syncHudPresentation();
            return;
        }
        returnToHudStandby("语音待命");
    }

    private void releaseSceneVideoRecorder() {
        if (sceneVideoRecorder == null) {
            return;
        }
        try {
            sceneVideoRecorder.reset();
        } catch (Exception ignored) {
        }
        try {
            sceneVideoRecorder.release();
        } catch (Exception ignored) {
        }
        sceneVideoRecorder = null;
    }

    private int jpegOrientation() {
        return relativeCameraRotationDegrees();
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
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = cameraDecodeSampleSize(
                bounds.outWidth, bounds.outHeight, UPLOAD_MAX_IMAGE_EDGE);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        if (bitmap == null) {
            return bytes;
        }
        Bitmap oriented = applyExifOrientation(bitmap, bytes);
        if (oriented != bitmap) {
            bitmap.recycle();
        }
        Bitmap normalized = applyRequestedCameraRotationIfNeeded(oriented);
        if (normalized != oriented) {
            oriented.recycle();
        }
        // The Air3 camera can emit a portrait JPEG while its TextureView is landscape.
        // Crop to the live view's aspect ratio so the AI evidence matches what the wearer saw.
        Bitmap framed = cropBitmapToPreviewAspect(normalized);
        if (framed != normalized) {
            normalized.recycle();
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

    static int cameraDecodeSampleSize(int width, int height, int targetMaxEdge) {
        if (width <= 0 || height <= 0 || targetMaxEdge <= 0) {
            return 1;
        }
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / (sample * 2) >= targetMaxEdge) {
            sample *= 2;
        }
        return sample;
    }

    static boolean shouldApplyRequestedCameraRotation(int decodedWidth, int decodedHeight,
            int requestedWidth, int requestedHeight, int rotationDegrees) {
        if (!cameraDimensionsAreSwapped(rotationDegrees)
                || decodedWidth <= 0 || decodedHeight <= 0
                || requestedWidth <= 0 || requestedHeight <= 0
                || requestedWidth == requestedHeight || decodedWidth == decodedHeight) {
            return false;
        }
        boolean decodedLandscape = decodedWidth > decodedHeight;
        boolean rawLandscape = requestedWidth > requestedHeight;
        return decodedLandscape == rawLandscape;
    }

    private Bitmap applyRequestedCameraRotationIfNeeded(Bitmap bitmap) {
        if (bitmap == null || captureSize == null) {
            return bitmap;
        }
        int requestedRotation = jpegOrientation();
        if (!shouldApplyRequestedCameraRotation(bitmap.getWidth(), bitmap.getHeight(),
                captureSize.getWidth(), captureSize.getHeight(), requestedRotation)) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.setRotate(requestedRotation);
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    matrix, true);
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private Bitmap cropBitmapToPreviewAspect(Bitmap bitmap) {
        if (bitmap == null || previewSize == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return bitmap;
        }
        float targetAspect = displayAspectRatioForBuffer(
                previewSize.getWidth(), previewSize.getHeight(), relativeCameraRotationDegrees());
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
        cameraDeviceGeneration++;
        cameraSessionGeneration++;
        abortSceneVideoCapture();
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
        resetCameraPreviewStability();
        if (previewView != null) {
            previewView.setAlpha(0f);
        }
    }

    private void abortSceneVideoCapture() {
        if (sceneVideoStopRunnable != null) {
            mainHandler.removeCallbacks(sceneVideoStopRunnable);
            sceneVideoStopRunnable = null;
        }
        pendingSceneVideoCapture = false;
        sceneVideoStarting = false;
        if (sceneVideoRecorder != null && sceneVideoRecording) {
            try {
                sceneVideoRecorder.stop();
            } catch (RuntimeException ignored) {
            }
        }
        sceneVideoRecording = false;
        sceneVideoStartedAtMs = 0L;
        releaseSceneVideoRecorder();
        if (sceneVideoFile != null && sceneVideoFile.exists()) {
            sceneVideoFile.delete();
        }
        sceneVideoFile = null;
        cancelWorkflowVideoCapture("workflow_video_interrupted");
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

    /** Removes a leading wake phrase only when a transcript becomes visible task evidence. */
    static String sanitizeTaskNarration(String text) {
        String cleaned = sanitizeTranscriptForDisplay(text);
        if (cleaned.length() == 0 || isVoiceRecoveryPrompt(cleaned)) {
            return "";
        }
        String narration = cleaned
                .replaceFirst("^\\s*(?:小叮当|小叮|小丁)[，,。！？!?\\s]*", "")
                .trim();
        String effectiveCharacters = narration.replaceAll("[\\p{P}\\p{S}\\s]", "");
        return effectiveCharacters.length() <= 1 ? "" : narration;
    }

    static boolean isInsufficientFreeformVoiceInput(String text) {
        String cleaned = VoiceCommandRouter.normalize(sanitizeTaskNarration(text));
        if (cleaned.length() < 2) {
            return true;
        }
        switch (cleaned) {
            case "你确":
            case "好的":
            case "好的你确":
                return true;
            default:
                return false;
        }
    }

    private static boolean isVoiceRecoveryPrompt(String text) {
        return "没有听清，请再说一次".equals(text)
                || "没有听清，请重新提问".equals(text)
                || "说话时间太短，请再说一次".equals(text)
                || text.startsWith("语音服务未连接，请检查");
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
        String detectionMarkersJson = "[]";
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
            ChatMessage copy = new ChatMessage(role, kind, text, imageId, imagePreviewBase64, streaming);
            copy.detectionMarkersJson = detectionMarkersJson;
            return copy;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("role", role);
                json.put("kind", kind);
                json.put("text", text);
                json.put("image_id", imageId);
                json.put("image_preview_base64", "");
                json.put("detection_markers", detectionMarkersJson);
                json.put("streaming", streaming);
            } catch (Exception ignored) {
            }
            return json;
        }

        static ChatMessage fromJson(JSONObject json) {
            ChatMessage message = new ChatMessage(
                    json.optString("role", "assistant"),
                    json.optString("kind", "text"),
                    json.optString("text", ""),
                    json.optString("image_id", ""),
                    json.optString("image_preview_base64", ""),
                    json.optBoolean("streaming", false));
            message.detectionMarkersJson = json.optString("detection_markers", "[]");
            return message;
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
            // The task workspace is a field repair aid, not a reasoning report. The final
            // user turn in the prompt is authoritative over older task memory.
            system.put("content", "你是" + APP_LABEL + "，面向现场运维人员。最后一条用户问题是本轮唯一待回答的问题；较早任务记忆只用于避免遗忘设备和已完成操作，不能替代或改写本轮问题。仅根据用户文字、任务记忆和实际提供的图片作答，不能编造现场观察。直接、简洁回答当前问题，不展示思维过程，不套固定栏目：询问原因或状态时直接说明；询问操作或维修时只给出一个当前最需要执行、可立即验证的原子操作，只能包含一个动作和一个检查对象，不得使用“及、或、以及、顿号”等并列结构；其余排查项留到用户反馈后再问，也不得罗列多个可能原因。只有证据不足时才说明无法确认及需要补拍的具体位置；只有确有必要时才建议专家协同。涉及带电、旋转、高温高压、泄漏、动火或无法确认的安全风险时，第一步必须要求停止操作并遵守现场规程。");
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
            StringBuilder rawResponse = new StringBuilder();
            boolean sawSseEvent = false;
            boolean deliveredText = false;
            while ((line = reader.readLine()) != null) {
                rawResponse.append(line).append('\n');
                String trimmed = line.trim();
                if (trimmed.length() == 0 || !trimmed.startsWith("data:")) {
                    continue;
                }
                sawSseEvent = true;
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                String delta = parseStreamingChatDelta(data);
                if (delta.length() > 0) {
                    deliveredText = true;
                    callback.onDelta(delta);
                }
            }
            if (!sawSseEvent) {
                String response = parseChatText(rawResponse.toString().trim()).trim();
                if (response.length() == 0) {
                    throw new IOException("direct_gpt_empty_response");
                }
                callback.onDelta(response);
                return;
            }
            if (!deliveredText) {
                throw new IOException("direct_gpt_empty_stream");
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
        private final BackendAuthorization backendAuthorization;
        private SessionProvider sessionProvider;
        private WebSocketRealtimeAsrSession websocketSession;

        BackendChatClient(String baseUrl, String apiKey) {
            this(baseUrl, BackendAuthorization.legacy(apiKey));
        }

        BackendChatClient(String baseUrl, DeviceAccessTokenProvider accessTokenProvider) {
            this(baseUrl, BackendAuthorization.session(accessTokenProvider));
        }

        private BackendChatClient(String baseUrl, BackendAuthorization backendAuthorization) {
            this.baseUrl = trimSlash(baseUrl == null || baseUrl.length() == 0
                    ? "https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses"
                    : baseUrl);
            this.backendAuthorization = backendAuthorization;
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
                        requireProvisionedBackend();
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
                        requireProvisionedBackend();
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
            if (!backendAuthorization.isProvisioned()) {
                callback.onError(new IllegalStateException("managed_backend_credential_missing"));
                return;
            }
            String sessionId = sessionProvider == null ? "" : sessionProvider.sessionId();
            websocketSession = new WebSocketRealtimeAsrSession(backendAsrUrl(sessionId), backendAuthorization, "fun-asr-realtime", new BackendRealtimeAsrCallback(callback), true);
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
            backendAuthorization.apply(connection);
            return connection;
        }

        private void requireProvisionedBackend() {
            if (!backendAuthorization.isProvisioned()) {
                throw new IllegalStateException("managed_backend_credential_missing");
            }
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

    private static final class ResilientRealtimeAsrClient implements RealtimeAsrClient {
        private static final long PRIMARY_FINAL_GRACE_MS = 1200L;

        private final RealtimeAsrClient primary;
        private final LocalAsrEngine local;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private RealtimeAsrCallback callback;
        private boolean primaryHasPartial;
        private boolean primaryDone;
        private boolean localDone;
        private boolean delivered;
        private String primaryDiagnostic = "asr_task_failed";
        private String localFinal = "";
        private Runnable localFinalFallback;

        ResilientRealtimeAsrClient(RealtimeAsrClient primary, LocalAsrEngine local) {
            this.primary = primary;
            this.local = local;
        }

        @Override
        public synchronized void start(RealtimeAsrCallback callback) {
            cancelLocalFinalFallback();
            this.callback = callback;
            primaryHasPartial = false;
            primaryDone = false;
            localDone = false;
            delivered = false;
            primaryDiagnostic = "asr_task_failed";
            localFinal = "";
            primary.start(new RealtimeAsrCallback() {
                @Override
                public void onPartial(String text) {
                    onPrimaryPartial(text);
                }

                @Override
                public void onFinal(String text) {
                    onPrimaryFinal(text);
                }

                @Override
                public void onUnclear(String diagnosticCode) {
                    onPrimaryUnclear(diagnosticCode);
                }

                @Override
                public void onError(Exception error) {
                    onPrimaryError(error);
                }
            });
            local.start(new LocalAsrEngine.Callback() {
                @Override
                public void onPartial(String text) {
                    onLocalPartial(text);
                }

                @Override
                public void onFinal(String text) {
                    onLocalFinal(text);
                }

                @Override
                public void onUnclear(String diagnosticCode) {
                    onLocalUnclear(diagnosticCode);
                }

                @Override
                public void onError(Exception error) {
                    onLocalError(error);
                }
            });
        }

        @Override
        public void acceptPcm(byte[] pcm, int length) {
            primary.acceptPcm(pcm, length);
            local.acceptPcm(pcm, length);
        }

        @Override
        public void finish(String stopReason) {
            primary.finish(stopReason);
            local.finish(stopReason);
        }

        @Override
        public synchronized void cancel() {
            cancelLocalFinalFallback();
            callback = null;
            delivered = true;
            primary.cancel();
            local.cancel();
        }

        private synchronized void onPrimaryPartial(String text) {
            if (callback == null || delivered) {
                return;
            }
            String partial = sanitizeTranscriptForDisplay(text);
            if (partial.length() == 0) {
                return;
            }
            primaryHasPartial = true;
            callback.onPartial(partial);
        }

        private synchronized void onPrimaryFinal(String text) {
            primaryDone = true;
            String finalText = sanitizeTranscriptForDisplay(text);
            if (finalText.length() > 0) {
                deliverFinal(finalText, "cloud");
            } else {
                onPrimaryUnclear("asr_final_empty");
            }
        }

        private synchronized void onPrimaryUnclear(String diagnosticCode) {
            primaryDone = true;
            primaryDiagnostic = diagnosticCode == null || diagnosticCode.trim().length() == 0
                    ? "asr_task_failed"
                    : diagnosticCode.trim();
            if (localFinal.length() > 0) {
                deliverFinal(localFinal, "local-after-primary-failure");
            } else if (localDone) {
                deliverUnclear(primaryDiagnostic);
            }
        }

        private synchronized void onPrimaryError(Exception error) {
            String message = error == null || error.getMessage() == null
                    ? "unknown"
                    : error.getMessage();
            onPrimaryUnclear("asr_error:" + message);
        }

        private synchronized void onLocalPartial(String text) {
            if (callback == null || delivered || primaryHasPartial) {
                return;
            }
            String partial = sanitizeTranscriptForDisplay(text);
            if (partial.length() > 0) {
                callback.onPartial(partial);
            }
        }

        private synchronized void onLocalFinal(String text) {
            localDone = true;
            localFinal = sanitizeTranscriptForDisplay(text);
            if (localFinal.length() == 0) {
                onLocalUnclear("local_asr_empty");
                return;
            }
            if (!primaryHasPartial || primaryDone) {
                deliverFinal(localFinal, "local");
                return;
            }
            cancelLocalFinalFallback();
            localFinalFallback = new Runnable() {
                @Override
                public void run() {
                    synchronized (ResilientRealtimeAsrClient.this) {
                        localFinalFallback = null;
                        if (!delivered && callback != null && localFinal.length() > 0) {
                            deliverFinal(localFinal, "local-timeout-fallback");
                        }
                    }
                }
            };
            handler.postDelayed(localFinalFallback, PRIMARY_FINAL_GRACE_MS);
        }

        private synchronized void onLocalUnclear(String diagnosticCode) {
            localDone = true;
            if (primaryDone && !delivered) {
                deliverUnclear(primaryDiagnostic);
            }
        }

        private synchronized void onLocalError(Exception error) {
            onLocalUnclear("local_asr_error");
        }

        private void deliverFinal(String text, String source) {
            if (callback == null || delivered) {
                return;
            }
            delivered = true;
            cancelLocalFinalFallback();
            Log.i(KEY_LOG_TAG, "Realtime ASR selected source=" + source
                    + " textChars=" + text.length());
            RealtimeAsrCallback target = callback;
            callback = null;
            primary.cancel();
            local.cancel();
            target.onFinal(text);
        }

        private void deliverUnclear(String diagnosticCode) {
            if (callback == null || delivered) {
                return;
            }
            delivered = true;
            cancelLocalFinalFallback();
            RealtimeAsrCallback target = callback;
            callback = null;
            primary.cancel();
            local.cancel();
            target.onUnclear(diagnosticCode);
        }

        private void cancelLocalFinalFallback() {
            if (localFinalFallback != null) {
                handler.removeCallbacks(localFinalFallback);
                localFinalFallback = null;
            }
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
        private final BackendAuthorization backendAuthorization;
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
            this.backendAuthorization = null;
            this.model = model == null || model.length() == 0 ? "fun-asr-realtime" : model;
            this.callback = callback;
            this.backendMode = backendMode;
        }

        WebSocketRealtimeAsrSession(
                String endpoint,
                BackendAuthorization backendAuthorization,
                String model,
                RealtimeAsrCallback callback,
                boolean backendMode) {
            this.endpoint = endpoint;
            this.apiKey = "";
            this.backendAuthorization = backendAuthorization;
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
            final Socket activeSocket;
            synchronized (lock) {
                // Cancellation is often requested by a main-thread timeout after a photo.
                // Mark the session closed immediately, but never let TLS shutdown block UI.
                closed = true;
                activeSocket = socket;
            }
            closeSocketAsync(activeSocket);
        }

        private void runSocket() {
            try {
                URI uri = URI.create(endpoint);
                boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
                Socket openedSocket = secure
                        ? SSLSocketFactory.getDefault().createSocket(uri.getHost(), port)
                        : new Socket(uri.getHost(), port);
                synchronized (lock) {
                    if (closed) {
                        closeSocketQuietly(openedSocket);
                        return;
                    }
                    socket = openedSocket;
                }
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
            if (backendAuthorization != null) {
                request.append("Authorization: Bearer ").append(backendAuthorization.bearerToken()).append("\r\n");
            } else if (apiKey.length() > 0) {
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
                            if ("task-failed".equals(eventName)) {
                                AsrProviderFailure failure =
                                        AsrProviderFailure.fromDashScopeFrame(text);
                                Log.e(KEY_LOG_TAG, "Realtime ASR provider failure code="
                                        + failure.diagnosticCode());
                                callback.onUnclear(failure.diagnosticCode());
                            } else {
                                callback.onUnclear("asr_task_finished");
                            }
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
            final Socket activeSocket;
            synchronized (lock) {
                closed = true;
                activeSocket = socket;
            }
            closeSocketQuietly(activeSocket);
        }

        private static void closeSocketAsync(final Socket socket) {
            if (socket == null) {
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    closeSocketQuietly(socket);
                }
            }, "FunAsrSocketClose").start();
        }

        private static void closeSocketQuietly(Socket socket) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
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
