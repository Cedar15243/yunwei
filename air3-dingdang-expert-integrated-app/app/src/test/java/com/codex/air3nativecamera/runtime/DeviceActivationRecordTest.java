package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class DeviceActivationRecordTest {
    private static final String BOOTSTRAP =
            "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE";

    @Test
    public void roundTripsOnlyTheDeviceActivationFields() throws Exception {
        DeviceActivationRecord original = DeviceActivationRecord.create(
                "https://bb.chinacedar.top:2305/v9-ops/",
                BOOTSTRAP,
                2_000_000L,
                "device-a",
                "organization-a",
                "v9-production-1");

        DeviceActivationRecord restored = DeviceActivationRecord.parse(original.toJson());
        JSONObject serialized = new JSONObject(original.toJson());

        assertEquals(DeviceActivationRecord.CURRENT_SCHEMA_VERSION, restored.schemaVersion());
        assertEquals("https://bb.chinacedar.top:2305/v9-ops", restored.backendBaseUrl());
        assertEquals(BOOTSTRAP, restored.bootstrapCredential());
        assertEquals(2_000_000L, restored.credentialExpiresAtMillis());
        assertEquals("device-a", restored.deviceId());
        assertEquals("organization-a", restored.organizationId());
        assertEquals("v9-production-1", restored.policyVersion());
        assertFalse(serialized.has("accessToken"));
        assertFalse(serialized.has("aiApiKey"));
        assertFalse(serialized.has("asrApiKey"));
        assertFalse(serialized.has("iflytekApiSecret"));
    }

    @Test
    public void rejectsHttpMissingBootstrapAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> DeviceActivationRecord.create(
                "http://bb.chinacedar.top/v9-ops",
                BOOTSTRAP,
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a"));
        assertThrows(IllegalArgumentException.class, () -> DeviceActivationRecord.create(
                "https://bb.chinacedar.top/v9-ops",
                "short",
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a"));
        assertThrows(IllegalArgumentException.class, () -> DeviceActivationRecord.create(
                "https://bb.chinacedar.top/v9-ops",
                BOOTSTRAP,
                2_000_000L,
                "device-a\nforged",
                "organization-a",
                "policy-a"));
    }

    @Test
    public void exposesCredentialExpiryWithoutMutatingTheRecord() {
        DeviceActivationRecord record = DeviceActivationRecord.create(
                "https://bb.chinacedar.top/v9-ops",
                BOOTSTRAP,
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a");

        assertTrue(record.isValidAt(1_999_999L));
        assertFalse(record.isValidAt(2_000_000L));
        assertFalse(record.isValidAt(2_000_001L));
    }

    @Test
    public void rejectsUnknownSchemaAndSupplierSecretFields() {
        assertThrows(IllegalArgumentException.class, () -> DeviceActivationRecord.parse(
                "{\"schemaVersion\":2,"
                        + "\"backendBaseUrl\":\"https://bb.chinacedar.top/v9-ops\","
                        + "\"bootstrapCredential\":\"" + BOOTSTRAP + "\","
                        + "\"credentialExpiresAtMillis\":2000000,"
                        + "\"deviceId\":\"device-a\","
                        + "\"organizationId\":\"organization-a\","
                        + "\"policyVersion\":\"policy-a\"}"));
        assertThrows(IllegalArgumentException.class, () -> DeviceActivationRecord.parse(
                "{\"schemaVersion\":1,"
                        + "\"backendBaseUrl\":\"https://bb.chinacedar.top/v9-ops\","
                        + "\"bootstrapCredential\":\"" + BOOTSTRAP + "\","
                        + "\"credentialExpiresAtMillis\":2000000,"
                        + "\"deviceId\":\"device-a\","
                        + "\"organizationId\":\"organization-a\","
                        + "\"policyVersion\":\"policy-a\","
                        + "\"iflytekApiSecret\":\"must-not-be-stored\"}"));
    }
}
