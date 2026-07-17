package com.codex.expertcollab;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

final class CollabProtocol {
    private final String senderId;
    private final AtomicInteger sequence = new AtomicInteger();

    CollabProtocol(String senderId) {
        this.senderId = senderId;
    }

    String envelope(String type, String sessionId, JSONObject payload) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("sessionId", sessionId == null ? JSONObject.NULL : sessionId);
        message.put("senderId", senderId);
        message.put("seq", sequence.incrementAndGet());
        message.put("sentAt", System.currentTimeMillis());
        message.put("payload", payload == null ? new JSONObject() : payload);
        return message.toString();
    }
}
