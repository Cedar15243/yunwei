package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class DebugPrivateWakeProvisioningTest {
    @Test
    public void parsesOnlyACompleteIflytekOfflineWakeCredentialSet() {
        Map<String, String> values = DebugPrivateWakeProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"iflytekAppId\":\"wake-app-123\","
                        + "\"iflytekApiKey\":\"wake-api-key-123456\","
                        + "\"iflytekApiSecret\":\"wake-api-secret-123456\"}");

        assertEquals("wake-app-123", values.get(ManagedRuntimeConfiguration.IFLYTEK_APP_ID));
        assertEquals("wake-api-key-123456", values.get(
                ManagedRuntimeConfiguration.IFLYTEK_API_KEY));
        assertEquals("wake-api-secret-123456", values.get(
                ManagedRuntimeConfiguration.IFLYTEK_API_SECRET));
        assertFalse(values.containsKey(ManagedRuntimeConfiguration.BACKEND_DEVICE_TOKEN));
    }

    @Test
    public void rejectsPartialWakeCredentialsAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> DebugPrivateWakeProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"iflytekAppId\":\"wake-app-123\","
                        + "\"iflytekApiKey\":\"wake-api-key-123456\"}"));
        assertThrows(IllegalArgumentException.class, () -> DebugPrivateWakeProvisioning.parse(
                "{\"schemaVersion\":1,"
                        + "\"iflytekAppId\":\"wake-app-123\","
                        + "\"iflytekApiKey\":\"wake-api-key-123456\","
                        + "\"iflytekApiSecret\":\"wake-api-secret-123456\","
                        + "\"voiceprintSecret\":\"forbidden\"}"));
    }

    @Test
    public void managedWakeAuthorizationWinsWithoutMixingCredentialSources() {
        Map<String, String> managed = new HashMap<>();
        managed.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, "managed-app");
        Map<String, String> debug = completeDebugWakeCredentials();

        Map<String, String> merged = DebugPrivateWakeProvisioning.mergeAfterManaged(
                managed, debug);

        assertEquals("managed-app", merged.get(ManagedRuntimeConfiguration.IFLYTEK_APP_ID));
        assertFalse(merged.containsKey(ManagedRuntimeConfiguration.IFLYTEK_API_KEY));
        assertFalse(merged.containsKey(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET));
    }

    @Test
    public void completeDebugWakeAuthorizationFillsAnUnmanagedAuditDevice() {
        Map<String, String> merged = DebugPrivateWakeProvisioning.mergeAfterManaged(
                new HashMap<String, String>(), completeDebugWakeCredentials());

        assertEquals("debug-app", merged.get(ManagedRuntimeConfiguration.IFLYTEK_APP_ID));
        assertEquals("debug-key-123456", merged.get(
                ManagedRuntimeConfiguration.IFLYTEK_API_KEY));
        assertEquals("debug-secret-123456", merged.get(
                ManagedRuntimeConfiguration.IFLYTEK_API_SECRET));
    }

    private static Map<String, String> completeDebugWakeCredentials() {
        Map<String, String> values = new HashMap<>();
        values.put(ManagedRuntimeConfiguration.IFLYTEK_APP_ID, "debug-app");
        values.put(ManagedRuntimeConfiguration.IFLYTEK_API_KEY, "debug-key-123456");
        values.put(ManagedRuntimeConfiguration.IFLYTEK_API_SECRET, "debug-secret-123456");
        return values;
    }
}
