package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceActivationUiPolicyTest {
    private static final String BOOTSTRAP =
            "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE";

    @Test
    public void mdmAlwaysWinsAndDoesNotOfferManualActivation() {
        DeviceActivationUiPolicy policy = DeviceActivationUiPolicy.resolve(
                true,
                true,
                record(2_000_000L),
                1_000_000L);

        assertEquals(DeviceActivationUiPolicy.State.MDM_MANAGED, policy.state());
        assertEquals("受管配置已生效", policy.statusLabel());
        assertFalse(policy.canActivate());
        assertTrue(policy.canClearLocalAuthorization());
    }

    @Test
    public void validLocalActivationShowsOnlyNonSecretIdentity() {
        DeviceActivationUiPolicy policy = DeviceActivationUiPolicy.resolve(
                true,
                false,
                record(2_000_000L),
                1_000_000L);

        assertEquals(DeviceActivationUiPolicy.State.LOCAL_ACTIVE, policy.state());
        assertTrue(policy.statusLabel().contains("device-a"));
        assertTrue(policy.statusLabel().contains("policy-a"));
        assertFalse(policy.statusLabel().contains(BOOTSTRAP));
        assertTrue(policy.canActivate());
        assertTrue(policy.canClearLocalAuthorization());
    }

    @Test
    public void missingOrExpiredActivationRequiresARealActivation() {
        DeviceActivationUiPolicy missing = DeviceActivationUiPolicy.resolve(
                true, false, null, 1_000_000L);
        DeviceActivationUiPolicy expired = DeviceActivationUiPolicy.resolve(
                true, false, record(1_000_000L), 1_000_000L);

        assertEquals(DeviceActivationUiPolicy.State.ACTIVATION_REQUIRED, missing.state());
        assertEquals("设备需要激活", missing.statusLabel());
        assertTrue(missing.canActivate());
        assertFalse(missing.canClearLocalAuthorization());
        assertEquals(DeviceActivationUiPolicy.State.ACTIVATION_REQUIRED, expired.state());
        assertEquals("本地授权已过期，需要重新激活", expired.statusLabel());
        assertTrue(expired.canClearLocalAuthorization());
    }

    @Test
    public void mapsGatewayFailuresToActionableChineseWithoutFakeFallback() {
        assertEquals(
                "激活码已过期，请让管理员重新生成",
                DeviceActivationUiPolicy.errorLabel("activation_expired"));
        assertEquals(
                "该激活码已经使用，未改变当前授权",
                DeviceActivationUiPolicy.errorLabel("activation_already_used"));
        assertEquals(
                "设备与激活码不匹配，请检查管理后台绑定",
                DeviceActivationUiPolicy.errorLabel("activation_device_mismatch"));
        assertEquals(
                "激活服务暂时不可用，当前授权未改变",
                DeviceActivationUiPolicy.errorLabel("device_activation_timeout"));
        assertEquals(
                "激活失败，当前授权未改变",
                DeviceActivationUiPolicy.errorLabel("unexpected"));
    }

    private static DeviceActivationRecord record(long expiry) {
        return DeviceActivationRecord.create(
                "https://bb.chinacedar.top:2305/v9-ops",
                BOOTSTRAP,
                expiry,
                "device-a",
                "organization-a",
                "policy-a");
    }
}
