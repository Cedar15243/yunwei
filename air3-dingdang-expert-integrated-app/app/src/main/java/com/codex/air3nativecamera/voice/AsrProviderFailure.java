package com.codex.air3nativecamera.voice;

import org.json.JSONObject;

public final class AsrProviderFailure {
    private final String diagnosticCode;
    private final String userMessage;
    private final boolean localFallback;

    private AsrProviderFailure(String diagnosticCode, String userMessage,
            boolean localFallback) {
        this.diagnosticCode = diagnosticCode;
        this.userMessage = userMessage;
        this.localFallback = localFallback;
    }

    public static AsrProviderFailure fromDashScopeFrame(String frame) {
        String errorCode = "";
        try {
            JSONObject root = new JSONObject(frame == null ? "" : frame);
            JSONObject header = root.optJSONObject("header");
            if (header != null) {
                errorCode = header.optString("error_code", "").trim();
            }
        } catch (Exception ignored) {
        }
        String normalized = errorCode.toLowerCase();
        if (normalized.contains("arrearage") || normalized.contains("account")) {
            return new AsrProviderFailure(
                    "asr_provider_account_unavailable",
                    "语音转写服务账户不可用，正在切换本地识别",
                    true);
        }
        if (normalized.contains("quota") || normalized.contains("limit")) {
            return new AsrProviderFailure(
                    "asr_provider_quota_exhausted",
                    "语音转写额度不足，正在切换本地识别",
                    true);
        }
        return new AsrProviderFailure(
                "asr_task_failed",
                "语音转写失败，已保留本轮录音",
                false);
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }

    public String userMessage() {
        return userMessage;
    }

    public boolean shouldUseLocalFallback() {
        return localFallback;
    }
}
