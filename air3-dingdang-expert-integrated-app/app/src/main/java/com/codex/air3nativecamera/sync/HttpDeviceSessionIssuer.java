package com.codex.air3nativecamera.sync;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Exchanges a managed bootstrap credential for a short-lived access token. */
public final class HttpDeviceSessionIssuer implements DeviceSessionManager.SessionIssuer {
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
                throw new IOException("device_session_http_" + status);
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
}
