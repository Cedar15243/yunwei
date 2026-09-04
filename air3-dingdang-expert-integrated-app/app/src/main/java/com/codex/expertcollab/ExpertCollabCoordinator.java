package com.codex.expertcollab;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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

public final class ExpertCollabCoordinator implements
        CollabSocketClient.Listener,
        TrtcSessionController.Listener {
    public interface Host {
        void requestExitExpertMode();

        void showExpertStatus(String message);

        void prepareExpertMedia();
    }

    private static final String DEVICE_ID = "glasses-01";
    private static final String DEVICE_NAME = "Air3-现场01";

    private final Activity activity;
    private final String serverUrl;
    private final Host host;
    private final CollabStateMachine stateMachine = new CollabStateMachine();
    private final OkHttpClient imageClient = new OkHttpClient();
    private final FrameLayout root;
    private final LinearLayout waitingSurface;
    private final LinearLayout topBar;
    private final TextView statusText;
    private final TextView expertText;
    private final Button primaryButton;
    private final TXCloudVideoView preview;
    private final FrameLayout expertVideoFrame;
    private final TXCloudVideoView expertPreview;
    private final TextView expertVideoLabel;
    private final TextView reticle;
    private final ImageView freezeImage;
    private final AnnotationOverlayView annotationOverlay;
    private CollabSocketClient signaling;
    private TrtcSessionController trtcSession;
    private String sessionId;
    private boolean started;
    private boolean released;
    private boolean initialCallRequested;
    private final Handler mediaStartHandler = new Handler(Looper.getMainLooper());
    private int mediaStartGeneration;

    public ExpertCollabCoordinator(Activity activity, String serverUrl, Host host) {
        if (activity == null || host == null || serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("activity, serverUrl and host are required");
        }
        this.activity = activity;
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.host = host;

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(237, 243, 241));

        preview = new TXCloudVideoView(activity);
        preview.setBackgroundColor(Color.rgb(17, 29, 34));
        root.addView(preview, matchParent());

        freezeImage = new ImageView(activity);
        freezeImage.setBackgroundColor(Color.rgb(9, 13, 17));
        freezeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        freezeImage.setVisibility(View.GONE);
        root.addView(freezeImage, matchParent());

        waitingSurface = new LinearLayout(activity);
        waitingSurface.setGravity(Gravity.CENTER);
        waitingSurface.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable waitingBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(249, 252, 251), Color.rgb(228, 242, 236)});
        waitingSurface.setBackground(waitingBackground);
        LinearLayout waitingPanel = new LinearLayout(activity);
        waitingPanel.setGravity(Gravity.CENTER);
        waitingPanel.setOrientation(LinearLayout.VERTICAL);
        waitingPanel.setPadding(dp(54), dp(34), dp(54), dp(34));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.argb(224, 255, 255, 255));
        panelBackground.setStroke(dp(1), Color.rgb(176, 215, 201));
        panelBackground.setCornerRadius(dp(12));
        waitingPanel.setBackground(panelBackground);
        TextView waitingMark = label(28, Color.rgb(7, 139, 104));
        waitingMark.setGravity(Gravity.CENTER);
        waitingMark.setText("●");
        waitingPanel.addView(waitingMark, new LinearLayout.LayoutParams(dp(64), dp(48)));
        TextView waitingTitle = label(30, Color.rgb(16, 44, 36));
        waitingTitle.setGravity(Gravity.CENTER);
        waitingTitle.setTypeface(Typeface.DEFAULT_BOLD);
        waitingTitle.setText("AR 远程专家协同");
        waitingPanel.addView(waitingTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(58)));
        TextView waitingHint = label(17, Color.rgb(70, 105, 94));
        waitingHint.setGravity(Gravity.CENTER);
        waitingHint.setText("正在建立安全音视频链路\n可说“小叮当，返回首页”退出等待");
        waitingPanel.addView(waitingHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(70)));
        waitingSurface.addView(waitingPanel, new LinearLayout.LayoutParams(dp(680), dp(260)));
        root.addView(waitingSurface, matchParent());

        annotationOverlay = new AnnotationOverlayView(activity);
        root.addView(annotationOverlay, matchParent());

        expertVideoFrame = new FrameLayout(activity);
        expertVideoFrame.setBackgroundColor(Color.rgb(191, 217, 206));
        expertVideoFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        expertVideoFrame.setVisibility(View.GONE);
        expertPreview = new TXCloudVideoView(activity);
        expertPreview.setBackgroundColor(Color.rgb(9, 13, 17));
        expertVideoFrame.addView(expertPreview, matchParent());
        expertVideoLabel = label(13, Color.WHITE);
        expertVideoLabel.setBackgroundColor(Color.argb(205, 9, 13, 17));
        expertVideoLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        expertVideoFrame.addView(expertVideoLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        FrameLayout.LayoutParams expertVideoParams = new FrameLayout.LayoutParams(
                dp(300), dp(180), Gravity.TOP | Gravity.END);
        expertVideoParams.topMargin = dp(82);
        expertVideoParams.rightMargin = dp(20);
        root.addView(expertVideoFrame, expertVideoParams);

        topBar = new LinearLayout(activity);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(24), dp(12), dp(24), dp(12));
        topBar.setBackgroundColor(Color.argb(232, 248, 252, 250));
        statusText = label(20, Color.rgb(7, 139, 104));
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        expertText = label(17, Color.rgb(49, 93, 79));
        expertText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.addView(statusText, new LinearLayout.LayoutParams(0, dp(48), 1f));
        topBar.addView(expertText, new LinearLayout.LayoutParams(0, dp(48), 1f));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.TOP);
        root.addView(topBar, topParams);

        reticle = label(22, Color.argb(150, 255, 255, 255));
        reticle.setGravity(Gravity.CENTER);
        reticle.setText("+");
        root.addView(reticle, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        primaryButton = actionButton();
        actions.addView(primaryButton, new LinearLayout.LayoutParams(dp(268), dp(54)));
        FrameLayout.LayoutParams actionsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        actionsParams.bottomMargin = dp(28);
        root.addView(actions, actionsParams);

        primaryButton.setOnClickListener(view -> onPrimaryAction());
        renderState();
    }

    public View createView() {
        return root;
    }

    public void start() {
        if (started || released) {
            return;
        }
        started = true;
        signaling = new CollabSocketClient(serverUrl, DEVICE_ID, DEVICE_NAME, this);
        trtcSession = new TrtcSessionController(activity, preview, expertPreview, serverUrl, DEVICE_ID, this);
        signaling.connect();
        renderState();
    }

    public boolean handleConfirmKey() {
        if (released) {
            return false;
        }
        primaryButton.performClick();
        return true;
    }

    public void endCallAndExit() {
        if (released) {
            return;
        }
        if (stateMachine.getState() != CollabStateMachine.State.IDLE
                && stateMachine.getState() != CollabStateMachine.State.ENDED) {
            stateMachine.onPrimaryAction();
        }
        endCurrentCall(true);
        host.requestExitExpertMode();
    }

    /** A waiting call has no TRTC microphone yet, so local voice exit remains safe. */
    public boolean canUseForegroundVoiceControl() {
        if (released) {
            return false;
        }
        CollabStateMachine.State state = stateMachine.getState();
        return state == CollabStateMachine.State.IDLE
                || state == CollabStateMachine.State.CALLING
                || state == CollabStateMachine.State.FAILED
                || state == CollabStateMachine.State.ENDED;
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        started = false;
        mediaStartGeneration++;
        mediaStartHandler.removeCallbacksAndMessages(null);
        endCurrentCall(true);
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
        if (trtcSession != null) {
            trtcSession.release();
            trtcSession = null;
        }
        imageClient.dispatcher().cancelAll();
        imageClient.dispatcher().executorService().shutdown();
        annotationOverlay.clearAnnotations();
        clearFreeze();
    }

    private void onPrimaryAction() {
        if (!started || signaling == null || trtcSession == null) {
            host.showExpertStatus("专家协同服务正在初始化");
            return;
        }
        CollabStateMachine.State previous = stateMachine.getState();
        CollabStateMachine.State next = stateMachine.onPrimaryAction();
        if (previous != CollabStateMachine.State.IDLE
                && previous != CollabStateMachine.State.ENDED
                && previous != CollabStateMachine.State.FAILED) {
            endCurrentCall(true);
            host.requestExitExpertMode();
            return;
        }
        if (next == CollabStateMachine.State.CALLING) {
            if (previous == CollabStateMachine.State.FAILED) {
                endCurrentCall(true);
            } else {
                sessionId = null;
                annotationOverlay.clearAnnotations();
                clearFreeze();
            }
            signaling.requestCall();
        } else if (next == CollabStateMachine.State.ENDED) {
            endCurrentCall(true);
        }
        renderState();
    }

    private void renderState() {
        CollabStateMachine.State state = stateMachine.getState();
        boolean videoActive = state == CollabStateMachine.State.IN_CALL
                || state == CollabStateMachine.State.RECONNECTING;
        setWaitingSurfaceVisible(!videoActive);
        switch (state) {
            case CALLING:
                statusText.setText(sessionId == null ? "正在发送专家邀请" : "专家邀请已发送");
                expertText.setText("等待接听");
                primaryButton.setText("挂断");
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
                statusText.setText("专家协同 · 设备在线");
                expertText.setText(DEVICE_NAME);
                primaryButton.setText("呼叫远程专家");
                break;
        }
        stylePrimaryButton(state == CollabStateMachine.State.CALLING
                || state == CollabStateMachine.State.CONNECTING
                || state == CollabStateMachine.State.IN_CALL
                || state == CollabStateMachine.State.RECONNECTING);
    }

    private void setWaitingSurfaceVisible(boolean visible) {
        waitingSurface.setVisibility(visible ? View.VISIBLE : View.GONE);
        reticle.setVisibility(visible ? View.GONE : View.VISIBLE);
        topBar.setBackgroundColor(Color.argb(224, 248, 252, 250));
        statusText.setTextColor(Color.rgb(7, 139, 104));
        expertText.setTextColor(Color.rgb(49, 93, 79));
    }

    @Override
    public void onSignalingConnected() {
        ui(() -> {
            if (released) {
                return;
            }
            if (!initialCallRequested
                    && stateMachine.getState() == CollabStateMachine.State.IDLE) {
                initialCallRequested = true;
                onPrimaryAction();
            } else if (stateMachine.shouldReplayPendingCallOnSignalingConnected()) {
                sessionId = null;
                signaling.requestCall();
                renderState();
            }
        });
    }

    @Override
    public void onSessionCreated(String createdSessionId) {
        ui(() -> {
            if (!released) {
                sessionId = createdSessionId;
                renderState();
            }
        });
    }

    @Override
    public void onAccepted(String acceptedSessionId, String expertId) {
        ui(() -> {
            if (released || stateMachine.getState() != CollabStateMachine.State.CALLING) {
                return;
            }
            sessionId = acceptedSessionId;
            try {
                stateMachine.onAccepted(expertId);
                renderState();
                host.prepareExpertMedia();
                final int generation = ++mediaStartGeneration;
                mediaStartHandler.postDelayed(() -> {
                    if (released || generation != mediaStartGeneration
                            || stateMachine.getState() != CollabStateMachine.State.CONNECTING
                            || trtcSession == null) {
                        return;
                    }
                    trtcSession.join(acceptedSessionId, expertId);
                }, 2000L);
            } catch (RuntimeException error) {
                onMediaError(error.getMessage());
            }
        });
    }

    @Override
    public void onCallEnded(String endedSessionId) {
        ui(() -> {
            if (!released && sessionId != null && sessionId.equals(endedSessionId)) {
                stateMachine.onEnded();
                endCurrentCall(false);
                host.requestExitExpertMode();
            }
        });
    }

    @Override
    public void onAnnotation(String type, JSONObject payload) {
        ui(() -> {
            if (!released) {
                annotationOverlay.apply(type, payload);
            }
        });
    }

    @Override
    public void onFreezeCreated(String url) {
        if (released || url == null || url.isEmpty()) {
            return;
        }
        String resolvedUrl = url.startsWith("http://") || url.startsWith("https://")
                ? url
                : serverUrl + (url.startsWith("/") ? url : "/" + url);
        imageClient.newCall(new Request.Builder().url(resolvedUrl).build()).enqueue(new Callback() {
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
                    ui(() -> {
                        if (!released) {
                            freezeImage.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.length));
                            freezeImage.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onFreezeCleared() {
        ui(this::clearFreeze);
    }

    @Override
    public void onSignalingError(String reason) {
        ui(() -> {
            if (!released) {
                statusText.setText(reason == null || reason.isEmpty()
                        ? "协同服务连接中断，正在重连"
                        : reason);
            }
        });
    }

    @Override
    public void onMediaConnected() {
        ui(() -> {
            if (released) {
                return;
            }
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
        ui(() -> {
            if (!released && stateMachine.getState() == CollabStateMachine.State.IN_CALL) {
                stateMachine.onConnectionLost();
                renderState();
            }
        });
    }

    @Override
    public void onConnectionRecovered() {
        ui(() -> {
            if (!released && stateMachine.getState() == CollabStateMachine.State.RECONNECTING) {
                stateMachine.onMediaConnected();
                renderState();
            }
        });
    }

    @Override
    public void onMediaError(String reason) {
        ui(() -> {
            if (released) {
                return;
            }
            stateMachine.onFailure();
            endCurrentCall(true);
            renderState();
            statusText.setText(reason == null || reason.isEmpty() ? "音视频连接失败" : reason);
        });
    }

    @Override
    public void onExpertVideoAvailable(String expertId, boolean available) {
        ui(() -> {
            if (released) {
                return;
            }
            expertVideoLabel.setText("专家 · " + expertId);
            expertVideoFrame.setVisibility(available ? View.VISIBLE : View.GONE);
        });
    }

    private void endCurrentCall(boolean notifyServer) {
        if (notifyServer && sessionId != null && signaling != null) {
            signaling.endCall(sessionId);
        }
        if (trtcSession != null) {
            trtcSession.leave();
        }
        annotationOverlay.clearAnnotations();
        clearFreeze();
        expertVideoFrame.setVisibility(View.GONE);
        sessionId = null;
    }

    private void clearFreeze() {
        freezeImage.setImageDrawable(null);
        freezeImage.setVisibility(View.GONE);
    }

    private void ui(Runnable action) {
        activity.runOnUiThread(action);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private TextView label(float size, int color) {
        TextView label = new TextView(activity);
        label.setTextColor(color);
        label.setTextSize(size);
        return label;
    }

    private Button actionButton() {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setTextSize(17);
        stylePrimaryButton(button, false);
        return button;
    }

    private void stylePrimaryButton(boolean hangUp) {
        stylePrimaryButton(primaryButton, hangUp);
    }

    private void stylePrimaryButton(Button button, boolean hangUp) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(27));
        if (hangUp) {
            background.setColor(Color.argb(238, 255, 255, 255));
            background.setStroke(dp(1), Color.rgb(194, 75, 83));
            button.setTextColor(Color.rgb(151, 45, 54));
        } else {
            background.setColor(Color.rgb(223, 245, 234));
            background.setStroke(dp(1), Color.rgb(145, 201, 181));
            button.setTextColor(Color.rgb(10, 91, 70));
        }
        button.setBackground(background);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
