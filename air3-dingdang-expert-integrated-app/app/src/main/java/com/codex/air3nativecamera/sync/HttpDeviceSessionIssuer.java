package com.codex.air3nativecamera.sync;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Exchanges a managed bootstrap credential for a short-lived access token. */
public final class HttpDeviceSessionIssuer implements DeviceSessionManager.SessionIssuer {
    private static final Set<String> AUTHORITATIVE_REVOCATION_CODES = new HashSet<>(
            Arrays.asList(
                    "device_revoked",
                    "bootstrap_revoked",
                    "account_disabled",
                    "device_binding_revoked"));
    private final DeviceSyncConfiguration configuration;
    private final HttpConnectionFactory connectionFactory;

    public HttpDeviceSessionIssuer(DeviceSyncConfiguration configuration) {
        this(configuration, HttpConnectionFactory.DEFAULT);
    }

    HttpDeviceSessionIssuer(DeviceSyncConfiguration configuration, HttpConnectionFactory connectionFactory) {
        this.configuration = configuration;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public DeviceAccessSession exchange(String bootstrapCredential) throws IOException {
        HttpURLConnection connection = connectionFactory.open(configuration.sessionEndpoint());
        OutputStream output = null;
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + bootstrapCredential);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            output = connection.getOutputStream();
            output.write(body);
            output.close();
            output = null;
            int status = connection.getResponseCode();
            String responseBody = readAll(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw httpFailure(status, responseBody);
            }
            JSONObject response = new JSONObject(responseBody);
            String accessToken = response.optString("accessToken", "").trim();
            String expiresAt = response.optString("expiresAt", "").trim();
            if (accessToken.length() == 0 || expiresAt.length() == 0) {
                throw new IOException("device_session_response_invalid");
            }
            return new DeviceAccessSession(accessToken, Instant.parse(expiresAt).toEpochMilli());
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("device_session_response_invalid", error);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static DeviceSessionException httpFailure(int status, String responseBody) {
        String errorCode = status >= 400 && status < 500
                ? responseErrorCode(responseBody) : "";
        if (errorCode.length() == 0) errorCode = "device_session_http_" + status;
        return new DeviceSessionException(
                errorCode,
                status,
                status >= 400 && status < 500
                        && AUTHORITATIVE_REVOCATION_CODES.contains(errorCode));
    }

    private static String responseErrorCode(String responseBody) {
        try {
            JSONObject response = new JSONObject(responseBody == null ? "" : responseBody);
            String candidate = safeCode(response.opt("error"));
            if (candidate.length() == 0) candidate = safeCode(response.opt("code"));
            return candidate;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeCode(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String nested = safeCode(object.opt("code"));
            if (nested.length() > 0) return nested;
            return safeCode(object.opt("error"));
        }
        if (!(value instanceof String)) return "";
        String candidate = ((String) value).trim();
        return candidate.matches("[a-z0-9_]{1,80}") ? candidate : "";
    }
}
