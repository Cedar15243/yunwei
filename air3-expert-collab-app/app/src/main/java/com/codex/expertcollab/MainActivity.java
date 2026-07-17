package com.codex.expertcollab;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.rtmp.ui.TXCloudVideoView;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class MainActivity extends Activity implements
        CollabSocketClient.Listener,
        TrtcSessionController.Listener {
    private static final String DEVICE_ID = "glasses-01";
    private static final String DEVICE_NAME = "Air3-现场01";

    private final CollabStateMachine stateMachine = new CollabStateMachine();
    private final OkHttpClient imageClient = new OkHttpClient();
    private TextView statusText;
    private TextView expertText;
    private Button primaryButton;
    private TXCloudVideoView preview;
    private ImageView freezeImage;
    private AnnotationOverlayView annotationOverlay;
    private CollabSocketClient signaling;
    private TrtcSessionController trtcSession;
    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(createContentView());
        requestMediaPermissions();
        signaling = new CollabSocketClient(BuildConfig.COLLAB_SERVER_URL, DEVICE_ID, DEVICE_NAME, this);
        trtcSession = new TrtcSessionController(this, preview, BuildConfig.COLLAB_SERVER_URL, DEVICE_ID, this);
        signaling.connect();
        renderState();
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 13, 17));

        preview = new TXCloudVideoView(this);
        preview.setBackgroundColor(Color.rgb(17, 29, 34));
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        freezeImage = new ImageView(this);
        freezeImage.setBackgroundColor(Color.rgb(9, 13, 17));
        freezeImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        freezeImage.setVisibility(View.GONE);
        root.addView(freezeImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        annotationOverlay = new AnnotationOverlayView(this);
        root.addView(annotationOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(24), dp(12), dp(24), dp(12));
        topBar.setBackgroundColor(Color.argb(218, 9, 13, 17));
        statusText = createLabel(18, Color.rgb(69, 212, 131));
        expertText = createLabel(16, Color.WHITE);
        expertText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.addView(statusText, new LinearLayout.LayoutParams(0, dp(48), 1));
        topBar.addView(expertText, new LinearLayout.LayoutParams(0, dp(48), 1));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.TOP);
        root.addView(topBar, topParams);

        TextView reticle = createLabel(22, Color.argb(150, 255, 255, 255));
        reticle.setGravity(Gravity.CENTER);
        reticle.setText("+");
        FrameLayout.LayoutParams reticleParams = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER);
        root.addView(reticle, reticleParams);

        primaryButton = new Button(this);
        primaryButton.setAllCaps(false);
        primaryButton.setTextColor(Color.rgb(7, 20, 12));
        primaryButton.setTextSize(20);
        primaryButton.setBackgroundColor(Color.rgb(69, 212, 131));
        primaryButton.setOnClickListener(view -> {
            CollabStateMachine.State next = stateMachine.onPrimaryAction();
            if (next == CollabStateMachine.State.CALLING) {
                sessionId = null;
                annotationOverlay.clearAnnotations();
                clearFreeze();
                signaling.requestCall();
            } else if (next == CollabStateMachine.State.ENDED) {
                endCurrentCall(true);
            }
            renderState();
        });
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(dp(300), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonParams.bottomMargin = dp(32);
        root.addView(primaryButton, buttonParams);

        return root;
    }

    private TextView createLabel(float size, int color) {
        TextView label = new TextView(this);
        label.setTextColor(color);
        label.setTextSize(size);
        return label;
    }

    private void requestMediaPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    stateMachine.onFailure();
                    renderState();
                    statusText.setText("需要相机和麦克风权限");
                    return;
                }
            }
        }
    }

    private void renderState() {
        CollabStateMachine.State state = stateMachine.getState();
        switch (state) {
            case CALLING:
                statusText.setText("正在广播呼叫在线专家");
                expertText.setText("等待接听");
                primaryButton.setText("取消呼叫");
                break;
            case CONNECTING:
                statusText.setText("专家已接听，正在连接");
                expertText.setText(stateMachine.getExpertId());
                primaryButton.setText("挂断");
                break;
            case IN_CALL:
                statusText.setText("实时协同中");
                expertText.setText("主专家：" + stateMachine.getExpertId());
                primaryButton.setText("挂断");
                break;
            case RECONNECTING:
                statusText.setText("网络波动，正在重连");
                expertText.setText(stateMachine.getExpertId());
                primaryButton.setText("挂断");
                break;
            case FAILED:
                statusText.setText("连接失败");
                expertText.setText("请重试");
                primaryButton.setText("重新呼叫专家");
                break;
            case ENDED:
                statusText.setText("协同已结束");
                expertText.setText("设备在线");
                primaryButton.setText("再次呼叫专家");
                break;
            case IDLE:
            default:
                statusText.setText("设备在线 · 等待操作");
                expertText.setText("Air3-现场01");
                primaryButton.setText("呼叫远程专家");
                break;
        }
    }

    @Override
    public void onBackPressed() {
        CollabStateMachine.State state = stateMachine.getState();
        if (state != CollabStateMachine.State.IDLE && state != CollabStateMachine.State.ENDED) {
            stateMachine.onPrimaryAction();
            endCurrentCall(true);
            renderState();
        }
    }

    @Override
    protected void onDestroy() {
        if (signaling != null) {
            signaling.close();
        }
        if (trtcSession != null) {
            trtcSession.release();
        }
        imageClient.dispatcher().executorService().shutdown();
        super.onDestroy();
    }

    @Override
    public void onSignalingConnected() {
        runOnUiThread(() -> {
            if (stateMachine.getState() == CollabStateMachine.State.IDLE) {
                statusText.setText("设备在线 · 等待操作");
            }
        });
    }

    @Override
    public void onSessionCreated(String createdSessionId) {
        runOnUiThread(() -> sessionId = createdSessionId);
    }

    @Override
    public void onAccepted(String acceptedSessionId, String expertId) {
        runOnUiThread(() -> {
            if (stateMachine.getState() != CollabStateMachine.State.CALLING) {
                return;
            }
            sessionId = acceptedSessionId;
            try {
                stateMachine.onAccepted(expertId);
                renderState();
                trtcSession.join(acceptedSessionId);
            } catch (RuntimeException error) {
                onMediaError(error.getMessage());
            }
        });
    }

    @Override
    public void onCallEnded(String endedSessionId) {
        runOnUiThread(() -> {
            if (sessionId != null && sessionId.equals(endedSessionId)) {
                stateMachine.onEnded();
                endCurrentCall(false);
                renderState();
            }
        });
    }

    @Override
    public void onAnnotation(String type, JSONObject payload) {
        runOnUiThread(() -> annotationOverlay.apply(type, payload));
    }

    @Override
    public void onFreezeCreated(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        imageClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                onSignalingError("冻结画面下载失败");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) {
                        onSignalingError("冻结画面下载失败");
                        return;
                    }
                    byte[] image = closeable.body().bytes();
                    runOnUiThread(() -> {
                        freezeImage.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.length));
                        freezeImage.setVisibility(View.VISIBLE);
                    });
                }
            }
        });
    }

    @Override
    public void onFreezeCleared() {
        runOnUiThread(this::clearFreeze);
    }

    @Override
    public void onSignalingError(String reason) {
        runOnUiThread(() -> {
            if (stateMachine.getState() == CollabStateMachine.State.CALLING) {
                statusText.setText(reason);
            }
        });
    }

    @Override
    public void onMediaConnected() {
        runOnUiThread(() -> {
            try {
                stateMachine.onMediaConnected();
                renderState();
            } catch (IllegalStateException ignored) {
                // A late TRTC callback after hangup is intentionally ignored.
            }
        });
    }

    @Override
    public void onConnectionLost() {
        runOnUiThread(() -> {
            if (stateMachine.getState() == CollabStateMachine.State.IN_CALL) {
                stateMachine.onConnectionLost();
                renderState();
            }
        });
    }

    @Override
    public void onConnectionRecovered() {
        runOnUiThread(() -> {
            if (stateMachine.getState() == CollabStateMachine.State.RECONNECTING) {
                stateMachine.onMediaConnected();
                renderState();
            }
        });
    }

    @Override
    public void onMediaError(String reason) {
        runOnUiThread(() -> {
            stateMachine.onFailure();
            trtcSession.leave();
            renderState();
            statusText.setText(reason);
        });
    }

    private void endCurrentCall(boolean notifyServer) {
        if (notifyServer && sessionId != null) {
            signaling.endCall(sessionId);
        }
        trtcSession.leave();
        annotationOverlay.clearAnnotations();
        clearFreeze();
        sessionId = null;
    }

    private void clearFreeze() {
        freezeImage.setImageDrawable(null);
        freezeImage.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
