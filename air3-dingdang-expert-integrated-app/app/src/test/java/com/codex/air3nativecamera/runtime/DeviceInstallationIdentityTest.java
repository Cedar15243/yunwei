package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public final class DeviceInstallationIdentityTest {
    @Test
    public void reusesAValidInstallationUuidWithoutGeneratingOrWriting() throws Exception {
        FakeStore store = new FakeStore("11111111-2222-4333-8444-555555555555", true);
        int[] generated = {0};
        DeviceInstallationIdentity identity = new DeviceInstallationIdentity(
                store,
                () -> {
                    generated[0] += 1;
                    return "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
                });

        assertEquals(
                "11111111-2222-4333-8444-555555555555",
                identity.getOrCreate());
        assertEquals(0, generated[0]);
        assertEquals(0, store.writeCalls);
    }

    @Test
    public void replacesInvalidStateWithOnePersistedRandomUuid() throws Exception {
        FakeStore store = new FakeStore("legacy-device-id", true);
        DeviceInstallationIdentity identity = new DeviceInstallationIdentity(
                store,
                () -> "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        assertEquals(
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                identity.getOrCreate());
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", store.value);
        assertEquals(1, store.writeCalls);
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", identity.getOrCreate());
        assertEquals(1, store.writeCalls);
    }

    @Test
    public void rejectsInvalidGeneratedValuesAndStorageFailures() {
        DeviceInstallationIdentity invalidGenerator = new DeviceInstallationIdentity(
                new FakeStore("", true),
                () -> "not-a-uuid");
        IOException invalid = assertThrows(
                IOException.class,
                invalidGenerator::getOrCreate);
        assertEquals("device_installation_id_generation_failed", invalid.getMessage());

        DeviceInstallationIdentity failedWrite = new DeviceInstallationIdentity(
                new FakeStore("", false),
                () -> "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        IOException write = assertThrows(IOException.class, failedWrite::getOrCreate);
        assertEquals("device_installation_id_write_failed", write.getMessage());
    }

    private static final class FakeStore implements DeviceInstallationIdentity.Store {
        String value;
        final boolean writesSucceed;
        int writeCalls;

        FakeStore(String value, boolean writesSucceed) {
            this.value = value;
            this.writesSucceed = writesSucceed;
        }

        @Override
        public String read() {
            return value;
        }

        @Override
        public boolean write(String value) {
            writeCalls += 1;
            if (writesSucceed) this.value = value;
            return writesSucceed;
        }
    }
}
