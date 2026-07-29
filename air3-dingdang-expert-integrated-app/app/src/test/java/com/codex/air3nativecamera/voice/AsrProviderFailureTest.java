package com.codex.air3nativecamera.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AsrProviderFailureTest {
    @Test
    public void dashScopeArrearageIsReportedAsAccountUnavailable() {
        String frame = "{\"header\":{\"event\":\"task-failed\","
                + "\"error_code\":\"Arrearage\","
                + "\"error_message\":\"Access denied\"},\"payload\":{}}";

        AsrProviderFailure failure = AsrProviderFailure.fromDashScopeFrame(frame);

        assertEquals("asr_provider_account_unavailable", failure.diagnosticCode());
        assertTrue(failure.userMessage().contains("语音转写服务账户不可用"));
        assertTrue(failure.shouldUseLocalFallback());
    }

    @Test
    public void quotaFailureUsesLocalFallback() {
        String frame = "{\"header\":{\"event\":\"task-failed\","
                + "\"error_code\":\"QuotaExhausted\","
                + "\"error_message\":\"Quota exhausted\"}}";

        AsrProviderFailure failure = AsrProviderFailure.fromDashScopeFrame(frame);

        assertEquals("asr_provider_quota_exhausted", failure.diagnosticCode());
        assertTrue(failure.shouldUseLocalFallback());
    }

    @Test
    public void unknownFailurePreservesGenericDiagnostic() {
        AsrProviderFailure failure = AsrProviderFailure.fromDashScopeFrame(
                "{\"header\":{\"event\":\"task-failed\",\"error_code\":\"Unknown\"}}");

        assertEquals("asr_task_failed", failure.diagnosticCode());
        assertEquals("语音转写失败，已保留本轮录音", failure.userMessage());
    }
}
