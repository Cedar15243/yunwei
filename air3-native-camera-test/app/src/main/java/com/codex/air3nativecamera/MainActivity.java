package com.codex.air3nativecamera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA = 1001;
    private static final int REQUEST_AUDIO = 1002;
    private static final String EVENTS_ENDPOINT = GeneratedConfig.EVENTS_ENDPOINT;
    private static final String OPS_GLASSES_API_KEY = GeneratedConfig.OPS_GLASSES_API_KEY;
    private static final String PREF_SESSION_ID = "opsSessionId";
    private static final String PREF_CURRENT_STEP = "opsCurrentStep";
    private static final String PREF_LAST_RESPONSE = "opsLastResponse";
    private static final float GUIDE_FRAME_WIDTH_RATIO = 0.72f;
    private static final float GUIDE_FRAME_HEIGHT_RATIO = 0.50f;
    private static final float GUIDE_FRAME_TOP_OFFSET_RATIO = 0.27f;
    private static final boolean UPLOAD_FULL_CAMERA_JPEG = true;
    private static final int JPEG_QUALITY = 94;
    private static final long VOICE_RECORDING_MS = 10000L;
    private static final long VOICE_MIN_RECORDING_MS = 900L;
    private static final long VOICE_SILENCE_AFTER_SPEECH_MS = 1100L;
    private static final long VOICE_NO_SPEECH_TIMEOUT_MS = 3200L;
    private static final int VOICE_UPLOAD_READ_TIMEOUT_MS = 65000;
    private static final long VOICE_AMPLITUDE_POLL_MS = 180L;
    private static final int VOICE_SPEECH_AMPLITUDE_THRESHOLD = 900;
    private static final float VOICE_RELATIVE_SILENCE_RATIO = 0.70f;
    private static final int VOICE_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int VOICE_SAMPLE_RATE_HZ = 16000;
    private static final int VOICE_WAV_CHANNEL_COUNT = 1;
    private static final int VOICE_WAV_BITS_PER_SAMPLE = 16;
    private static final int VOICE_WAV_HEADER_BYTES = 44;
    private static final int HUD_PAGE_CHAR_LIMIT = 54;
    private static final String VOICE_STT_PROMPT =
            "中文普通话现场问题。常见短句：这个是什么、这是什么、有什么问题、下一步怎么做、帮我看屏幕报错。";

    private TextureView previewView;
    private TextView titleText;
    private TextView stepText;
    private TextView hintText;
    private TextView resultText;
    private TextView statusText;
    private TextView actionCaptureButton;
    private TextView actionVoiceButton;
    private TextView actionBackButton;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size captureSize = new Size(1280, 720);
    private Size previewSize = new Size(1280, 720);
    private int sensorOrientation;
    private String cameraId;
    private String sessionId = "";
    private String currentStep = "locate_server";
    private boolean captureInFlight;
    private AudioRecord voiceRecorder;
    private File voiceFile;
    private Thread voiceRecordThread;
    private final AtomicBoolean voiceRecordThreadRunning = new AtomicBoolean(false);
    private boolean recordingVoice;
    private long voiceRecordingStartedAt;
    private long voiceLastSpeechAt;
    private boolean voiceSpeechDetected;
    private volatile int voiceCurrentAmplitude;
    private int voicePeakAmplitude;
    private Runnable voiceStopRunnable;
    private Runnable voiceAmplitudeMonitor;
    private int activeVoiceGeneration;
    private int captureGeneration;
    private int interactionGeneration;
    private boolean centerKeyLongPressed;
    private HudResponse activeHud;
    private int activeHudPageIndex;
    private int cameraOpenRetryCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        sessionId = getPreferences(MODE_PRIVATE).getString(PREF_SESSION_ID, "");
        currentStep = getPreferences(MODE_PRIVATE).getString(PREF_CURRENT_STEP, "locate_server");
        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResultText("需要相机权限");
            setStatus("请允许相机权限，然后重新进入应用。");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }

        startCameraFlow();
    }

    @Override
    protected void onDestroy() {
        stopVoiceRecording(false, "destroy");
        closeCamera();
        stopCameraThread();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
            restoreLastHudResponse();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraFlow();
        } else {
            setResultText("没有相机权限");
            setStatus("相机权限未授权，无法采集现场画面。");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        android.util.Log.i("Air3NativeCameraTest", "key action=" + event.getAction()
                + " code=" + event.getKeyCode()
                + " name=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " repeat=" + event.getRepeatCount());
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (actionVoiceButton != null && isTouchInside(actionVoiceButton, event)) {
            android.util.Log.i("Air3NativeCameraTest", "Voice button dispatch touch action=" + event.getAction()
                    + " recording=" + recordingVoice);
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                showActionFeedback(actionVoiceButton, recordingVoice ? "结束录音" : "语音中");
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                handleVoiceButtonPress();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchInside(View view, MotionEvent event) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (recordingVoice) {
                stopVoiceRecording(true, "manual_finish");
                return true;
            }
            centerKeyLongPressed = false;
            event.startTracking();
            setStatus("中心键已按下：短按拍照，长按语音。");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            setStatus("相机键已记录。当前版本请使用中心点击拍照，避免系统快捷键退出应用。");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backHudPageOrRetake();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            centerKeyLongPressed = true;
            startCloudVoiceCapture();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (!recordingVoice && !centerKeyLongPressed) {
                advanceHudPageOrCapture("key-center");
            }
            centerKeyLongPressed = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(2, 8, 12));

        previewView = new TextureView(this);
        previewView.setAlpha(0.86f);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(42, 0, 0, 0));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        GuideOverlayView guideOverlay = new GuideOverlayView(this);
        root.addView(guideOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.HORIZONTAL);
        topPanel.setGravity(Gravity.CENTER_VERTICAL);
        topPanel.setPadding(28, 16, 28, 16);
        topPanel.setBackground(panelBackground(Color.argb(172, 5, 16, 20), Color.argb(210, 87, 255, 176), 2));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topParams.leftMargin = 32;
        topParams.rightMargin = 32;
        topParams.topMargin = 24;
        root.addView(topPanel, topParams);

        titleText = new TextView(this);
        titleText.setTextColor(Color.rgb(232, 255, 244));
        titleText.setTextSize(24);
        titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleText.setText("AI 运维眼镜  |  AI 运维现场指导");
        topPanel.addView(titleText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        stepText = new TextView(this);
        stepText.setTextColor(Color.rgb(87, 255, 176));
        stepText.setTextSize(20);
        stepText.setGravity(Gravity.CENTER);
        stepText.setPadding(20, 8, 20, 8);
        stepText.setBackground(panelBackground(Color.argb(120, 7, 36, 29), Color.rgb(87, 255, 176), 2));
        topPanel.addView(stepText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout centerPanel = new LinearLayout(this);
        centerPanel.setOrientation(LinearLayout.VERTICAL);
        centerPanel.setGravity(Gravity.CENTER);
        centerPanel.setPadding(40, 28, 40, 28);
        centerPanel.setBackground(panelBackground(Color.argb(138, 3, 12, 18), Color.argb(170, 87, 255, 176), 2));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.62f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(centerPanel, centerParams);

        resultText = new TextView(this);
        resultText.setTextColor(Color.rgb(87, 255, 176));
        resultText.setTextSize(32);
        resultText.setGravity(Gravity.CENTER);
        resultText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        centerPanel.addView(resultText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        hintText = new TextView(this);
        hintText.setTextColor(Color.rgb(218, 234, 245));
        hintText.setTextSize(22);
        hintText.setGravity(Gravity.CENTER);
        hintText.setPadding(0, 14, 0, 0);
        centerPanel.addView(hintText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(218, 234, 245));
        statusText.setTextSize(22);
        statusText.setGravity(Gravity.LEFT);
        statusText.setPadding(40, 18, 40, 18);
        statusText.setBackground(panelBackground(Color.argb(150, 1, 7, 10), Color.argb(120, 218, 234, 245), 1));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        statusParams.leftMargin = 32;
        statusParams.rightMargin = 32;
        statusParams.bottomMargin = 24;
        root.addView(statusText, statusParams);

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        actionParams.leftMargin = 28;
        actionParams.rightMargin = 28;
        actionParams.bottomMargin = 120;
        root.addView(actionBar, actionParams);

        actionCaptureButton = createActionButton("1", "中心点击", "拍照 / 下一步");
        actionVoiceButton = createActionButton("2", "长按中心", "语音确认 / 补充说明");
        actionBackButton = createActionButton("3", "返回键", "重拍 / 返回上一步");
        actionBar.addView(actionCaptureButton, actionButtonLayoutParams(0));
        actionBar.addView(actionVoiceButton, actionButtonLayoutParams(1));
        actionBar.addView(actionBackButton, actionButtonLayoutParams(2));

        attachActionButtonHandlers();
        setContentView(root);
        showHomeHud();
        restoreLastHudResponse();
    }

    private TextView createActionButton(String number, String title, String subtitle) {
        TextView button = new TextView(this);
        button.setText(number + "  " + title + "\n" + subtitle);
        button.setTextColor(Color.rgb(218, 234, 245));
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(28, 12, 28, 12);
        button.setBackground(panelBackground(Color.argb(172, 5, 16, 20), Color.argb(190, 87, 255, 176), 2));
        button.setClickable(true);
        button.setLongClickable(true);
        return button;
    }

    private LinearLayout.LayoutParams actionButtonLayoutParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        if (index > 0) {
            params.leftMargin = 16;
        }
        return params;
    }

    private void attachActionButtonHandlers() {
        actionCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showActionFeedback(actionCaptureButton, hasNextHudPage() ? "下一页" : "拍照中");
                advanceHudPageOrCapture("button-center");
            }
        });
        actionCaptureButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                showActionFeedback(actionCaptureButton, "语音中");
                startCloudVoiceCapture();
                return true;
            }
        });
        actionVoiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });
        actionVoiceButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                return true;
            }
        });
        actionVoiceButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                android.util.Log.i("Air3NativeCameraTest", "Voice button touch action=" + event.getAction()
                        + " recording=" + recordingVoice);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    showActionFeedback(actionVoiceButton, recordingVoice ? "结束录音" : "语音中");
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    handleVoiceButtonPress();
                    return true;
                }
                return true;
            }
        });
        actionBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showActionFeedback(actionBackButton, hasPreviousHudPage() ? "上一页" : "已重拍");
                backHudPageOrRetake();
            }
        });
        actionBackButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                showActionFeedback(actionBackButton, hasPreviousHudPage() ? "上一页" : "已重拍");
                backHudPageOrRetake();
                return true;
            }
        });
    }

    private void handleVoiceButtonPress() {
        if (recordingVoice) {
            android.util.Log.i("Air3NativeCameraTest", "Voice button manual finish");
            showActionFeedback(actionVoiceButton, "结束录音");
            stopVoiceRecording(true, "manual_finish");
            return;
        }
        showActionFeedback(actionVoiceButton, "语音中");
        startCloudVoiceCapture();
    }

    private void showActionFeedback(final TextView button, final String label) {
        if (button == null) {
            return;
        }
        button.setSelected(true);
        button.setBackground(panelBackground(Color.argb(220, 8, 42, 35), Color.rgb(87, 255, 176), 3));
        setStatus(label);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (button != null) {
                    button.setSelected(false);
                    button.setBackground(panelBackground(Color.argb(172, 5, 16, 20), Color.argb(190, 87, 255, 176), 2));
                }
            }
        }, 520L);
    }

    private void prepareRetake() {
        beginInteraction();
        activeHud = null;
        activeHudPageIndex = 0;
        stopVoiceRecording(false, "retake");
        captureInFlight = false;
        setResultText("准备重拍");
        hintText.setText("请重新对准需要判断的现场画面\n把关键内容放进绿色取景框");
        setStatus("已进入重拍准备。对准后单击继续采集。");
    }

    private void advanceHudPageOrCapture(String source) {
        if (hasNextHudPage()) {
            activeHudPageIndex += 1;
            renderHudPage();
            return;
        }
        captureStillImage(source);
    }

    private void backHudPageOrRetake() {
        if (hasPreviousHudPage()) {
            activeHudPageIndex -= 1;
            renderHudPage();
            return;
        }
        prepareRetake();
    }

    private boolean hasNextHudPage() {
        return activeHud != null && activeHud.totalPages > 1 && activeHudPageIndex < activeHud.totalPages - 1;
    }

    private boolean hasPreviousHudPage() {
        return activeHud != null && activeHud.totalPages > 1 && activeHudPageIndex > 0;
    }

    private static GradientDrawable panelBackground(int fillColor, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(strokeWidth, strokeColor);
        drawable.setCornerRadius(8f);
        return drawable;
    }

    private void showHomeHud() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(stepLabel(currentStep));
                resultText.setText("任务待开始");
                hintText.setText("请对准需要判断的现场画面\n中心点击开始采集，长按语音提问");
                statusText.setText("1 中心点击：拍照/下一步    2 长按中心：语音确认    3 返回键：重拍/上一步");
            }
        });
        android.util.Log.i("Air3NativeCameraTest", "Formal HUD home shown.");
    }

    private static final class GuideOverlayView extends View {
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GuideOverlayView(Activity activity) {
            super(activity);
            setWillNotDraw(false);
            framePaint.setColor(Color.rgb(87, 255, 176));
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(4f);

            dimPaint.setColor(Color.argb(42, 0, 0, 0));
            dimPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(Color.rgb(218, 234, 245));
            textPaint.setTextSize(28f);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float frameWidth = width * GUIDE_FRAME_WIDTH_RATIO;
            float frameHeight = height * GUIDE_FRAME_HEIGHT_RATIO;
            float left = (width - frameWidth) / 2f;
            float top = height * GUIDE_FRAME_TOP_OFFSET_RATIO;
            RectF frame = new RectF(left, top, left + frameWidth, top + frameHeight);

            canvas.drawRect(0f, 0f, width, frame.top, dimPaint);
            canvas.drawRect(0f, frame.bottom, width, height, dimPaint);
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint);
            canvas.drawRect(frame.right, frame.top, width, frame.bottom, dimPaint);

            canvas.drawRoundRect(frame, 12f, 12f, framePaint);
            float centerX = frame.centerX();
            float centerY = frame.centerY();
            canvas.drawLine(centerX - 42f, centerY, centerX + 42f, centerY, framePaint);
            canvas.drawLine(centerX, centerY - 42f, centerX, centerY + 42f, framePaint);
            canvas.drawText("让关键画面填满绿色框，内容清楚后再拍", centerX, frame.bottom - 30f, textPaint);
        }
    }

    private void startCloudVoiceCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setResultText("需要麦克风权限");
            setStatus("请允许麦克风权限，授权后长按可录音确认现场操作。");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (recordingVoice) {
            stopVoiceRecording(true, "manual_finish");
            return;
        }
        if (sessionId.length() == 0) {
            setResultText("请先拍照");
            setStatus("请先中心点击拍照创建运维会话，再长按进行语音确认。");
            return;
        }

        try {
            final int generation = beginInteraction();
            voiceFile = new File(getCacheDir(), "ops_voice_" + System.currentTimeMillis() + ".wav");
            int minBufferSize = AudioRecord.getMinBufferSize(
                    VOICE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) {
                throw new IllegalStateException("AudioRecord minBufferSize=" + minBufferSize);
            }
            final int bufferSize = Math.max(minBufferSize, VOICE_SAMPLE_RATE_HZ / 2);
            voiceRecorder = new AudioRecord(
                    VOICE_AUDIO_SOURCE,
                    VOICE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (voiceRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }
            voiceRecorder.startRecording();
            recordingVoice = true;
            activeVoiceGeneration = generation;
            voiceRecordingStartedAt = System.currentTimeMillis();
            voiceLastSpeechAt = voiceRecordingStartedAt;
            voiceSpeechDetected = false;
            voiceCurrentAmplitude = 0;
            voicePeakAmplitude = 0;
            voiceRecordThreadRunning.set(true);
            startVoiceRecordThread(voiceRecorder, voiceFile, bufferSize);
            setResultText("正在录音");
            setStatus("请说短句问题。说完后会自动上传，也可以再按中心键立即结束。");
            voiceStopRunnable = new Runnable() {
                @Override
                public void run() {
                    stopVoiceRecording(true, "max_duration");
                }
            };
            mainHandler.postDelayed(voiceStopRunnable, VOICE_RECORDING_MS);
            startVoiceAmplitudeMonitor(generation);
        } catch (Exception error) {
            recordingVoice = false;
            releaseVoiceRecorder();
            setResultText("录音失败");
            setStatus("语音录制失败，请改用中心点击拍照继续。");
            android.util.Log.w("Air3NativeCameraTest", "Voice recording failed", error);
        }
    }

    private void startVoiceAmplitudeMonitor(final int generation) {
        voiceAmplitudeMonitor = new Runnable() {
            @Override
            public void run() {
                if (!recordingVoice || generation != activeVoiceGeneration || voiceRecorder == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                int amplitude = voiceCurrentAmplitude;
                if (amplitude >= VOICE_SPEECH_AMPLITUDE_THRESHOLD) {
                    voiceSpeechDetected = true;
                    if (amplitude >= voiceDynamicSilenceThreshold()) {
                        voiceLastSpeechAt = now;
                    }
                    android.util.Log.i("Air3NativeCameraTest", "voice amplitude=" + amplitude + " speech=true");
                } else if (amplitude > 0) {
                    android.util.Log.i("Air3NativeCameraTest", "voice amplitude=" + amplitude + " speech=false");
                }
                long elapsed = now - voiceRecordingStartedAt;
                long silentMs = now - voiceLastSpeechAt;
                if (voiceSpeechDetected &&
                        elapsed >= VOICE_MIN_RECORDING_MS &&
                        silentMs >= VOICE_SILENCE_AFTER_SPEECH_MS) {
                    stopVoiceRecording(true, "silence_detected");
                    return;
                }
                if (!voiceSpeechDetected && elapsed >= VOICE_NO_SPEECH_TIMEOUT_MS) {
                    stopVoiceRecording(false, "no_speech_timeout");
                    return;
                }
                mainHandler.postDelayed(this, VOICE_AMPLITUDE_POLL_MS);
            }
        };
        mainHandler.postDelayed(voiceAmplitudeMonitor, VOICE_AMPLITUDE_POLL_MS);
    }

    private void startVoiceRecordThread(final AudioRecord recorder, final File outputFile, final int bufferSize) {
        voiceRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[bufferSize];
                int audioBytes = 0;
                RandomAccessFile output = null;
                try {
                    output = new RandomAccessFile(outputFile, "rw");
                    output.setLength(0);
                    writeWavHeader(output, 0);
                    while (voiceRecordThreadRunning.get()) {
                        int read = recorder.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            output.write(buffer, 0, read);
                            audioBytes += read;
                            int amplitude = voicePcmAmplitude(buffer, read);
                            voiceCurrentAmplitude = amplitude;
                            if (amplitude > 0) {
                                voicePeakAmplitude = Math.max(voicePeakAmplitude, amplitude);
                            }
                        }
                    }
                } catch (Exception error) {
                    android.util.Log.w("Air3NativeCameraTest", "Voice WAV writer failed", error);
                } finally {
                    if (output != null) {
                        try {
                            output.seek(0);
                            writeWavHeader(output, audioBytes);
                            output.close();
                        } catch (Exception closeError) {
                            android.util.Log.w("Air3NativeCameraTest", "Voice WAV finalize failed", closeError);
                        }
                    }
                }
            }
        }, "Air3VoiceWavRecorder");
        voiceRecordThread.start();
    }

    private static int voicePcmAmplitude(byte[] buffer, int length) {
        int peak = 0;
        int safeLength = length - (length % 2);
        for (int index = 0; index < safeLength; index += 2) {
            int low = buffer[index] & 0xff;
            int high = buffer[index + 1];
            int sample = (high << 8) | low;
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static void writeWavHeader(RandomAccessFile output, int pcmDataBytes) throws IOException {
        int byteRate = VOICE_SAMPLE_RATE_HZ * VOICE_WAV_CHANNEL_COUNT * VOICE_WAV_BITS_PER_SAMPLE / 8;
        int blockAlign = VOICE_WAV_CHANNEL_COUNT * VOICE_WAV_BITS_PER_SAMPLE / 8;
        output.writeBytes("RIFF");
        writeLittleEndianInt(output, 36 + pcmDataBytes);
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
        writeLittleEndianInt(output, pcmDataBytes);
    }

    private static void writeLittleEndianInt(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }

    private static void writeLittleEndianShort(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private int voiceDynamicSilenceThreshold() {
        return Math.max(VOICE_SPEECH_AMPLITUDE_THRESHOLD, Math.round(voicePeakAmplitude * VOICE_RELATIVE_SILENCE_RATIO));
    }

    private void cancelVoiceTimers() {
        if (voiceStopRunnable != null) {
            mainHandler.removeCallbacks(voiceStopRunnable);
            voiceStopRunnable = null;
        }
        if (voiceAmplitudeMonitor != null) {
            mainHandler.removeCallbacks(voiceAmplitudeMonitor);
            voiceAmplitudeMonitor = null;
        }
    }

    private void stopVoiceRecording(boolean upload, String stopReason) {
        if (!recordingVoice && voiceRecorder == null) {
            return;
        }
        cancelVoiceTimers();
        File finishedFile = voiceFile;
        long durationMs = voiceRecordingStartedAt > 0 ? System.currentTimeMillis() - voiceRecordingStartedAt : 0L;
        int generation = activeVoiceGeneration;
        try {
            if (voiceRecorder != null) {
                voiceRecorder.stop();
            }
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Voice recorder stop failed", error);
        } finally {
            recordingVoice = false;
            voiceRecordThreadRunning.set(false);
            releaseVoiceRecorder();
            waitForVoiceRecordThread();
        }
        if (upload && finishedFile != null && finishedFile.exists() && finishedFile.length() > 0) {
            if (durationMs < VOICE_MIN_RECORDING_MS) {
                stopReason = "too_short";
                persistVoiceDiagnostics(finishedFile.length(), durationMs, "VOICE_RECOGNITION_WAV", stopReason);
                setResultText("录音太短");
                setHintText("请至少说满一句完整问题\n说完后停顿一下，系统会自动结束");
                setStatus("没有录到完整问题。请按语音按钮后说完一句话，再停顿一下。");
                return;
            }
            persistVoiceDiagnostics(finishedFile.length(), durationMs, "VOICE_RECOGNITION_WAV", stopReason);
            setResultText("语音上传中");
            setStatus("语音已录制，正在上传给 AI 转文字。");
            uploadVoiceAudio(finishedFile, durationMs, stopReason, generation);
        } else if ("no_speech_timeout".equals(stopReason)) {
            persistVoiceDiagnostics(finishedFile == null ? 0 : finishedFile.length(), durationMs, "VOICE_RECOGNITION_WAV", stopReason);
            setResultText("没有听到声音");
            setHintText("没有检测到有效语音\n请靠近眼镜麦克风后再问一次");
            setStatus("没有听到有效语音。请靠近眼镜麦克风，说完一句话后停顿。");
        }
    }

    private void releaseVoiceRecorder() {
        if (voiceRecorder == null) {
            return;
        }
        try {
            voiceRecorder.release();
        } catch (Exception ignored) {
        }
        voiceRecorder = null;
    }

    private void waitForVoiceRecordThread() {
        if (voiceRecordThread == null) {
            return;
        }
        try {
            voiceRecordThread.join(1200L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        voiceRecordThread = null;
    }

    private void uploadVoiceAudio(File audioFile, long recordingMs, String stopReason, int generation) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    byte[] audioBytes = readFileBytes(audioFile);
                    JSONObject payload = new JSONObject();
                    payload.put("audioBase64", "data:audio/wav;base64," + Base64.encodeToString(audioBytes, Base64.NO_WRAP));
                    payload.put("audioFormat", "audio/wav");
                    payload.put("expectedLanguage", "zh");
                    payload.put("sttPrompt", VOICE_STT_PROMPT);
                    payload.put("recordingMs", recordingMs);
                    payload.put("stopReason", stopReason);
                    payload.put("timestamp", System.currentTimeMillis());
                    payload.put("source", "air3-audio-record-wav");
                    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                    if (sessionId.length() == 0) {
                        throw new IllegalStateException("No ops session yet. Tap once to create session.");
                    }
                    connection = (HttpURLConnection) new URL(voiceEndpoint()).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(VOICE_UPLOAD_READ_TIMEOUT_MS);
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("x-ops-glasses-key", OPS_GLASSES_API_KEY);
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }

                    int status = connection.getResponseCode();
                    InputStream input = status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();
                    String responseText = readAll(input);
                    persistVoiceDiagnostics(audioBytes.length, recordingMs, "VOICE_RECOGNITION_WAV", stopReason);
                    persistVoiceResponse(status, audioBytes.length, responseText, null);
                    if (status < 200 || status >= 300) {
                        throw new IOException("voice_upload_http_" + status + ": " + responseText);
                    }
                    JSONObject response = new JSONObject(responseText);
                    HudResponse hud = new HudResponse(response, sessionId, currentStep);
                    applyHudResponseIfCurrent(hud, generation);
                    android.util.Log.i("Air3NativeCameraTest",
                            "Voice OK http=" + status + " bytes=" + audioBytes.length
                                    + " resultType=" + hud.resultType
                                    + " feedbackCode=" + hud.feedbackCode
                                    + " step=" + hud.step);
                } catch (Exception error) {
                    setResultText("语音同步失败");
                    setHintText("语音没有同步到 AI\n请确认网络后重试，或单击重新拍照");
                    setStatus("语音没有同步成功，请改用单击拍照继续。");
                    persistVoiceDiagnostics(audioFile.length(), recordingMs, "VOICE_RECOGNITION_WAV", stopReason);
                    persistVoiceResponse(-1, audioFile.length(), "", error);
                    android.util.Log.w("Air3NativeCameraTest", "Voice upload failed", error);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }, "Air3VoiceAudioUpload").start();
    }

    private void startCameraFlow() {
        startCameraThread();
        setResultText("正在打开相机");
        setStatus("正在准备眼镜相机预览。");

        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                configurePreviewTransform();
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
        cameraThread = new HandlerThread("Air3NativeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join(1500);
        } catch (InterruptedException ignored) {
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (cameraDevice != null) {
            return;
        }
        if (cameraHandler == null) {
            startCameraThread();
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraId = chooseCameraId(manager);
            if (cameraId == null) {
                setResultText("未找到相机");
                setStatus("没有可用相机，无法采集现场画面。");
                return;
            }

            logCameraCapabilities(manager, cameraId);
            sensorOrientation = readSensorOrientation(manager, cameraId);
            captureSize = chooseCaptureSize(manager, cameraId);
            previewSize = choosePreviewSize(manager, cameraId);
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    ImageFormat.JPEG,
                    2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    MainActivity.this.onImageAvailable(reader);
                }
            }, cameraHandler);

            setStatus("正在打开相机，请保持眼镜稳定。");
            android.util.Log.i("Air3NativeCameraTest", "Opening camera " + cameraId
                    + " capture=" + captureSize.getWidth() + "x" + captureSize.getHeight()
                    + " preview=" + previewSize.getWidth() + "x" + previewSize.getHeight());
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraOpenRetryCount = 0;
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    setStatus("相机连接中断，正在尝试恢复。");
                    retryOpenCamera("disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    setResultText("相机异常");
                    setStatus("相机暂时不可用，正在自动重试。");
                    android.util.Log.w("Air3NativeCameraTest", "Camera open error=" + error);
                    retryOpenCamera("error=" + error);
                }
            }, cameraHandler);
        } catch (Exception error) {
            setResultText("相机启动失败");
            setStatus("相机启动失败，正在自动重试。");
            android.util.Log.w("Air3NativeCameraTest", "Camera failed", error);
            retryOpenCamera(error.getClass().getSimpleName());
        }
    }

    private void retryOpenCamera(String reason) {
        if (cameraHandler == null || isFinishing()) {
            return;
        }
        if (cameraOpenRetryCount >= 3) {
            setStatus("相机仍未就绪，请退出后重新进入应用，或关闭其他占用相机的程序。");
            return;
        }
        cameraOpenRetryCount += 1;
        closeCamera();
        final int retryNumber = cameraOpenRetryCount;
        setStatus("相机未就绪，正在第 " + retryNumber + " 次重试。");
        cameraHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                openCamera();
            }
        }, 900L * retryNumber);
    }

    private String chooseCameraId(CameraManager manager) throws CameraAccessException {
        String first = null;
        for (String id : manager.getCameraIdList()) {
            if (first == null) {
                first = id;
            }
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            android.util.Log.i("Air3NativeCameraTest", "candidate cameraId=" + id + " facing=" + facing);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return first;
    }

    private Size chooseCaptureSize(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1920, 1080);
        }

        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(1920, 1080);
        }

        Size best = sizes[0];
        long bestPixels = (long) best.getWidth() * (long) best.getHeight();
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * (long) size.getHeight();
            boolean usable = size.getWidth() >= 1920 && size.getHeight() >= 1080;
            if (usable && pixels > bestPixels) {
                best = size;
                bestPixels = pixels;
            }
        }
        return best;
    }

    private Size choosePreviewSize(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1920, 1080);
        }

        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(1920, 1080);
        }

        Size best = sizes[0];
        double targetAspect = (double) captureSize.getWidth() / (double) captureSize.getHeight();
        double bestScore = Double.MAX_VALUE;
        for (Size size : sizes) {
            double aspect = (double) size.getWidth() / (double) size.getHeight();
            double aspectPenalty = Math.abs(aspect - targetAspect) * 10000.0;
            double pixelDelta = Math.abs(((double) size.getWidth() * (double) size.getHeight())
                    - ((double) captureSize.getWidth() * (double) captureSize.getHeight())) / 100000.0;
            double undersizePenalty = (size.getWidth() < 1280 || size.getHeight() < 720) ? 5000.0 : 0.0;
            double score = aspectPenalty + pixelDelta + undersizePenalty;
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private void logCameraCapabilities(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Log.i("Air3NativeCameraTest",
                "cameraId=" + id + " sensorOrientation=" + sensorOrientation);
        if (map == null) {
            android.util.Log.w("Air3NativeCameraTest", "No StreamConfigurationMap");
            return;
        }
        android.util.Log.i("Air3NativeCameraTest",
                "JPEG sizes=" + Arrays.toString(map.getOutputSizes(ImageFormat.JPEG)));
        android.util.Log.i("Air3NativeCameraTest",
                "Preview sizes=" + Arrays.toString(map.getOutputSizes(SurfaceTexture.class)));
    }

    private int readSensorOrientation(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return orientation == null ? 0 : orientation;
    }

    private void configurePreviewTransform() {
        if (previewView == null || previewSize == null) {
            return;
        }
        int viewWidth = previewView.getWidth();
        int viewHeight = previewView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }

        float bufferWidth = previewSize.getWidth();
        float bufferHeight = previewSize.getHeight();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0f, 0f, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0f, 0f, bufferWidth, bufferHeight);
        float scale = Math.max(viewRect.width() / bufferRect.width(), viewRect.height() / bufferRect.height());
        float scaledWidth = bufferRect.width() * scale;
        float scaledHeight = bufferRect.height() * scale;
        float dx = (viewRect.width() - scaledWidth) / 2f;
        float dy = (viewRect.height() - scaledHeight) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        int previewRotation = previewRotationDegrees();
        if (previewRotation != 0) {
            matrix.postRotate(previewRotation, viewRect.centerX(), viewRect.centerY());
        }
        previewView.setTransform(matrix);
        android.util.Log.i("Air3NativeCameraTest", "Preview transform view="
                + viewWidth + "x" + viewHeight + " buffer="
                + previewSize.getWidth() + "x" + previewSize.getHeight()
                + " sensorOrientation=" + sensorOrientation
                + " previewRotation=" + previewRotation);
    }

    private int previewRotationDegrees() {
        return 0;
    }

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null || cameraDevice == null || imageReader == null) {
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configurePreviewTransform();
            Surface previewSurface = new Surface(texture);

            CaptureRequest.Builder previewRequest =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(previewSurface);
            previewRequest.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(
                                        previewRequest.build(),
                                        null,
                                        cameraHandler);
                                if (!restoreLastHudResponse()) {
                                    showHomeHud();
                                    setStatus("相机已就绪。请把现场关键画面放入绿色框内，单击开始 AI 反馈。");
                                }
                            } catch (CameraAccessException error) {
                                setResultText("预览失败");
                                setStatus("相机预览启动失败，请重新进入应用。");
                                android.util.Log.w("Air3NativeCameraTest", "Preview repeating request failed", error);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            setResultText("预览失败");
                            setStatus("相机预览配置失败，请重新进入应用。");
                        }
                    },
                    cameraHandler);
        } catch (Exception error) {
            setResultText("预览失败");
            setStatus("相机预览启动失败，请重新进入应用。");
            android.util.Log.w("Air3NativeCameraTest", "Preview failed", error);
        }
    }

    private void captureStillImage(String source) {
        if (captureInFlight || cameraDevice == null || captureSession == null || imageReader == null) {
            setStatus("相机正在准备或上一张仍在处理，请稍候再试。");
            return;
        }

        captureGeneration = beginInteraction();
        final int generation = captureGeneration;
        captureInFlight = true;
        try {
            CaptureRequest.Builder request =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.JPEG_ORIENTATION, 0);
            setResultText("正在采集");
            hintText.setText("保持画面稳定\n请等待 AI 分析结果");
            setStatus("正在采集现场画面，请保持稳定。");
            android.util.Log.i("Air3NativeCameraTest", "Capturing JPEG source=" + source);
            captureSession.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    if (!isCurrentInteraction(generation)) {
                        android.util.Log.i("Air3NativeCameraTest",
                                "Skip stale capture completion generation=" + generation);
                        return;
                    }
                    setStatus("照片已采集，正在读取画面。");
                }
            }, cameraHandler);
        } catch (Exception error) {
            captureInFlight = false;
            setResultText("拍照失败");
            setStatus("拍照失败，请稍后重试。");
            android.util.Log.w("Air3NativeCameraTest", "Capture failed", error);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) {
                captureInFlight = false;
                setStatus("本次没有读到照片，请重新拍摄。");
                return;
            }

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            persistRawCapture(bytes);
            final int generation = captureGeneration;
            if (!isCurrentInteraction(generation)) {
                android.util.Log.i("Air3NativeCameraTest", "Skip stale captured image before UI generation=" + generation);
                return;
            }
            setResultText("AI 分析中");
            hintText.setText("照片已上传\n正在生成现场反馈");
            setStatus("正在处理取景框内画面，并上传给 AI 分析。");
            android.util.Log.i("Air3NativeCameraTest", "JPEG image bytes=" + bytes.length);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    uploadImage(bytes, generation);
                }
            }, "Air3NativeUpload").start();
        } catch (Exception error) {
            captureInFlight = false;
            setResultText("照片读取失败");
            setStatus("照片读取失败，请重新拍摄。");
            android.util.Log.w("Air3NativeCameraTest", "Image failed", error);
        }
    }

    private void uploadImage(byte[] jpegBytes, int generation) {
        HttpURLConnection connection = null;
        try {
            if (!isCurrentInteraction(generation)) {
                android.util.Log.i("Air3NativeCameraTest", "Skip stale image upload before network generation=" + generation);
                return;
            }
            UploadImage uploadImage = prepareUploadJpeg(jpegBytes);
            if (!isCurrentInteraction(generation)) {
                android.util.Log.i("Air3NativeCameraTest",
                        "Skip stale image upload after prepare generation=" + generation);
                return;
            }
            String imageBase64 = Base64.encodeToString(uploadImage.bytes, Base64.NO_WRAP);
            JSONObject payload = new JSONObject();
            payload.put("sessionId", sessionId);
            payload.put("taskType", "general_scene_feedback");
            payload.put("step", currentStep);
            payload.put("action", actionForCurrentStep());
            payload.put("imageKind", "field_scene");
            payload.put("imageBase64", "data:image/jpeg;base64," + imageBase64);
            payload.put("width", uploadImage.width);
            payload.put("height", uploadImage.height);
            payload.put("format", "jpg");
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("source", "native-camera2");
            payload.put("preprocess", uploadImage.preprocess);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            if (OPS_GLASSES_API_KEY.length() == 0) {
                throw new IllegalStateException("OPS_GLASSES_API_KEY missing in APK build.");
            }
            persistDebugUpload(uploadImage.bytes);
            setStatus("真实照片已采集，正在上传给 AI 分析现场画面。");
            android.util.Log.i("Air3NativeCameraTest",
                    "Uploading focused JPEG bytes=" + uploadImage.bytes.length
                            + " size=" + uploadImage.width + "x" + uploadImage.height
                            + " preprocess=" + uploadImage.preprocess
                            + " originalBytes=" + jpegBytes.length);
            connection = (HttpURLConnection) new URL(EVENTS_ENDPOINT).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-ops-glasses-key", OPS_GLASSES_API_KEY);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseText = readAll(input);
            if (status < 200 || status >= 300) {
                throw new IOException("image_upload_http_" + status + ": " + responseText);
            }
            JSONObject response = new JSONObject(responseText);
            HudResponse hud = new HudResponse(response, sessionId, currentStep);
            int imageBytes = response.optInt("imageBytes", uploadImage.bytes.length);
            applyHudResponseIfCurrent(hud, generation);
            android.util.Log.i("Air3NativeCameraTest", String.format(Locale.US,
                    "OK http=%d step=%s session=%s resultType=%s feedbackCode=%s serverImageBytes=%d uploadBytes=%d rawBytes=%d",
                    status, hud.step, shortSessionId(hud.sessionId), hud.resultType, hud.feedbackCode,
                    imageBytes, uploadImage.bytes.length, jpegBytes.length));
        } catch (Exception error) {
            if (isCurrentInteraction(generation)) {
                setResultText("网络连接失败");
                setHintText("暂时连接不到 AI 运维服务\n请确认网络后单击中心重试");
                setStatus("暂时连接不到 AI 运维服务。请确认网络后单击重试。");
            }
            android.util.Log.w("Air3NativeCameraTest", "Upload failed", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            captureInFlight = false;
        }
    }

    private UploadImage prepareUploadJpeg(byte[] jpegBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (bitmap == null || bitmap.getWidth() < 640 || bitmap.getHeight() < 360) {
            return new UploadImage(
                    jpegBytes,
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    "raw-camera-jpeg-decode-fallback");
        }

        Bitmap normalized = normalizeCameraBitmap(bitmap);
        if (normalized != bitmap) {
            bitmap.recycle();
        }

        if (UPLOAD_FULL_CAMERA_JPEG) {
            byte[] result = encodeJpeg(normalized, JPEG_QUALITY);
            int width = normalized.getWidth();
            int height = normalized.getHeight();
            normalized.recycle();
            android.util.Log.i("Air3NativeCameraTest",
                    "Prepared normalized full camera JPEG size=" + width + "x" + height
                            + " bytes=" + result.length
                            + " sensorOrientation=" + sensorOrientation);
            return new UploadImage(result.length > 0 ? result : jpegBytes,
                    result.length > 0 ? width : captureSize.getWidth(),
                    result.length > 0 ? height : captureSize.getHeight(),
                    result.length > 0 ? "normalized-full-camera-jpeg" : "raw-camera-jpeg-encode-fallback");
        }

        int width = normalized.getWidth();
        int height = normalized.getHeight();
        int cropWidth = Math.max(640, Math.round(width * GUIDE_FRAME_WIDTH_RATIO));
        int cropHeight = Math.max(360, Math.round(height * GUIDE_FRAME_HEIGHT_RATIO));
        int left = Math.max(0, Math.round((width - cropWidth) / 2f));
        int top = Math.max(0, Math.round(height * GUIDE_FRAME_TOP_OFFSET_RATIO));
        if (left + cropWidth > width) {
            cropWidth = width - left;
        }
        if (top + cropHeight > height) {
            cropHeight = height - top;
        }

        Bitmap uploadBitmap = Bitmap.createBitmap(normalized, left, top, cropWidth, cropHeight);
        byte[] result = encodeJpeg(uploadBitmap, JPEG_QUALITY);
        android.util.Log.i("Air3NativeCameraTest",
                "Prepared upload crop source=" + width + "x" + height
                        + " crop=" + cropWidth + "x" + cropHeight
                        + " left=" + left + " top=" + top
                        + " bytes=" + result.length);
        uploadBitmap.recycle();
        normalized.recycle();
        return new UploadImage(
                result.length > 0 ? result : jpegBytes,
                result.length > 0 ? cropWidth : captureSize.getWidth(),
                result.length > 0 ? cropHeight : captureSize.getHeight(),
                result.length > 0 ? "normalized-center-guide-crop" : "raw-camera-jpeg-encode-fallback");
    }

    private Bitmap normalizeCameraBitmap(Bitmap source) {
        int rotation = uploadRotationDegrees();
        if (rotation == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        android.util.Log.i("Air3NativeCameraTest",
                "Normalized camera bitmap from=" + source.getWidth() + "x" + source.getHeight()
                        + " to=" + rotated.getWidth() + "x" + rotated.getHeight()
                        + " rotation=" + rotation);
        return rotated;
    }

    private int uploadRotationDegrees() {
        if (sensorOrientation == 270) {
            return 180;
        }
        if (sensorOrientation == 90) {
            return 0;
        }
        return 0;
    }

    private static final class HudResponse {
        final String rawResponse;
        final String sessionId;
        final String step;
        final String resultType;
        final String feedbackCode;
        final String diagnosticCode;
        final String displayTitle;
        final String displayText;
        final String displayHint;
        final String[] displayPages;
        final int totalPages;
        final boolean humanEscalationSuggestion;

        HudResponse(JSONObject response, String fallbackSessionId, String fallbackStep) {
            rawResponse = response.toString();
            sessionId = response.optString("sessionId", fallbackSessionId);
            step = response.optString("step", fallbackStep);
            resultType = response.optString("resultType", "instruction");
            feedbackCode = response.optString("feedbackCode", "");
            diagnosticCode = response.optString("diagnosticCode", "");
            String legacyText = response.optString("text", "");
            displayTitle = response.optString("displayTitle", firstInstructionLine(legacyText));
            displayText = response.optString("displayText", legacyText);
            displayHint = response.optString("displayHint", "");
            String fullText = response.optString("fullText", displayText);
            displayPages = parseDisplayPages(response, fullText);
            totalPages = displayPages.length;
            humanEscalationSuggestion = response.optBoolean("humanEscalationSuggestion", false);
        }
    }

    private static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
        return output.toByteArray();
    }

    private static final class UploadImage {
        final byte[] bytes;
        final int width;
        final int height;
        final String preprocess;

        UploadImage(byte[] bytes, int width, int height, String preprocess) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.preprocess = preprocess;
        }
    }

    private void persistDebugUpload(byte[] uploadBytes) {
        try (OutputStream output = openFileOutput("last_upload_crop.jpg", MODE_PRIVATE)) {
            output.write(uploadBytes);
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Persist upload crop failed", error);
        }
        try (OutputStream output = openFileOutput("last_upload_full.jpg", MODE_PRIVATE)) {
            output.write(uploadBytes);
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Persist upload full failed", error);
        }
    }

    private void persistRawCapture(byte[] jpegBytes) {
        try (OutputStream output = openFileOutput("last_raw_capture.jpg", MODE_PRIVATE)) {
            output.write(jpegBytes);
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Persist raw capture failed", error);
        }
    }

    private void persistVoiceResponse(int status, long audioBytes, String responseText, Exception error) {
        try {
            JSONObject diagnostic = new JSONObject();
            diagnostic.put("status", status);
            diagnostic.put("audioBytes", audioBytes);
            diagnostic.put("response", responseText == null ? "" : responseText);
            diagnostic.put("timestamp", System.currentTimeMillis());
            if (error != null) {
                diagnostic.put("errorType", error.getClass().getName());
                diagnostic.put("errorMessage", error.getMessage() == null ? "" : error.getMessage());
            }
            try (OutputStream output = openFileOutput("last_voice_response.json", MODE_PRIVATE)) {
                output.write(diagnostic.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void persistVoiceDiagnostics(long audioBytes, long recordingMs, String audioSource, String stopReason) {
        try {
            JSONObject diagnostic = new JSONObject();
            diagnostic.put("audioBytes", audioBytes);
            diagnostic.put("recordingMs", recordingMs);
            diagnostic.put("audioSource", audioSource);
            diagnostic.put("stopReason", stopReason);
            diagnostic.put("peakAmplitude", voicePeakAmplitude);
            diagnostic.put("expectedLanguage", "zh");
            diagnostic.put("sttPrompt", VOICE_STT_PROMPT);
            diagnostic.put("timestamp", System.currentTimeMillis());
            try (OutputStream output = openFileOutput("last_voice_diagnostics.json", MODE_PRIVATE)) {
                output.write(diagnostic.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private String actionForCurrentStep() {
        if ("run_diagnostic_command".equals(currentStep) || "confirm_diagnostic_output".equals(currentStep)) {
            return "diagnostic_output_uploaded";
        }
        if ("run_recovery_command".equals(currentStep)) {
            return "recovery_output_uploaded";
        }
        return "console_photo_uploaded";
    }

    private String voiceEndpoint() {
        return EVENTS_ENDPOINT.replace("/sessions/events", "/sessions/" + sessionId + "/voice");
    }

    private void persistSession(String nextSessionId, String nextStep) {
        if (nextSessionId == null) {
            nextSessionId = "";
        }
        if (nextStep == null || nextStep.length() == 0) {
            nextStep = "locate_server";
        }
        sessionId = nextSessionId;
        currentStep = nextStep;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(stepLabel(currentStep));
            }
        });
        getPreferences(MODE_PRIVATE)
                .edit()
                .putString(PREF_SESSION_ID, sessionId)
                .putString(PREF_CURRENT_STEP, currentStep)
                .apply();
    }

    private int beginInteraction() {
        interactionGeneration += 1;
        android.util.Log.i("Air3NativeCameraTest", "begin interaction generation=" + interactionGeneration);
        return interactionGeneration;
    }

    private boolean isCurrentInteraction(int generation) {
        return generation == interactionGeneration;
    }

    private void applyHudResponseIfCurrent(final HudResponse hud, int generation) {
        if (!isCurrentInteraction(generation)) {
            android.util.Log.i("Air3NativeCameraTest", "Skip stale HUD response generation=" + generation
                    + " current=" + interactionGeneration);
            return;
        }
        applyHudResponse(hud);
    }

    private void applyHudResponse(final HudResponse hud) {
        persistSession(hud.sessionId, hud.step);
        persistLastResponse(hud.rawResponse);
        activeHud = hud;
        activeHudPageIndex = 0;
        renderHudPage();
    }

    private void renderHudPage() {
        final HudResponse hud = activeHud;
        if (hud == null) {
            return;
        }
        if (activeHudPageIndex < 0) {
            activeHudPageIndex = 0;
        }
        if (activeHudPageIndex >= hud.totalPages) {
            activeHudPageIndex = Math.max(0, hud.totalPages - 1);
        }
        final int pageIndex = activeHudPageIndex;
        final int totalPages = hud.totalPages;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(stepLabel(hud.step));
                resultText.setText(hud.displayTitle.length() == 0 ? firstInstructionLine(hud.displayText) : hud.displayTitle);
                String pageText = pageTextForHud(hud, pageIndex);
                String pageLabel = totalPages > 1
                        ? "\n第 " + (pageIndex + 1) + "/" + totalPages + " 页"
                        : "";
                String hint = hud.displayHint.length() == 0 ? fallbackHint(hud) : hud.displayHint;
                String diagnostic = diagnosticHint(hud);
                hintText.setText(pageText + pageLabel + "\n" + hint + diagnostic);
                statusText.setText(statusForHud(hud));
            }
        });
    }

    private void persistLastResponse(String responseText) {
        getPreferences(MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_RESPONSE, responseText)
                .apply();
        try (OutputStream output = openFileOutput("last_ops_response.json", MODE_PRIVATE)) {
            output.write(responseText.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Persist last response failed", error);
        }
    }

    private boolean restoreLastHudResponse() {
        final String lastResponse = getPreferences(MODE_PRIVATE).getString(PREF_LAST_RESPONSE, "");
        if (lastResponse == null || lastResponse.trim().length() == 0) {
            return false;
        }
        try {
            HudResponse hud = new HudResponse(new JSONObject(lastResponse), sessionId, currentStep);
            applyHudResponse(hud);
            android.util.Log.i("Air3NativeCameraTest", "Restored last HUD response.");
            return true;
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Restore last HUD response failed", error);
            return false;
        }
    }

    private static String firstInstructionLine(String text) {
        if (text == null || text.trim().length() == 0) {
            return "等待 AI 指导";
        }
        String normalized = text.replace('\r', '\n').trim();
        int newline = normalized.indexOf('\n');
        String first = newline >= 0 ? normalized.substring(0, newline).trim() : normalized;
        return first.length() > 32 ? first.substring(0, 32) : first;
    }

    private static String[] parseDisplayPages(JSONObject response, String fallbackText) {
        JSONArray pages = response.optJSONArray("displayPages");
        if (pages == null || pages.length() == 0) {
            String text = fallbackText == null || fallbackText.trim().length() == 0 ? "请按提示继续。" : fallbackText.trim();
            return paginateLocalHudText(text);
        }
        String[] result = new String[pages.length()];
        int count = 0;
        for (int i = 0; i < pages.length(); i++) {
            String page = pages.optString(i, "").trim();
            if (page.length() == 0) {
                continue;
            }
            result[count] = page;
            count += 1;
        }
        if (count == 0) {
            String text = fallbackText == null || fallbackText.trim().length() == 0 ? "请按提示继续。" : fallbackText.trim();
            return paginateLocalHudText(text);
        }
        return Arrays.copyOf(result, count);
    }

    private static String[] paginateLocalHudText(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() == 0) {
            return new String[]{"请按提示继续。"};
        }
        if (normalized.length() <= HUD_PAGE_CHAR_LIMIT) {
            return new String[]{normalized};
        }
        int pageCount = (normalized.length() + HUD_PAGE_CHAR_LIMIT - 1) / HUD_PAGE_CHAR_LIMIT;
        String[] pages = new String[pageCount];
        int count = 0;
        for (int index = 0; index < normalized.length(); index += HUD_PAGE_CHAR_LIMIT) {
            int end = Math.min(normalized.length(), index + HUD_PAGE_CHAR_LIMIT);
            pages[count] = normalized.substring(index, end).trim();
            count += 1;
        }
        return Arrays.copyOf(pages, count);
    }

    private static String pageTextForHud(HudResponse hud, int pageIndex) {
        if (hud.displayPages.length == 0) {
            return hud.displayText;
        }
        int safeIndex = Math.max(0, Math.min(pageIndex, hud.displayPages.length - 1));
        return hud.displayPages[safeIndex];
    }

    private static String stepLabel(String step) {
        if ("run_diagnostic_command".equals(step)) {
            return "步骤 2/4 诊断";
        }
        if ("run_recovery_command".equals(step)) {
            return "步骤 3/4 恢复";
        }
        if ("verify_remote_access".equals(step)) {
            return "步骤 4/4 复测";
        }
        if ("needs_better_photo".equals(step)) {
            return "需要重拍";
        }
        if ("needs_human_expert".equals(step)) {
            return "人工接管";
        }
        if ("completed".equals(step)) {
            return "已完成";
        }
        return "步骤 1/4 定位";
    }

    private static String fallbackHint(HudResponse hud) {
        if ("wrong_target".equals(hud.feedbackCode)) {
            return "请补充你要 AI 判断的目标，或重新拍摄关键现场";
        }
        if ("unclear_photo".equals(hud.feedbackCode)) {
            return "请靠近屏幕，避免反光，把文字放进绿色框后重拍";
        }
        if ("insufficient_info".equals(hud.feedbackCode)) {
            return "请拍摄完整现场，或长按说明你要 AI 判断什么";
        }
        if ("voice_unclear".equals(hud.feedbackCode)) {
            return "请重新长按，说短一点";
        }
        if ("human_suggested".equals(hud.resultType) || hud.humanEscalationSuggestion) {
            return "你可以长按中心选择转人工，也可以重新拍摄补充信息";
        }
        return hud.displayText.length() == 0 ? "请按提示继续" : hud.displayText;
    }

    private static String diagnosticHint(HudResponse hud) {
        if (!"voice_unclear".equals(hud.feedbackCode) || hud.diagnosticCode.length() == 0) {
            return "";
        }
        if ("custom_stt_timeout".equals(hud.diagnosticCode)) {
            return "\n诊断：语音转文字服务超时，按钮和录音已正常。";
        }
        if ("official_stt_invalid_key".equals(hud.diagnosticCode)) {
            return "\n诊断：官方语音转写 key 无效。";
        }
        if ("main_provider_stt_unsupported".equals(hud.diagnosticCode)) {
            return "\n诊断：当前主 AI 服务不支持语音转写接口。";
        }
        if ("transcript_empty".equals(hud.diagnosticCode)) {
            return "\n诊断：语音没有转出文字。";
        }
        if ("suspicious_transcript".equals(hud.diagnosticCode)) {
            return "\n诊断：语音识别结果不可信，请靠近麦克风重新说短句。";
        }
        return "\n诊断：语音转文字失败。";
    }

    private static String statusForHud(HudResponse hud) {
        if ("recognition_problem".equals(hud.resultType)) {
            return "AI 需要你重新补充现场信息。";
        }
        if ("human_suggested".equals(hud.resultType) || hud.humanEscalationSuggestion) {
            return "AI 建议转人工，是否转人工由你决定。";
        }
        if ("completed".equals(hud.resultType)) {
            return "远程访问已恢复，本次会话完成。";
        }
        if ("network_error".equals(hud.resultType)) {
            return "暂时连接不到 AI 运维服务，请确认网络后重试。";
        }
        return "AI 已返回下一步，请按屏幕中央指令继续。";
    }

    private static String statusForStep(String step) {
        if ("run_diagnostic_command".equals(step)) {
            return "AI 已识别控制台，请输入屏幕中央给出的诊断命令。";
        }
        if ("run_recovery_command".equals(step)) {
            return "AI 已读取诊断输出，请输入屏幕中央给出的恢复命令。";
        }
        if ("verify_remote_access".equals(step)) {
            return "恢复命令已确认，后台正在复测远程访问。";
        }
        if ("needs_better_photo".equals(step)) {
            return "照片不够清晰。请靠近目标，把关键内容放进绿色框后重拍。";
        }
        if ("needs_human_expert".equals(step)) {
            return "当前情况需要人工专家接管，请停止继续输入命令。";
        }
        if ("completed".equals(step)) {
            return "远程访问已恢复，本次运维会话完成。";
        }
        return "AI 已返回下一步，请按屏幕中央指令继续。";
    }

    private static String voiceIntentLabel(String intent) {
        if ("confirm_done".equals(intent)) {
            return "已确认完成";
        }
        if ("retake_photo".equals(intent)) {
            return "准备重拍";
        }
        if ("escalate_human".equals(intent)) {
            return "转人工";
        }
        if ("new_issue".equals(intent)) {
            return "新问题";
        }
        return "语音已收到";
    }

    private void setHintForStep(String step, String text) {
        final String hint;
        if ("run_diagnostic_command".equals(step)) {
            hint = "请按中间指令输入诊断命令\n执行后单击拍摄完整输出";
        } else if ("run_recovery_command".equals(step)) {
            hint = "请只输入 AI 给出的恢复命令\n执行后单击拍摄命令输出";
        } else if ("verify_remote_access".equals(step)) {
            hint = "后台正在复测远程访问\n请等待复测结果";
        } else if ("needs_better_photo".equals(step)) {
            hint = "请把关键画面放入绿色框\n靠近目标并避免反光后单击重拍";
        } else if ("needs_human_expert".equals(step)) {
            hint = "请停止现场操作\n等待运维专家接管";
        } else if ("completed".equals(step)) {
            hint = "远程访问已恢复\n本次运维会话完成";
        } else {
            hint = text == null || text.length() == 0
                    ? "请对准需要判断的现场画面\n单击采集现场照片"
                    : "请按提示继续\n单击采集下一张现场照片";
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stepText.setText(stepLabel(step));
                hintText.setText(hint);
            }
        });
    }

    private static String shortSessionId(String value) {
        if (value == null || value.length() < 8) {
            return value == null ? "" : value;
        }
        return value.substring(0, 8);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readFileBytes(File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void setResultText(String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultText.setText(value);
            }
        });
        android.util.Log.i("Air3NativeCameraTest", "RESULT " + value);
    }

    private void setHintText(String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hintText.setText(value);
            }
        });
    }

    private void setStatus(String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(value);
            }
        });
        android.util.Log.i("Air3NativeCameraTest", value);
    }
}
