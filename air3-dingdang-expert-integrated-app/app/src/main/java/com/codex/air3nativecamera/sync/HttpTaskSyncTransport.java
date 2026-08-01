package com.codex.air3nativecamera.sync;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;

/** Sends one already-persisted event to the device-authorized Edge Function. */
public final class HttpTaskSyncTransport implements TaskSyncClient.Transport {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final DeviceSyncConfiguration configuration;
    private final DeviceAccessTokenProvider accessTokenProvider;
    private final HttpConnectionFactory connectionFactory;

    public HttpTaskSyncTransport(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider accessTokenProvider) {
        this(configuration, accessTokenProvider, HttpConnectionFactory.DEFAULT);
    }

    HttpTaskSyncTransport(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider accessTokenProvider,
            HttpConnectionFactory connectionFactory) {
        this.configuration = configuration;
        this.accessTokenProvider = accessTokenProvider;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void send(TaskSyncEvent event) throws IOException {
        HttpURLConnection connection = connectionFactory.open(configuration.endpoint());
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessTokenProvider.accessToken());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = TaskSyncRequest.body(event).getBytes(UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("device_sync_http_" + status);
            }
        } finally {
            connection.disconnect();
        }
    }
}
