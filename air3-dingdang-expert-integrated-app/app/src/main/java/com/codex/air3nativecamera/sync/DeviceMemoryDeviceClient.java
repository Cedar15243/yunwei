package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the authorized equipment memory catalog through the V9 short-session gateway. */
public final class DeviceMemoryDeviceClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    public static final class ProjectHistory {
        private final String projectId;
        private final String localProjectId;
        private final String title;
        private final String status;
        private final int taskCount;

        private ProjectHistory(JSONObject value) throws IOException {
            projectId = requiredIdentifier(value.optString("projectId", ""));
            localProjectId = requiredIdentifier(value.optString("localProjectId", ""));
            title = requiredText(value.optString("title", ""), 240);
            status = requiredText(value.optString("status", ""), 80);
            taskCount = nonNegativeInt(value, "taskCount");
        }

        public String projectId() { return projectId; }
        public String localProjectId() { return localProjectId; }
        public String title() { return title; }
        public String status() { return status; }
        public int taskCount() { return taskCount; }
    }

    public static final class Item {
        private final String id;
        private final String system;
        private final String brand;
        private final String model;
        private final int quantity;
        private final String status;
        private final String lastInspectionAt;
        private final int faultCount;
        private final int repairCount;
        private final String keyParameter;
        private final List<ProjectHistory> linkedProjects;

        private Item(JSONObject value) throws IOException {
            id = requiredIdentifier(value.optString("id", ""));
            system = requiredText(value.optString("system", ""), 240);
            brand = requiredText(value.optString("brand", ""), 240);
            model = requiredText(value.optString("model", ""), 240);
            quantity = nonNegativeInt(value, "quantity");
            status = requiredText(value.optString("status", ""), 80);
            lastInspectionAt = optionalTimestamp(value, "lastInspectionAt");
            faultCount = nonNegativeInt(value, "faultCount");
            repairCount = nonNegativeInt(value, "repairCount");
            keyParameter = optionalText(value.optString("keyParameter", ""), 400);
            JSONArray projects = value.optJSONArray("linkedProjects");
            if (projects == null || projects.length() > 100) {
                throw new IOException("device_memory_response_invalid");
            }
            List<ProjectHistory> accepted = new ArrayList<>();
            for (int index = 0; index < projects.length(); index++) {
                JSONObject project = projects.optJSONObject(index);
                if (project == null) throw new IOException("device_memory_response_invalid");
                accepted.add(new ProjectHistory(project));
            }
            linkedProjects = Collections.unmodifiableList(accepted);
            if (!(status.equals("normal") || status.equals("attention")
                    || status.equals("maintenance") || status.equals("decommissioned"))) {
                throw new IOException("device_memory_response_invalid");
            }
        }

        public String id() { return id; }
        public String system() { return system; }
        public String brand() { return brand; }
        public String model() { return model; }
        public int quantity() { return quantity; }
        public String status() { return status; }
        public String lastInspectionAt() { return lastInspectionAt; }
        public int faultCount() { return faultCount; }
        public int repairCount() { return repairCount; }
        public String keyParameter() { return keyParameter; }
        public List<ProjectHistory> linkedProjects() { return linkedProjects; }
    }

    public static final class Catalog {
        private final List<Item> items;

        private Catalog(List<Item> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public List<Item> items() { return items; }
    }

    private final String endpoint;
    private final DeviceAccessTokenProvider tokenProvider;
    private final HttpConnectionFactory connectionFactory;

    public DeviceMemoryDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider
    ) {
        this(configuration, tokenProvider, HttpConnectionFactory.DEFAULT);
    }

    DeviceMemoryDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            HttpConnectionFactory connectionFactory
    ) {
        if (configuration == null || tokenProvider == null || connectionFactory == null) {
            throw new IllegalArgumentException("device memory client configuration is invalid");
        }
        String syncEndpoint = configuration.endpoint();
        if (!syncEndpoint.endsWith("/events")) {
            throw new IllegalArgumentException("device sync endpoint is invalid");
        }
        endpoint = syncEndpoint.substring(0, syncEndpoint.length() - "/events".length())
                + "/device-memory";
        this.tokenProvider = tokenProvider;
        this.connectionFactory = connectionFactory;
    }

    public Catalog load() throws IOException {
        HttpURLConnection connection = connectionFactory.open(endpoint);
        try {
            String accessToken = tokenProvider.accessToken();
            if (accessToken == null || accessToken.trim().isEmpty()) {
                throw new IOException("device_session_missing");
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            int status = connection.getResponseCode();
            String body = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            JSONObject response;
            try {
                response = new JSONObject(body);
            } catch (JSONException error) {
                throw new IOException("device_memory_response_invalid", error);
            }
            if (status < 200 || status >= 300) {
                throw new IOException(safeError(response.optString("error", "request_failed")));
            }
            if (!response.optBoolean("ok", false) || response.optInt("schemaVersion", 0) != 1
                    || !isTimestamp(response.optString("generatedAt", ""))) {
                throw new IOException("device_memory_response_invalid");
            }
            JSONArray values = response.optJSONArray("items");
            if (values == null || values.length() > 500) {
                throw new IOException("device_memory_response_invalid");
            }
            List<Item> items = new ArrayList<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) throw new IOException("device_memory_response_invalid");
                items.add(new Item(value));
            }
            return new Catalog(items);
        } finally {
            connection.disconnect();
        }
    }

    private static int nonNegativeInt(JSONObject value, String key) throws IOException {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IOException("device_memory_response_invalid");
        Number number = (Number) raw;
        long parsed = number.longValue();
        if (number.doubleValue() != (double) parsed || parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw new IOException("device_memory_response_invalid");
        }
        return (int) parsed;
    }

    private static String requiredIdentifier(String value) throws IOException {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$")) {
            throw new IOException("device_memory_response_invalid");
        }
        return clean;
    }

    private static String requiredText(String value, int maximum) throws IOException {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum) {
            throw new IOException("device_memory_response_invalid");
        }
        for (int index = 0; index < clean.length(); index++) {
            if (clean.charAt(index) < 32) throw new IOException("device_memory_response_invalid");
        }
        return clean;
    }

    private static String optionalText(String value, int maximum) throws IOException {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > maximum) throw new IOException("device_memory_response_invalid");
        for (int index = 0; index < clean.length(); index++) {
            if (clean.charAt(index) < 32) throw new IOException("device_memory_response_invalid");
        }
        return clean;
    }

    private static String optionalTimestamp(JSONObject value, String key) throws IOException {
        if (value.isNull(key)) return null;
        String timestamp = value.optString(key, "").trim();
        if (timestamp.isEmpty() || !isTimestamp(timestamp)) {
            throw new IOException("device_memory_response_invalid");
        }
        return timestamp;
    }

    private static boolean isTimestamp(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException error) {
            return false;
        }
    }

    private static String safeError(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("^[a-z0-9_]{1,120}$") ? clean : "request_failed";
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("device_memory_response_too_large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
