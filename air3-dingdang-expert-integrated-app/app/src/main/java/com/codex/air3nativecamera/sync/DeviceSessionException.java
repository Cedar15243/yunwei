package com.codex.air3nativecamera.sync;

import java.io.IOException;

/** Stable device-session failure without response bodies or credential material. */
public final class DeviceSessionException extends IOException {
    private final String errorCode;
    private final int httpStatus;
    private final boolean authoritativeRevocation;

    public DeviceSessionException(
            String errorCode,
            int httpStatus,
            boolean authoritativeRevocation) {
        super(clean(errorCode));
        this.errorCode = clean(errorCode);
        this.httpStatus = httpStatus;
        this.authoritativeRevocation = authoritativeRevocation;
    }

    public String errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isAuthoritativeRevocation() {
        return authoritativeRevocation;
    }

    private static String clean(String value) {
        String result = value == null ? "" : value.trim();
        return result.length() == 0 ? "device_session_failed" : result;
    }
}
