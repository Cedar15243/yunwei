package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Assume;
import org.junit.Test;

public final class SecureRuntimeGeneratedConfigTest {
    @Test
    public void secureRuntimeContainsNoClientSideAiOrProviderConfiguration() {
        Assume.assumeTrue(GeneratedConfig.SECURE_RUNTIME);

        assertFalse(GeneratedConfig.DIRECT_GPT_ENABLED);
        assertEquals("", GeneratedConfig.OPS_GLASSES_API_KEY);
        assertEquals("", GeneratedConfig.DINGDANG_BACKEND_API_KEY);
        assertEquals("", GeneratedConfig.DIRECT_GPT_BASE_URL);
        assertEquals("", GeneratedConfig.DIRECT_GPT_MODEL);
        assertEquals("", GeneratedConfig.DIRECT_GPT_REASONING_EFFORT);
        assertEquals("", GeneratedConfig.DIRECT_GPT_API_KEY);
        assertEquals("", GeneratedConfig.DIRECT_ASR_ENDPOINT);
        assertEquals("", GeneratedConfig.DIRECT_ASR_API_KEY);
        assertEquals("", GeneratedConfig.IFLYTEK_APP_ID);
        assertEquals("", GeneratedConfig.IFLYTEK_API_KEY);
        assertEquals("", GeneratedConfig.IFLYTEK_API_SECRET);
    }
}
