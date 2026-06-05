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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private TextureView previewView;
    private TextView titleText;
    private TextView stepText;
    private TextView hintText;
    private TextView resultText;
    private TextView statusText;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size captureSize = new Size(1280, 720);
    private Size previewSize = new Size(1280, 720);
    private String cameraId;
    private String sessionId = "";
    private String currentStep = "locate_server";
    private boolean captureInFlight;
    private SpeechRecognizer speechRecognizer;
    private boolean listening;
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
        destroySpeechRecognizer();
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
            setStatus("相机权限未授权，无法采集服务器控制台画面。");
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            captureStillImage("key-center");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            captureStillImage("key-camera");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setResultText("准备重拍");
            hintText.setText("请重新对准服务器控制台\n把文字放进绿色取景框");
            setStatus("已进入重拍准备。对准后单击继续采集。");
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        titleText.setText("AI 运维眼镜  |  服务器 SSH 恢复");
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

        attachCaptureGestures(root, previewView, scrim, guideOverlay, topPanel, titleText, stepText,
                centerPanel, resultText, hintText, statusText);
        setContentView(root);
        showHomeHud();
    }

    private void attachCaptureGestures(View... views) {
        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                captureStillImage("tap");
            }
        };
        View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startLocalSpeechTest();
                return true;
            }
        };
        for (View view : views) {
            if (view == null) {
                continue;
            }
            view.setClickable(true);
            view.setLongClickable(true);
            view.setOnClickListener(clickListener);
            view.setOnLongClickListener(longClickListener);
        }
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
                hintText.setText("请对准服务器本地控制台或终端窗口\n单击开始采集，长按尝试语音入口");
                statusText.setText("单击：拍照/下一步    长按：语音    返回键：重拍    相机键：拍照");
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
            canvas.drawText("把服务器控制台文字放入绿色框内，靠近屏幕并避免反光", centerX, frame.bottom + 42f, textPaint);
        }
    }

    private void startLocalSpeechTest() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setResultText("语音不可用");
            setStatus("这台 Air3 当前不支持系统语音识别，请改用单击拍照继续。");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setResultText("需要麦克风权限");
            setStatus("请允许麦克风权限，授权后长按可再次尝试语音。");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (listening) {
            setStatus("正在听取语音，请说出现场操作结果。");
            return;
        }

        ensureSpeechRecognizer();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        listening = true;
        setResultText("正在听取");
        setStatus("请说：好了，已经完成。");
        speechRecognizer.startListening(intent);
    }

    private void ensureSpeechRecognizer() {
        if (speechRecognizer != null) {
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                setStatus("语音已准备好，请开始说话。");
            }

            @Override
            public void onBeginningOfSpeech() {
                setStatus("已检测到语音。");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                setStatus("语音已结束，正在识别。");
            }

            @Override
            public void onError(int error) {
                listening = false;
                setResultText("语音失败");
                setStatus("语音识别失败，请单击拍照继续，或稍后重试语音。");
                android.util.Log.w("Air3NativeCameraTest",
                        "SpeechRecognizer error=" + error + " (" + speechErrorName(error) + ")");
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String transcript = matches == null || matches.isEmpty() ? "" : matches.get(0);
                setResultText(transcript.length() == 0 ? "未听清" : transcript);
                setStatus("已收到语音内容，正在同步到当前运维会话。");
                uploadTranscript(transcript);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer == null) {
            return;
        }
        try {
            speechRecognizer.destroy();
        } catch (Exception ignored) {
        }
        speechRecognizer = null;
    }

    private void uploadTranscript(String transcript) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("transcript", transcript);
                    payload.put("timestamp", System.currentTimeMillis());
                    payload.put("source", "android-local-speech");
                    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                    if (sessionId.length() == 0) {
                        throw new IllegalStateException("No ops session yet. Tap once to create session.");
                    }
                    connection = (HttpURLConnection) new URL(voiceEndpoint()).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(15000);
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
                    JSONObject response = new JSONObject(responseText);
                    String intent = response.optString("voiceIntent", "unknown");
                    String text = response.optString("text", responseText);
                    persistSession(response.optString("sessionId", sessionId), response.optString("step", currentStep));
                    setResultText(voiceIntentLabel(intent));
                    setStatus("语音内容已同步，请按屏幕提示继续操作。");
                    android.util.Log.i("Air3NativeCameraTest",
                            "Voice OK http=" + status + " transcript=" + transcript + " text=" + text);
                } catch (Exception error) {
                    setResultText("语音同步失败");
                    setStatus("语音没有同步成功，请改用单击拍照继续。");
                    android.util.Log.w("Air3NativeCameraTest", "Voice upload failed", error);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }, "Air3VoiceUpload").start();
    }

    private static String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_CLIENT:
                return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK:
                return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER:
                return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "ERROR_SPEECH_TIMEOUT";
            default:
                return "UNKNOWN";
        }
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
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) {
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
        double targetAspect = 16.0 / 9.0;
        double bestScore = Double.MAX_VALUE;
        for (Size size : sizes) {
            double aspect = (double) size.getWidth() / (double) size.getHeight();
            double aspectPenalty = Math.abs(aspect - targetAspect) * 10000.0;
            double pixelDelta = Math.abs(((double) size.getWidth() * (double) size.getHeight())
                    - (1920.0 * 1080.0)) / 100000.0;
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
        previewView.setTransform(matrix);
        android.util.Log.i("Air3NativeCameraTest", "Preview transform view="
                + viewWidth + "x" + viewHeight + " buffer="
                + previewSize.getWidth() + "x" + previewSize.getHeight());
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
                                showHomeHud();
                                setStatus("相机已就绪。请把服务器控制台放入绿色框内，单击开始 AI 运维会话。");
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

        captureInFlight = true;
        try {
            CaptureRequest.Builder request =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            setResultText("正在采集");
            hintText.setText("保持画面稳定\n请等待 AI 分析结果");
            setStatus("正在采集服务器本地控制台画面，请保持稳定。");
            android.util.Log.i("Air3NativeCameraTest", "Capturing JPEG source=" + source);
            captureSession.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
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
            setResultText("AI 分析中");
            hintText.setText("照片已上传\n正在生成下一步维修指令");
            setStatus("正在处理取景框内画面，并上传给 AI 分析。");
            android.util.Log.i("Air3NativeCameraTest", "JPEG image bytes=" + bytes.length);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    uploadImage(bytes);
                }
            }, "Air3NativeUpload").start();
        } catch (Exception error) {
            captureInFlight = false;
            setResultText("照片读取失败");
            setStatus("照片读取失败，请重新拍摄。");
            android.util.Log.w("Air3NativeCameraTest", "Image failed", error);
        }
    }

    private void uploadImage(byte[] jpegBytes) {
        HttpURLConnection connection = null;
        try {
            byte[] uploadBytes = prepareUploadJpeg(jpegBytes);
            String imageBase64 = Base64.encodeToString(uploadBytes, Base64.NO_WRAP);
            JSONObject payload = new JSONObject();
            payload.put("sessionId", sessionId);
            payload.put("taskType", "ssh_console_recovery");
            payload.put("step", currentStep);
            payload.put("action", actionForCurrentStep());
            payload.put("imageKind", "console");
            payload.put("imageBase64", "data:image/jpeg;base64," + imageBase64);
            payload.put("width", captureSize.getWidth());
            payload.put("height", captureSize.getHeight());
            payload.put("format", "jpg");
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("source", "native-camera2");
            payload.put("preprocess", "center-guide-crop");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            if (OPS_GLASSES_API_KEY.length() == 0) {
                throw new IllegalStateException("OPS_GLASSES_API_KEY missing in APK build.");
            }
            persistDebugUpload(uploadBytes);
            setStatus("照片已截取，正在上传给 AI 识别服务器控制台。");
            android.util.Log.i("Air3NativeCameraTest",
                    "Uploading focused JPEG bytes=" + uploadBytes.length
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
            JSONObject response = new JSONObject(responseText);
            String text = response.optString("text", responseText);
            int imageBytes = response.optInt("imageBytes", jpegBytes.length);
            String nextSessionId = response.optString("sessionId", sessionId);
            String nextStep = response.optString("step", currentStep);
            persistSession(nextSessionId, nextStep);
            persistLastResponse(responseText);

            setResultText(firstInstructionLine(text));
            setHintForStep(nextStep, text);
            setStatus(statusForStep(nextStep));
            android.util.Log.i("Air3NativeCameraTest", String.format(Locale.US,
                    "OK http=%d step=%s session=%s serverImageBytes=%d uploadBytes=%d rawBytes=%d text=%s",
                    status, nextStep, shortSessionId(nextSessionId), imageBytes,
                    uploadBytes.length, jpegBytes.length, text));
        } catch (Exception error) {
            setResultText("上传失败");
            setStatus("暂时连接不到 AI 运维服务。请确认网络后单击重试。");
            android.util.Log.w("Air3NativeCameraTest", "Upload failed", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            captureInFlight = false;
        }
    }

    private byte[] prepareUploadJpeg(byte[] jpegBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (bitmap == null || bitmap.getWidth() < 640 || bitmap.getHeight() < 360) {
            return jpegBytes;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
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

        Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight);
        Bitmap uploadBitmap = rotateForAiUpload(cropped);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 94, output);
        byte[] result = output.toByteArray();
        if (uploadBitmap != cropped) {
            uploadBitmap.recycle();
        }
        cropped.recycle();
        bitmap.recycle();
        return result.length > 0 ? result : jpegBytes;
    }

    private Bitmap rotateForAiUpload(Bitmap bitmap) {
        Matrix matrix = new Matrix();
        matrix.postRotate(270f);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void persistDebugUpload(byte[] uploadBytes) {
        try (OutputStream output = openFileOutput("last_upload_crop.jpg", MODE_PRIVATE)) {
            output.write(uploadBytes);
        } catch (Exception error) {
            android.util.Log.w("Air3NativeCameraTest", "Persist upload crop failed", error);
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

    private static String firstInstructionLine(String text) {
        if (text == null || text.trim().length() == 0) {
            return "No instruction";
        }
        String normalized = text.replace('\r', '\n').trim();
        int newline = normalized.indexOf('\n');
        String first = newline >= 0 ? normalized.substring(0, newline).trim() : normalized;
        return first.length() > 32 ? first.substring(0, 32) : first;
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
            return "照片不够清晰。请靠近屏幕，把文字放进绿色框后重拍。";
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
            hint = "请把控制台文字放入绿色框\n靠近屏幕并避免反光后单击重拍";
        } else if ("needs_human_expert".equals(step)) {
            hint = "请停止现场操作\n等待运维专家接管";
        } else if ("completed".equals(step)) {
            hint = "远程访问已恢复\n本次运维会话完成";
        } else {
            hint = text == null || text.length() == 0
                    ? "请对准服务器本地控制台\n单击采集现场画面"
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
