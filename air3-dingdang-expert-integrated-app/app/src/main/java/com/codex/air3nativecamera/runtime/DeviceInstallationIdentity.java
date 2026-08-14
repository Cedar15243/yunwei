package com.codex.air3nativecamera.runtime;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.UUID;

/** Owns the random installation identifier used only for device activation binding. */
public final class DeviceInstallationIdentity {
    interface Store {
        String read();

        boolean write(String value);
    }

    interface Generator {
        String generate();
    }

    private static final String FIELD_INSTALLATION_ID = "installation_id";

    private final Store store;
    private final Generator generator;

    public DeviceInstallationIdentity(final SharedPreferences preferences) {
        this(new Store() {
            @Override
            public String read() {
                return preferences == null
                        ? "" : preferences.getString(FIELD_INSTALLATION_ID, "");
            }

            @Override
            public boolean write(String value) {
                return preferences != null && preferences.edit()
                        .putString(FIELD_INSTALLATION_ID, value)
                        .commit();
            }
        }, new Generator() {
            @Override
            public String generate() {
                return UUID.randomUUID().toString();
            }
        });
    }

    DeviceInstallationIdentity(Store store, Generator generator) {
        if (store == null || generator == null) {
            throw new IllegalArgumentException("device_installation_id_configuration_invalid");
        }
        this.store = store;
        this.generator = generator;
    }

    public synchronized String getOrCreate() throws IOException {
        String existing = clean(store.read());
        if (isCanonicalUuid(existing)) return existing;
        String generated = clean(generator.generate());
        if (!isCanonicalUuid(generated)) {
            throw new IOException("device_installation_id_generation_failed");
        }
        if (!store.write(generated)) {
            throw new IOException("device_installation_id_write_failed");
        }
        return generated;
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return value.length() == 36 && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
