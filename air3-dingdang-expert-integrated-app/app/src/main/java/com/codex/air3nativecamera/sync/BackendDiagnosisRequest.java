package com.codex.air3nativecamera.sync;

import org.json.JSONException;
import org.json.JSONObject;

/** Builds the bounded V9 diagnose payload without exposing provider or model selection. */
public final class BackendDiagnosisRequest {
    private static final int MAX_PROMPT_CHARS = 12_000;

    private BackendDiagnosisRequest() {}

    public static JSONObject create(
            String imageId,
            String finalText,
            AiExecutionContext executionContext
    ) {
        String prompt = finalText == null ? "" : finalText.trim();
        if (prompt.isEmpty() || prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("diagnosis text is invalid");
        }
        if (executionContext == null) {
            throw new IllegalArgumentException("AI execution context is required");
        }
        try {
            return new JSONObject()
                    .put("image_id", imageId == null ? "" : imageId.trim())
                    .put("final_text", prompt)
                    .put("localProjectId", executionContext.localProjectId())
                    .put("localTaskId", executionContext.localTaskId())
                    .put("client_context", new JSONObject()
                            .put("source", "dingdang-android"));
        } catch (JSONException exception) {
            throw new IllegalStateException("unable to build diagnosis request", exception);
        }
    }
}
