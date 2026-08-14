package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class ManagedRuntimeConfigurationTest {
    @Test
    public void secureRuntimeUsesOnlyManagedCredentials() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "https://ops.example.com/functions/v1/ops-glasses");
        managed.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, " device-token ");
        managed.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, "managed-app");
        managed.put(ManagedRuntimeConfiguration.IFLYTEK_API_KEY, "managed-key");
        managed.put(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET, "managed-secret");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true,
                "https://generated.example.com/functions/v1/ops-glasses",
                "generated-backend-key",
                "generated-app",
                "generated-key",
                "generated-secret",
                managed);

        assertEquals("https://ops.example.com/functions/v1/ops-glasses", configuration.backendBaseUrl());
        assertEquals("device-token", configuration.backendCredential());
        assertEquals("managed-app", configuration.iflytekAppId());
        assertEquals("managed-key", configuration.iflytekApiKey());
        assertEquals("managed-secret", configuration.iflytekApiSecret());
        assertTrue(configuration.isBackendProvisioned());
        assertTrue(configuration.hasIflytekCredentials());
    }

    @Test
    public void secureRuntimeNeverFallsBackToGeneratedSecrets() {
        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true,
                "https://generated.example.com/functions/v1/ops-glasses",
                "generated-backend-key",
                "generated-app",
                "generated-key",
                "generated-secret",
                new HashMap<String, String>());

        assertEquals("https://generated.example.com/functions/v1/ops-glasses", configuration.backendBaseUrl());
        assertEquals("", configuration.backendCredential());
        assertEquals("", configuration.iflytekAppId());
        assertEquals("", configuration.iflytekApiKey());
        assertEquals("", configuration.iflytekApiSecret());
        assertFalse(configuration.isBackendProvisioned());
        assertFalse(configuration.hasIflytekCredentials());
    }

    @Test
    public void legacyRuntimeKeepsExistingGeneratedCredentials() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "https://managed.example.com/ops");
        managed.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, "managed-token");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                false,
                "https://generated.example.com/functions/v1/ops-glasses/",
                "generated-backend-key",
                "generated-app",
                "generated-key",
                "generated-secret",
                managed);

        assertEquals("https://generated.example.com/functions/v1/ops-glasses", configuration.backendBaseUrl());
        assertEquals("generated-backend-key", configuration.backendCredential());
        assertEquals("generated-app", configuration.iflytekAppId());
        assertTrue(configuration.isBackendProvisioned());
        assertTrue(configuration.hasIflytekCredentials());
    }

    @Test
    public void legacyRuntimeKeepsExistingHttpDevelopmentEndpoint() {
        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                false,
                "http://192.168.1.20:8787/ops/",
                "generated-backend-key",
                "generated-app",
                "generated-key",
                "generated-secret",
                new HashMap<String, String>());

        assertEquals("http://192.168.1.20:8787/ops", configuration.backendBaseUrl());
        assertTrue(configuration.isBackendProvisioned());
    }

    @Test
    public void secureRuntimeRejectsNonHttpsManagedBackend() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "http://ops.example.com/functions/v1/ops-glasses");
        managed.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, "device-token");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true, "", "", "", "", "", managed);

        assertEquals("", configuration.backendBaseUrl());
        assertFalse(configuration.isBackendProvisioned());
    }

    @Test
    public void secureRuntimeUsesAValidLocalActivationWhenMdmIsAbsent() {
        DeviceActivationRecord local = DeviceActivationRecord.create(
                "https://activation.example.com/v9-ops",
                "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true, "https://generated.example.com/v9-ops", "", "", "", "",
                new HashMap<String, String>(), local, 1_000_000L);

        assertEquals("https://activation.example.com/v9-ops", configuration.backendBaseUrl());
        assertEquals(local.bootstrapCredential(), configuration.backendCredential());
        assertTrue(configuration.isBackendProvisioned());
        assertTrue(configuration.usesLocalActivation());
    }

    @Test
    public void completeMdmPairOverridesTheLocalActivation() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL,
                "https://mdm.example.com/v9-ops");
        managed.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN,
                "managed-device-bootstrap-token-12345");
        DeviceActivationRecord local = DeviceActivationRecord.create(
                "https://activation.example.com/v9-ops",
                "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true, "https://generated.example.com/v9-ops", "", "", "", "",
                managed, local, 1_000_000L);

        assertEquals("https://mdm.example.com/v9-ops", configuration.backendBaseUrl());
        assertEquals("managed-device-bootstrap-token-12345",
                configuration.backendCredential());
        assertFalse(configuration.usesLocalActivation());
    }

    @Test
    public void partialMdmConfigurationFailsClosedInsteadOfMixingLocalValues() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL,
                "https://mdm.example.com/v9-ops");
        DeviceActivationRecord local = DeviceActivationRecord.create(
                "https://activation.example.com/v9-ops",
                "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true, "https://generated.example.com/v9-ops", "", "", "", "",
                managed, local, 1_000_000L);

        assertEquals("https://mdm.example.com/v9-ops", configuration.backendBaseUrl());
        assertEquals("", configuration.backendCredential());
        assertFalse(configuration.isBackendProvisioned());
    }

    @Test
    public void expiredLocalActivationNeverRestoresAnApkCredential() {
        DeviceActivationRecord expired = DeviceActivationRecord.create(
                "https://activation.example.com/v9-ops",
                "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
                2_000_000L,
                "device-a",
                "organization-a",
                "policy-a");

        ManagedRuntimeConfiguration configuration = ManagedRuntimeConfiguration.resolve(
                true,
                "https://generated.example.com/v9-ops",
                "generated-secret-must-not-return",
                "generated-app",
                "generated-key",
                "generated-secret",
                new HashMap<String, String>(),
                expired,
                2_000_000L);

        assertEquals("https://generated.example.com/v9-ops", configuration.backendBaseUrl());
        assertEquals("", configuration.backendCredential());
        assertEquals("", configuration.iflytekApiSecret());
        assertFalse(configuration.isBackendProvisioned());
    }
}
