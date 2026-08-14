package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class DebugPrivateProvisioningTest {
    @Test
    public void parsesOnlyTheHttpsGatewayAndBootstrapCredential() {
        Map<String, String> values = DebugPrivateProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"backendBaseUrl\":\"https://bb.chinacedar.top:2305/v9-ops/\","
                        + "\"bootstrapCredential\":\"abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE\"}");

        assertEquals(
                "https://bb.chinacedar.top:2305/v9-ops",
                values.get(ManagedRuntimeConfiguration.BACKEND_BASE_URL));
        assertEquals(
                "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
                values.get(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN));
        assertFalse(values.containsKey(ManagedRuntimeConfiguration.IFLYTEK_API_KEY));
    }

    @Test
    public void rejectsUnknownOrSupplierSecretFields() {
        assertThrows(IllegalArgumentException.class, () -> DebugPrivateProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"backendBaseUrl\":\"https://bb.chinacedar.top:2305/v9-ops\","
                        + "\"bootstrapCredential\":\"abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE\","
                        + "\"iflytekApiSecret\":\"must-not-enter-apk-provisioning\"}"));
    }

    @Test
    public void rejectsHttpAndMalformedBootstrapCredentials() {
        assertThrows(IllegalArgumentException.class, () -> DebugPrivateProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"backendBaseUrl\":\"http://bb.chinacedar.top/v9-ops\","
                        + "\"bootstrapCredential\":\"short token\"}"));
    }

    @Test
    public void managedBackendPairAlwaysWinsOverDebugFallback() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "https://mdm.example.com/v9");
        Map<String, String> debug = new HashMap<>();
        debug.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "https://debug.example.com/v9");
        debug.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, "debug-bootstrap-token");

        Map<String, String> merged = DebugPrivateProvisioning.mergeAfterManaged(managed, debug);

        assertEquals("https://mdm.example.com/v9", merged.get(
                ManagedRuntimeConfiguration.BACKEND_BASE_URL));
        assertFalse(merged.containsKey(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN));
    }

    @Test
    public void completeDebugPairFillsAnUnmanagedDeviceOnly() {
        Map<String, String> debug = new HashMap<>();
        debug.put(ManagedRuntimeConfiguration.BACKEND_BASE_URL, "https://debug.example.com/v9");
        debug.put(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN, "debug-bootstrap-token");

        Map<String, String> merged = DebugPrivateProvisioning.mergeAfterManaged(
                new HashMap<String, String>(), debug);

        assertEquals("https://debug.example.com/v9", merged.get(
                ManagedRuntimeConfiguration.BACKEND_BASE_URL));
        assertEquals("debug-bootstrap-token", merged.get(
                ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN));
    }
}
