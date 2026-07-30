package com.codex.expertcollab;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class CollabSocketClient {
    interface Listener {
        void onSignalingConnected();
        void onSessionCreated(String sessionId);
        void onAccepted(String sessionId, String expertId);
        void onCallEnded(String sessionId);
        void onAnnotation(String type, JSONObject payload);
        void onFreezeCreated(String url);
        void onFreezeCleared();
        void onSignalingError(String reason);
    }

    private static final String TAG = "ExpertCollabSocket";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CollabReconnectPolicy reconnectPolicy = new CollabReconnectPolicy();
    private final Runnable reconnect = this::connect;
    private final String websocketUrl;
    private final String deviceId;
    private final String deviceName;
    private final Listener listener;
    private final CollabProtocol protocol;
    private WebSocket socket;
    private boolean closed;

    CollabSocketClient(String serverOrigin, String deviceId, String deviceName, Listener listener) {
        this.websocketUrl = serverOrigin.replaceFirst("^http", "ws") + "/collab";
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.listener = listener;
        this.protocol = new CollabProtocol(deviceId);
    }

    void connect() {
        if (closed) {
            return;
        }
        handler.removeCallbacks(reconnect);
        closed = false;
        socket = client.newWebSocket(new Request.Builder().url(websocketUrl).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (closed) {
                    webSocket.close(1000, "client closed");
                    return;
                }
                reconnectPolicy.reset();
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("kind", "glasses");
                    payload.put("name", deviceName);
                    send(protocol.envelope("presence.registered", null, payload));
                    listener.onSignalingConnected();
                } catch (JSONException error) {
                    listener.onSignalingError(error.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                Log.w(TAG, "signaling connection failed", error);
                listener.onSignalingError("协同服务连接中断，正在重连");
                scheduleReconnect(webSocket);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                scheduleReconnect(webSocket);
            }
        });
    }

    void requestCall() {
        sendEvent("call.requested", null, new JSONObject());
    }

    void endCall(String sessionId) {
        sendEvent("call.ended", sessionId, new JSONObject());
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) {
            socket.close(1000, "activity destroyed");
            socket = null;
        }
        client.dispatcher().executorService().shutdown();
    }

    private void sendEvent(String type, String sessionId, JSONObject payload) {
        try {
            send(protocol.envelope(type, sessionId, payload));
        } catch (JSONException error) {
            listener.onSignalingError(error.getMessage());
        }
    }

    private void send(String message) {
        WebSocket current = socket;
        if (current == null || !current.send(message)) {
            listener.onSignalingError("协同服务尚未连接");
        }
    }

    private void scheduleReconnect(WebSocket disconnectedSocket) {
        if (socket == disconnectedSocket) {
            socket = null;
        }
        if (closed) {
            return;
        }
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, reconnectPolicy.nextDelayMs());
    }

    private void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.getString("type");
            String sessionId = message.isNull("sessionId") ? null : message.getString("sessionId");
            JSONObject payload = message.optJSONObject("payload");
            if (payload == null) {
                payload = new JSONObject();
            }
            Log.d(TAG, "event=" + type + " sessionId=" + sessionId);
            if ("call.requested".equals(type) && sessionId != null) {
                listener.onSessionCreated(sessionId);
            } else if ("call.accepted".equals(type) && sessionId != null) {
                listener.onAccepted(sessionId, payload.optString("expertId", "专家"));
            } else if ("call.ended".equals(type) && sessionId != null) {
                listener.onCallEnded(sessionId);
            } else if (type.startsWith("annotation.")) {
                listener.onAnnotation(type, payload);
            } else if ("freeze.created".equals(type)) {
                listener.onFreezeCreated(payload.optString("url", ""));
            } else if ("freeze.cleared".equals(type)) {
                listener.onFreezeCleared();
            } else if ("error".equals(type)) {
                listener.onSignalingError(payload.optString("reason", "协同服务错误"));
            }
        } catch (JSONException error) {
            listener.onSignalingError("收到无法解析的协同事件");
        }
    }
}
