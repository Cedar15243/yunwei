package com.codex.air3nativecamera.sync;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Uses only a short device session to reach the managed voiceprint lifecycle. */
public final class VoiceprintDeviceClient {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 20_000;
    private static final int VERIFY_CONNECT_TIMEOUT_MS = 3_000;
    private static final int VERIFY_READ_TIMEOUT_MS = 5_000;

    public static final class Result {
        private final boolean ok;
        private final boolean verified;
        private final String status;
        private final String error;
        private final int sampleCount;

        private Result(boolean ok, boolean verified, String status, String error, int sampleCount) {
            this.ok = ok;
            this.verified = verified;
            this.status = clean(status);
            this.error = clean(error);
            this.sampleCount = Math.max(0, sampleCount);
        }

        public boolean ok() {
            return ok;
        }

        public boolean verified() {
            return verified;
        }

        public String status() {
            return status;
        }

        public String error() {
            return error;
        }

        public int sampleCount() {
            return sampleCount;
        }

        @Override
        public String toString() {
            return "VoiceprintResult{ok=" + ok
                    + ", verified=" + verified
                    + ", status='" + status + "'"
                    + ", error='" + error + "'"
                    + ", sampleCount=" + sampleCount + "}";
        }
    }

    private final DeviceSyncConfiguration configuration;
    private final DeviceAccessTokenProvider tokenProvider;
    private final HttpConnectionFactory connectionFactory;

    public VoiceprintDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider) {
        this(configuration, tokenProvider, HttpConnectionFactory.DEFAULT);
    }

    VoiceprintDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            HttpConnectionFactory connectionFactory) {
        if (configuration == null || tokenProvider == null || connectionFactory == null) {
            throw new IllegalArgumentException("voiceprint_client_configuration_missing");
        }
        this.configuration = configuration;
        this.tokenProvider = tokenProvider;
        this.connectionFactory = connectionFactory;
    }

    public Result profile() throws IOException {
        return execute("GET", "/profile", null);
    }

    public Result consent(String consentVersion, String idempotencyKey) throws IOException {
        JSONObject payload = new JSONObject();
        put(payload, "consentAccepted", true);
        put(payload, "consentVersion", clean(consentVersion));
        put(payload, "idempotencyKey", clean(idempotencyKey));
        return execute("POST", "/consent", payload);
    }

    public Result enrollSample(int sampleIndex, byte[] wav, String idempotencyKey)
            throws IOException {
        JSONObject payload = audioPayload(wav, idempotencyKey);
        put(payload, "sampleIndex", sampleIndex);
        return execute("POST", "/enrollment/samples", payload);
    }

    public Result verify(byte[] wav, String idempotencyKey) throws IOException {
        return execute(
                "POST",
                "/verify",
                audioPayload(wav, idempotencyKey),
                VERIFY_CONNECT_TIMEOUT_MS,
                VERIFY_READ_TIMEOUT_MS);
    }

    public Result reenroll(String consentVersion, String idempotencyKey) throws IOException {
        JSONObject payload = new JSONObject();
        put(payload, "consentAccepted", true);
        put(payload, "consentVersion", clean(consentVersion));
        put(payload, "confirmation", "重新录入我的声纹");
        put(payload, "idempotencyKey", clean(idempotencyKey));
        return execute("POST", "/reenroll", payload);
    }

    public Result delete(String idempotencyKey) throws IOException {
        JSONObject payload = new JSONObject();
        put(payload, "confirmation", "删除我的声纹");
        put(payload, "idempotencyKey", clean(idempotencyKey));
        return execute("POST", "/delete", payload);
    }

    private JSONObject audioPayload(byte[] wav, String idempotencyKey) throws IOException {
        JSONObject payload = new JSONObject();
        put(payload, "audioBase64", Base64.getEncoder().encodeToString(
                wav == null ? new byte[0] : wav));
        put(payload, "idempotencyKey", clean(idempotencyKey));
        return payload;
    }

    private Result execute(String method, String path, JSONObject payload) throws IOException {
        return execute(method, path, payload, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    private Result execute(
            String method,
            String path,
            JSONObject payload,
            int connectTimeoutMs,
            int readTimeoutMs) throws IOException {
        HttpURLConnection connection = connectionFactory.open(voiceprintEndpoint() + path);
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Authorization", "Bearer " + tokenProvider.accessToken());
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream output = connection.getOutputStream();
                try {
                    output.write(body);
                } finally {
                    output.close();
                }
            }
            int responseCode = connection.getResponseCode();
            InputStream input = responseCode >= 200 && responseCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            JSONObject response = readJson(input);
            boolean ok = responseCode >= 200 && responseCode < 300
                    && response.optBoolean("ok", false);
            return new Result(
                    ok,
                    ok && response.optBoolean("verified", false),
                    response.optString("status", ""),
                    response.optString("error", ok ? "" : "voiceprint_http_" + responseCode),
                    response.optInt("sampleCount", 0));
        } finally {
            connection.disconnect();
        }
    }

    private String voiceprintEndpoint() {
        String endpoint = configuration.endpoint();
        if (endpoint.endsWith("/events")) {
            return endpoint.substring(0, endpoint.length() - "/events".length()) + "/voiceprint";
        }
        return endpoint + "/voiceprint";
    }

    private static JSONObject readJson(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("voiceprint_response_missing");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() > 256 * 1_024) {
                    throw new IOException("voiceprint_response_too_large");
                }
            }
            try {
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new IOException("voiceprint_response_invalid");
            }
        } finally {
            input.close();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void put(JSONObject object, String key, Object value) throws IOException {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IOException("voiceprint_request_invalid");
        }
    }
}
