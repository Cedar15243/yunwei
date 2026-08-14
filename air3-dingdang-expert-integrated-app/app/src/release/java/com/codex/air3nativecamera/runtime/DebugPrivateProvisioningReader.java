package com.codex.air3nativecamera.runtime;

import android.content.Context;

import java.util.Collections;
import java.util.Map;

/** Release variant: app-private debug provisioning is permanently disabled. */
public final class DebugPrivateProvisioningReader {
    private DebugPrivateProvisioningReader() {
    }

    public static Map<String, String> load(Context context, boolean enabled) {
        return Collections.emptyMap();
    }
}
