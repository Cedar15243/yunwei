package com.codex.air3nativecamera.runtime;

import java.io.IOException;

/** Stores only the app's device bootstrap; access tokens and supplier keys are excluded. */
public interface DeviceCredentialStore {
    DeviceActivationRecord load() throws IOException;

    void save(DeviceActivationRecord record) throws IOException;

    void clear() throws IOException;

    default boolean clearIfCurrent(String bootstrapCredential) throws IOException {
        DeviceActivationRecord current = load();
        if (current == null || !current.bootstrapCredential().equals(
                bootstrapCredential == null ? "" : bootstrapCredential.trim())) {
            return false;
        }
        clear();
        return true;
    }
}
