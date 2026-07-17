package com.codex.expertcollab;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.tencent.rtmp.ui.TXCloudVideoView;
import com.tencent.trtc.TRTCCloud;
import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class TrtcSessionController {
    interface Listener {
        void onMediaConnected();
        void onConnectionLost();
        void onConnectionRecovered();
        void onMediaError(String reason);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TRTCCloud trtc;
    private final TXCloudVideoView preview;
    private final String serverOrigin;
    private final String userId;
    private final Listener listener;
    private boolean active;
    private boolean joining;
    private int joinGeneration;

    TrtcSessionController(Context context, TXCloudVideoView preview, String serverOrigin, String userId, Listener listener) {
        this.trtc = TRTCCloud.sharedInstance(context.getApplicationContext());
        this.preview = preview;
        this.serverOrigin = serverOrigin;
        this.userId = userId;
        this.listener = listener;
        this.trtc.setListener(new TRTCCloudListener() {
            @Override
            public void onEnterRoom(long result) {
                if (result > 0) {
                    active = true;
                    joining = false;
                    listener.onMediaConnected();
                } else {
                    joining = false;
                    listener.onMediaError("进入TRTC房间失败：" + result);
                }
            }

            @Override
            public void onError(int errorCode, String errorMessage, Bundle extraInfo) {
                listener.onMediaError("TRTC错误 " + errorCode + "：" + errorMessage);
            }

            @Override
            public void onConnectionLost() {
                listener.onConnectionLost();
            }

            @Override
            public void onConnectionRecovery() {
                listener.onConnectionRecovered();
            }
        });
    }

    void join(String sessionId) {
        final int generation = ++joinGeneration;
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("userId", userId);
        } catch (Exception error) {
            listener.onMediaError(error.getMessage());
            return;
        }
        Request request = new Request.Builder()
                .url(serverOrigin + "/api/trtc/credential")
                .post(RequestBody.create(requestJson.toString(), JSON))
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                mainHandler.post(() -> {
                    if (generation == joinGeneration) {
                        listener.onMediaError("获取TRTC凭证失败");
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) {
                        mainHandler.post(() -> {
                            if (generation == joinGeneration) {
                                listener.onMediaError("TRTC凭证服务不可用");
                            }
                        });
                        return;
                    }
                    JSONObject credential = new JSONObject(closeable.body().string());
                    int sdkAppId = credential.getInt("sdkAppId");
                    String signedUserId = credential.getString("userId");
                    String userSig = credential.getString("userSig");
                    mainHandler.post(() -> {
                        if (generation == joinGeneration) {
                            enterRoom(sessionId, sdkAppId, signedUserId, userSig);
                        }
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        if (generation == joinGeneration) {
                            listener.onMediaError("TRTC凭证格式错误");
                        }
                    });
                }
            }
        });
    }

    private void enterRoom(String sessionId, int sdkAppId, String signedUserId, String userSig) {
        joining = true;
        trtc.startLocalPreview(true, preview);
        trtc.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_SPEECH);
        TRTCCloudDef.TRTCParams params = new TRTCCloudDef.TRTCParams();
        params.sdkAppId = sdkAppId;
        params.userId = signedUserId;
        params.userSig = userSig;
        params.strRoomId = sessionId;
        trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL);
    }

    void leave() {
        joinGeneration++;
        if (active || joining) {
            trtc.exitRoom();
        }
        trtc.stopLocalAudio();
        trtc.stopLocalPreview();
        active = false;
        joining = false;
    }

    void release() {
        leave();
        trtc.setListener(null);
        httpClient.dispatcher().executorService().shutdown();
        TRTCCloud.destroySharedInstance();
    }
}
