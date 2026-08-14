package com.codex.air3nativecamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.sync.DeviceAccessTokenProvider;
import com.codex.air3nativecamera.sync.DeviceSyncConfiguration;
import com.codex.air3nativecamera.workflow.MvsWorkOrderEvidenceDraftStore;
import com.codex.air3nativecamera.workflow.WorkflowSnapshotStorage;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public final class MainActivityMvsWorkOrderTest {
    @Test
    public void createsMvsClientOnlyForSecureRuntimeWithShortSessionProvider() {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        DeviceAccessTokenProvider session = () -> "access-a";

        assertNotNull(MainActivity.createMvsWorkOrderDeviceClient(
                true, configuration, session));
        assertNull(MainActivity.createMvsWorkOrderDeviceClient(
                false, configuration, session));
        assertNull(MainActivity.createMvsWorkOrderDeviceClient(
                true, configuration, null));
    }

    @Test
    public void acceptsOnlyFreshAndAccurateLocationsForCheckin() {
        long now = 1_000_000L;

        assertTrue(MainActivity.mvsLocationUsable(now, now - 30_000L, 25f));
        assertFalse(MainActivity.mvsLocationUsable(now, now - 180_000L, 25f));
        assertFalse(MainActivity.mvsLocationUsable(now, now - 30_000L, 800f));
        assertFalse(MainActivity.mvsLocationUsable(now, now + 1L, 25f));
    }

    @Test
    public void acceptsOnlyExplicitMvsCheckinDirections() {
        assertEquals("in", MainActivity.normalizeMvsCheckinDirection("in"));
        assertEquals("out", MainActivity.normalizeMvsCheckinDirection("out"));
        assertEquals("", MainActivity.normalizeMvsCheckinDirection("sideways"));
        assertEquals("", MainActivity.normalizeMvsCheckinDirection(""));
        assertEquals("", MainActivity.normalizeMvsCheckinDirection(null));
    }

    @Test
    public void removesMissingMvsEvidenceDraftsWhileKeepingValidLocalPhotos() throws Exception {
        File filesDir = Files.createTempDirectory("mvs-evidence-reconcile").toFile();
        File orderDirectory = new File(filesDir, "mvs-work-order-evidence/42");
        assertTrue(orderDirectory.mkdirs());
        File validPhoto = new File(orderDirectory, "capture-a.jpg");
        try (FileOutputStream output = new FileOutputStream(validPhoto)) {
            output.write(new byte[]{1, 2, 3});
        }

        MvsWorkOrderEvidenceDraftStore store = new MvsWorkOrderEvidenceDraftStore(
                new MemoryStorage());
        store.recordPhoto("42", "mvs-work-order-evidence/42/capture-a.jpg",
                3, repeat('a', 64));
        store.recordPhoto("42", "mvs-work-order-evidence/42/capture-missing.jpg",
                4, repeat('b', 64));

        assertEquals(1, MainActivity.reconcileMvsEvidenceDrafts(filesDir, store, "42"));
        assertEquals("capture-a.jpg", new File(
                filesDir, store.draftsForOrder("42").get(0).localReference()).getName());
    }

    @Test
    public void deletesOnlyTheConfirmedOrdersLocalEvidenceDraft() throws Exception {
        File filesDir = Files.createTempDirectory("mvs-evidence-delete").toFile();
        File orderDirectory = new File(filesDir, "mvs-work-order-evidence/42");
        assertTrue(orderDirectory.mkdirs());
        File photo = new File(orderDirectory, "capture-a.jpg");
        try (FileOutputStream output = new FileOutputStream(photo)) {
            output.write(new byte[]{1, 2, 3});
        }
        MvsWorkOrderEvidenceDraftStore store = new MvsWorkOrderEvidenceDraftStore(
                new MemoryStorage());
        MvsWorkOrderEvidenceDraftStore.Draft draft = store.recordPhoto(
                "42",
                "mvs-work-order-evidence/42/capture-a.jpg",
                3,
                repeat('a', 64));

        assertTrue(MainActivity.deleteMvsEvidenceDraft(filesDir, store, "42", draft.id()));
        assertFalse(photo.exists());
        assertEquals(0, store.countForOrder("42"));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class MemoryStorage implements WorkflowSnapshotStorage {
        private byte[] value;

        @Override public byte[] read() {
            return value == null ? null : value.clone();
        }

        @Override public void writeAtomically(byte[] next) throws IOException {
            value = next.clone();
        }
    }
}
