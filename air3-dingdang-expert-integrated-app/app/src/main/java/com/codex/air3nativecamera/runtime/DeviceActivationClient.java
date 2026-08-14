package com.codex.air3nativecamera.runtime;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exchanges a one-time public activation code for our managed device bootstrap. */
public final class DeviceActivationClient {
    interface ConnectionFactory {
        HttpURLConnection open(String endpoint) throws IOException;

        ConnectionFactory DEFAULT = new ConnectionFactory() {
            @Override
            public HttpURLConnection open(String endpoint) throws IOException {
                return (HttpURLConnection) new URL(endpoint).openConnection();
            }
        };
    }

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String FORMAL_PACKAGE =
            "com.codex.air3nativecamera.dingdangexpert.v9";
    private static final Pattern ACTIVATION_CODE = Pattern.compile(
            "^HF9-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$");
    private static final Pattern APP_VERSION = Pattern.compile(
            "^9\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9._-]+)?$");
    private static final Set<String> SUCCESS_FIELDS = new HashSet<>(Arrays.asList(
            "ok",
            "backendBaseUrl",
            "bootstrapCredential",
            "credentialExpiresAt",
            "deviceId",
            "organizationId",
            "policyVersion"));
    private static final Set<String> ERROR_FIELDS = new HashSet<>(Arrays.asList(
            "ok",
            "error"));
    private static final Set<String> STABLE_ERRORS = new HashSet<>(Arrays.asList(
            "invalid_request",
            "activation_expired",
            "activation_already_used",
            "activation_device_mismatch",
            "device_revoked",
            "device_binding_revoked",
            "activation_invalid"));

    private final String redeemEndpoint;
    private final DeviceCredentialStore credentialStore;
    private final ConnectionFactory connectionFactory;

    public DeviceActivationClient(
            String activationServiceBaseUrl,
            DeviceCredentialStore credentialStore) {
        this(activationServiceBaseUrl, credentialStore, ConnectionFactory.DEFAULT);
    }

    DeviceActivationClient(
            String activationServiceBaseUrl,
            DeviceCredentialStore credentialStore,
            ConnectionFactory connectionFactory) {
        if (credentialStore == null || connectionFactory == null) {
            throw new IllegalArgumentException("device_activation_configuration_invalid");
        }
        redeemEndpoint = requireHttpsBaseUrl(activationServiceBaseUrl)
                + "/device-activation/redeem";
        this.credentialStore = credentialStore;
        this.connectionFactory = connectionFactory;
    }

    public DeviceActivationRecord redeem(
            String activationCode,
            String deviceInstanceId,
            String packageName,
            String appVersion,
            String deviceModel) throws IOException {
        JSONObject request = requestPayload(
                activationCode,
                deviceInstanceId,
                packageName,
                appVersion,
                deviceModel);
        HttpURLConnection connection;
        try {
            connection = connectionFactory.open(redeemEndpoint);
        } catch (SocketTimeoutException timeout) {
            throw new IOException("device_activation_timeout", timeout);
        } catch (IOException error) {
            throw new IOException("device_activation_network_error", error);
        }
        try {
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            requireNoStore(connection.getHeaderField("Cache-Control"));
            JSONObject response = readJson(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw gatewayError(response, status);
            }
            validateFields(response, SUCCESS_FIELDS);
            if (!response.optBoolean("ok", false)) {
                throw new IOException("device_activation_response_invalid");
            }
            DeviceActivationRecord record = DeviceActivationRecord.create(
                    response.optString("backendBaseUrl", ""),
                    response.optString("bootstrapCredential", ""),
                    parseExpiry(response.optString("credentialExpiresAt", "")),
                    response.optString("deviceId", ""),
                    response.optString("organizationId", ""),
                    response.optString("policyVersion", ""));
            if (!record.isValidAt(System.currentTimeMillis())) {
                throw new IOException("device_activation_credential_expired");
            }
            credentialStore.save(record);
            return record;
        } catch (SocketTimeoutException timeout) {
            throw new IOException("device_activation_timeout", timeout);
        } catch (IOException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new IOException("device_activation_response_invalid", error);
        } catch (Exception error) {
            throw new IOException("device_activation_response_invalid", error);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject requestPayload(
            String activationCode,
            String deviceInstanceId,
            String packageName,
            String appVersion,
            String deviceModel) {
        String normalizedCode = clean(activationCode).toUpperCase(Locale.ROOT);
        String normalizedInstanceId = clean(deviceInstanceId);
        String normalizedPackage = clean(packageName);
        String normalizedVersion = clean(appVersion);
        String normalizedModel = clean(deviceModel);
        if (!ACTIVATION_CODE.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException("device_activation_code_invalid");
        }
        try {
            if (!UUID.fromString(normalizedInstanceId).toString().equals(normalizedInstanceId)) {
                throw new IllegalArgumentException("device_activation_instance_invalid");
            }
        } catch (IllegalArgumentException error) {
            if ("device_activation_instance_invalid".equals(error.getMessage())) throw error;
            throw new IllegalArgumentException("device_activation_instance_invalid", error);
        }
        if (!FORMAL_PACKAGE.equals(normalizedPackage)) {
            throw new IllegalArgumentException("device_activation_package_invalid");
        }
        if (!APP_VERSION.matcher(normalizedVersion).matches()) {
            throw new IllegalArgumentException("device_activation_version_invalid");
        }
        if (!validText(normalizedModel, 100)) {
            throw new IllegalArgumentException("device_activation_model_invalid");
        }
        try {
            return new JSONObject()
                    .put("activationCode", normalizedCode)
                    .put("deviceInstanceId", normalizedInstanceId)
                    .put("packageName", normalizedPackage)
                    .put("appVersion", normalizedVersion)
                    .put("deviceModel", normalizedModel);
        } catch (JSONException error) {
            throw new IllegalArgumentException("device_activation_request_invalid", error);
        }
    }

    private static IOException gatewayError(JSONObject response, int status) throws IOException {
        validateFields(response, ERROR_FIELDS);
        String error = clean(response.optString("error", ""));
        if (STABLE_ERRORS.contains(error)) return new IOException(error);
        if (status == 408 || status == 504) return new IOException("device_activation_timeout");
        if (status >= 500) return new IOException("device_activation_service_unavailable");
        return new IOException("device_activation_http_" + status);
    }

    private static void requireNoStore(String cacheControl) throws IOException {
        String value = clean(cacheControl).toLowerCase(Locale.ROOT);
        for (String directive : value.split(",")) {
            if ("no-store".equals(directive.trim())) return;
        }
        throw new IOException("device_activation_response_cacheable");
    }

    private static JSONObject readJson(InputStream input) throws IOException {
        if (input == null) throw new IOException("device_activation_response_missing");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() > MAX_RESPONSE_BYTES) {
                    throw new IOException("device_activation_response_too_large");
                }
            }
            try {
                return new JSONObject(new String(
                        output.toByteArray(), StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IOException("device_activation_response_invalid", error);
            }
        }
    }

    private static void validateFields(JSONObject value, Set<String> allowed) throws IOException {
        Iterator<String> fields = value.keys();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new IOException("device_activation_response_forbidden_field");
            }
        }
    }

    private static long parseExpiry(String value) throws IOException {
        try {
            return Instant.parse(clean(value)).toEpochMilli();
        } catch (Exception error) {
            throw new IOException("device_activation_response_invalid", error);
        }
    }

    private static String requireHttpsBaseUrl(String value) {
        String normalized = clean(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null) {
                throw new IllegalArgumentException("device_activation_https_required");
            }
            return normalized;
        } catch (IllegalArgumentException error) {
            if ("device_activation_https_required".equals(error.getMessage())) throw error;
            throw new IllegalArgumentException("device_activation_https_required", error);
        }
    }

    private static boolean validText(String value, int maxLength) {
        if (value.length() == 0 || value.length() > maxLength) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
