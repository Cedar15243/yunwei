package com.codex.air3nativecamera.runtime;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/** Debug variant only: reads audit wake authorization from app-private storage. */
public final class DebugPrivateWakeProvisioningReader {
    private DebugPrivateWakeProvisioningReader() {
    }

    public static Map<String, String> load(Context context, boolean enabled) {
        if (!enabled || context == null) return Collections.emptyMap();
        File file = new File(context.getFilesDir(), DebugPrivateWakeProvisioning.FILE_NAME);
        if (!file.isFile() || file.length() <= 0
                || file.length() > DebugPrivateWakeProvisioning.MAX_FILE_BYTES) {
            return Collections.emptyMap();
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > DebugPrivateWakeProvisioning.MAX_FILE_BYTES) {
                return Collections.emptyMap();
            }
            return DebugPrivateWakeProvisioning.parse(
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }
}
