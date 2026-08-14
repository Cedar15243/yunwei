package com.codex.air3nativecamera.runtime;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/** Debug variant only: reads the ADB-delivered bootstrap from app-private storage. */
public final class DebugPrivateProvisioningReader {
    private DebugPrivateProvisioningReader() {
    }

    public static Map<String, String> load(Context context, boolean enabled) {
        if (!enabled || context == null) return Collections.emptyMap();
        File file = new File(context.getFilesDir(), DebugPrivateProvisioning.FILE_NAME);
        if (!file.isFile() || file.length() <= 0
                || file.length() > DebugPrivateProvisioning.MAX_FILE_BYTES) {
            return Collections.emptyMap();
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > DebugPrivateProvisioning.MAX_FILE_BYTES) {
                return Collections.emptyMap();
            }
            return DebugPrivateProvisioning.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }
}
