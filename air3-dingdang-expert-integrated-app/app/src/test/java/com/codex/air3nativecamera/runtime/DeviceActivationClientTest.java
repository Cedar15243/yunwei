package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DeviceActivationClientTest {
    private static final String BOOTSTRAP =
            "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE";

    @Test
    public void redeemsTheExactContractAndPersistsOnlyTheValidatedRecord() throws Exception {
        CapturingStore store = new CapturingStore();
        CapturingConnection connection = new CapturingConnection(
                201,
                "no-cache, no-store, max-age=0",
                successResponse());
        DeviceActivationClient client = client(
                "https://ops.example.com/functions/v1/ops-glasses/",
                store,
                endpoint -> {
                    connection.openedEndpoint = endpoint;
                    return connection;
                });

        DeviceActivationRecord result = client.redeem(
                "HF9-ABCD-EFGH-JKLM",
                "11111111-2222-4333-8444-555555555555",
                "com.codex.air3nativecamera.dingdangexpert.v9",
                "9.0.0",
                "IMA301");

        assertEquals("POST", connection.method);
        assertEquals(
                "https://ops.example.com/functions/v1/ops-glasses/device-activation/redeem",
                connection.openedEndpoint);
        assertEquals("application/json", connection.getRequestProperty("Accept"));
        assertEquals("no-store", connection.getRequestProperty("Cache-Control"));
        JSONObject request = new JSONObject(connection.requestBody());
        assertEquals("HF9-ABCD-EFGH-JKLM", request.getString("activationCode"));
        assertEquals(
                "11111111-2222-4333-8444-555555555555",
                request.getString("deviceInstanceId"));
        assertEquals(
                "com.codex.air3nativecamera.dingdangexpert.v9",
                request.getString("packageName"));
        assertEquals("9.0.0", request.getString("appVersion"));
        assertEquals("IMA301", request.getString("deviceModel"));
        assertEquals("device-a", result.deviceId());
        assertEquals(BOOTSTRAP, result.bootstrapCredential());
        assertNotNull(store.saved);
        assertEquals(result.toJson(), store.saved.toJson());
        assertFalse(store.saved.toJson().contains("aiApiKey"));
        assertFalse(store.saved.toJson().contains("asrApiKey"));
        assertFalse(store.saved.toJson().contains("iflytekApiSecret"));
    }

    @Test
    public void rejectsNonHttpsActivationEndpointsBeforeOpeningTheNetwork() {
        CapturingStore store = new CapturingStore();
        int[] opens = {0};

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> client("http://ops.example.com", store, endpoint -> {
                    opens[0] += 1;
                    throw new AssertionError("network must not open");
                }));

        assertEquals("device_activation_https_required", error.getMessage());
        assertEquals(0, opens[0]);
        assertEquals(null, store.saved);
    }

    @Test
    public void requiresNoStoreAndRejectsSupplierFieldsWithoutPersisting() throws Exception {
        CapturingStore missingHeaderStore = new CapturingStore();
        DeviceActivationClient missingHeader = client(
                "https://ops.example.com",
                missingHeaderStore,
                new SingleConnectionFactory(new CapturingConnection(
                        201, "no-cache", successResponse())));

        IOException missingHeaderError = assertThrows(
                IOException.class,
                () -> missingHeader.redeem(
                        "HF9-ABCD-EFGH-JKLM",
                        "11111111-2222-4333-8444-555555555555",
                        "com.codex.air3nativecamera.dingdangexpert.v9",
                        "9.0.0",
                        "IMA301"));
        assertEquals("device_activation_response_cacheable", missingHeaderError.getMessage());
        assertEquals(null, missingHeaderStore.saved);

        CapturingStore supplierStore = new CapturingStore();
        DeviceActivationClient supplierResponse = client(
                "https://ops.example.com",
                supplierStore,
                new SingleConnectionFactory(new CapturingConnection(
                        201,
                        "no-store",
                        successResponse().replace(
                                "\"policyVersion\":\"v9-production-1\"",
                                "\"policyVersion\":\"v9-production-1\","
                                        + "\"iflytekApiSecret\":\"must-not-enter-apk\""))));

        IOException supplierError = assertThrows(
                IOException.class,
                () -> supplierResponse.redeem(
                        "HF9-ABCD-EFGH-JKLM",
                        "11111111-2222-4333-8444-555555555555",
                        "com.codex.air3nativecamera.dingdangexpert.v9",
                        "9.0.0",
                        "IMA301"));
        assertEquals("device_activation_response_forbidden_field", supplierError.getMessage());
        assertEquals(null, supplierStore.saved);
    }

    @Test
    public void mapsStableGatewayErrorsAndTimeoutsWithoutClearingExistingActivation()
            throws Exception {
        CapturingStore store = new CapturingStore();
        store.saved = DeviceActivationRecord.create(
                "https://existing.example.com/v9-ops",
                BOOTSTRAP,
                1_800_000_000_000L,
                "device-existing",
                "organization-existing",
                "policy-existing");
        DeviceActivationClient rejected = client(
                "https://ops.example.com",
                store,
                new SingleConnectionFactory(new CapturingConnection(
                        410,
                        "no-store",
                        "{\"ok\":false,\"error\":\"activation_expired\"}")));

        IOException rejectedError = assertThrows(
                IOException.class,
                () -> rejected.redeem(
                        "HF9-ABCD-EFGH-JKLM",
                        "11111111-2222-4333-8444-555555555555",
                        "com.codex.air3nativecamera.dingdangexpert.v9",
                        "9.0.0",
                        "IMA301"));
        assertEquals("activation_expired", rejectedError.getMessage());
        assertEquals(0, store.clearCalls);
        assertEquals("device-existing", store.saved.deviceId());

        DeviceActivationClient timeout = client(
                "https://ops.example.com",
                store,
                endpoint -> {
                    throw new SocketTimeoutException("connect timed out");
                });
        IOException timeoutError = assertThrows(
                IOException.class,
                () -> timeout.redeem(
                        "HF9-ABCD-EFGH-JKLM",
                        "11111111-2222-4333-8444-555555555555",
                        "com.codex.air3nativecamera.dingdangexpert.v9",
                        "9.0.0",
                        "IMA301"));
        assertEquals("device_activation_timeout", timeoutError.getMessage());
        assertEquals(0, store.clearCalls);
    }

    @Test
    public void rejectsMalformedInputBeforeOpeningTheNetwork() {
        CapturingStore store = new CapturingStore();
        int[] opens = {0};
        DeviceActivationClient client = client(
                "https://ops.example.com",
                store,
                endpoint -> {
                    opens[0] += 1;
                    throw new AssertionError("network must not open");
                });

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> client.redeem(
                        "short",
                        "not-a-uuid",
                        "com.example.fake",
                        "",
                        "IMA301\nforged"));

        assertTrue(error.getMessage().startsWith("device_activation_"));
        assertEquals(0, opens[0]);
        assertEquals(null, store.saved);
    }

    private static DeviceActivationClient client(
            String endpoint,
            DeviceCredentialStore store,
            DeviceActivationClient.ConnectionFactory factory) {
        return new DeviceActivationClient(endpoint, store, factory);
    }

    private static String successResponse() {
        return "{"
                + "\"ok\":true,"
                + "\"backendBaseUrl\":\"https://bb.chinacedar.top:2305/v9-ops\","
                + "\"bootstrapCredential\":\"" + BOOTSTRAP + "\","
                + "\"credentialExpiresAt\":\"2026-11-01T12:00:00.000Z\","
                + "\"deviceId\":\"device-a\","
                + "\"organizationId\":\"organization-a\","
                + "\"policyVersion\":\"v9-production-1\""
                + "}";
    }

    private static final class CapturingStore implements DeviceCredentialStore {
        DeviceActivationRecord saved;
        int clearCalls;

        @Override
        public DeviceActivationRecord load() {
            return saved;
        }

        @Override
        public void save(DeviceActivationRecord record) {
            saved = record;
        }

        @Override
        public void clear() {
            clearCalls += 1;
            saved = null;
        }
    }

    private static final class SingleConnectionFactory
            implements DeviceActivationClient.ConnectionFactory {
        private final CapturingConnection connection;

        SingleConnectionFactory(CapturingConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpURLConnection open(String endpoint) {
            connection.openedEndpoint = endpoint;
            return connection;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int responseStatus;
        private final String cacheControl;
        private final byte[] responseBody;
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private String method = "";
        private String openedEndpoint = "";

        CapturingConnection(int status, String cacheControl, String response) throws Exception {
            super(new URL("https://unused.example"));
            this.responseStatus = status;
            this.cacheControl = cacheControl;
            this.responseBody = response.getBytes(StandardCharsets.UTF_8);
        }

        String requestBody() {
            return new String(requestBody.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void setRequestMethod(String method) {
            this.method = method;
        }

        @Override
        public OutputStream getOutputStream() {
            return requestBody;
        }

        @Override
        public int getResponseCode() {
            return responseStatus;
        }

        @Override
        public String getHeaderField(String name) {
            return "Cache-Control".equalsIgnoreCase(name) ? cacheControl : null;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (responseStatus >= 400) throw new IOException("http_" + responseStatus);
            return new ByteArrayInputStream(responseBody);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(responseBody);
        }

        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
