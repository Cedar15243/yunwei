package com.codex.air3nativecamera.runtime;

/** Converts activation authority into safe settings text and available actions. */
public final class DeviceActivationUiPolicy {
    public enum State {
        UNMANAGED,
        MDM_MANAGED,
        LOCAL_ACTIVE,
        ACTIVATION_REQUIRED
    }

    private final State state;
    private final DeviceActivationRecord localActivation;
    private final boolean expiredLocalActivation;

    private DeviceActivationUiPolicy(
            State state,
            DeviceActivationRecord localActivation,
            boolean expiredLocalActivation) {
        this.state = state;
        this.localActivation = localActivation;
        this.expiredLocalActivation = expiredLocalActivation;
    }

    public static DeviceActivationUiPolicy resolve(
            boolean secureRuntime,
            boolean mdmBackendComplete,
            DeviceActivationRecord localActivation,
            long nowMillis) {
        if (!secureRuntime) {
            return new DeviceActivationUiPolicy(State.UNMANAGED, localActivation, false);
        }
        boolean localValid = localActivation != null && localActivation.isValidAt(nowMillis);
        boolean localExpired = localActivation != null && !localValid;
        if (mdmBackendComplete) {
            return new DeviceActivationUiPolicy(
                    State.MDM_MANAGED, localActivation, localExpired);
        }
        if (localValid) {
            return new DeviceActivationUiPolicy(
                    State.LOCAL_ACTIVE, localActivation, false);
        }
        return new DeviceActivationUiPolicy(
                State.ACTIVATION_REQUIRED, localActivation, localExpired);
    }

    public State state() {
        return state;
    }

    public String statusLabel() {
        if (state == State.UNMANAGED) return "当前版本不使用正式设备激活";
        if (state == State.MDM_MANAGED) return "受管配置已生效";
        if (state == State.LOCAL_ACTIVE && localActivation != null) {
            return "本机已激活 · 设备 " + localActivation.deviceId()
                    + " · 策略 " + localActivation.policyVersion();
        }
        return expiredLocalActivation
                ? "本地授权已过期，需要重新激活" : "设备需要激活";
    }

    public boolean canActivate() {
        return state == State.LOCAL_ACTIVE || state == State.ACTIVATION_REQUIRED;
    }

    public boolean canClearLocalAuthorization() {
        return localActivation != null;
    }

    public static String errorLabel(String errorCode) {
        String error = errorCode == null ? "" : errorCode.trim();
        if ("activation_expired".equals(error)) {
            return "激活码已过期，请让管理员重新生成";
        }
        if ("activation_already_used".equals(error)) {
            return "该激活码已经使用，未改变当前授权";
        }
        if ("activation_device_mismatch".equals(error)) {
            return "设备与激活码不匹配，请检查管理后台绑定";
        }
        if ("device_revoked".equals(error)
                || "device_binding_revoked".equals(error)) {
            return "设备授权已被撤销，请联系管理员";
        }
        if ("device_activation_timeout".equals(error)
                || "device_activation_network_error".equals(error)
                || "device_activation_service_unavailable".equals(error)) {
            return "激活服务暂时不可用，当前授权未改变";
        }
        if ("device_activation_response_cacheable".equals(error)
                || "device_activation_response_forbidden_field".equals(error)
                || "device_activation_response_invalid".equals(error)) {
            return "激活响应未通过安全校验，当前授权未改变";
        }
        return "激活失败，当前授权未改变";
    }
}
