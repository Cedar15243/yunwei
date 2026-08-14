package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

public final class MvsWorkOrderEvidenceDraftStoreTest {
    @Test
    public void persistsOrderBoundPhotoDraftsWithOnlyLocalStateAcrossRestart() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        MvsWorkOrderEvidenceDraftStore store = new MvsWorkOrderEvidenceDraftStore(
                storage, () -> 1_700_000_000_000L);

        MvsWorkOrderEvidenceDraftStore.Draft draft = store.recordPhoto(
                "42",
                "mvs-work-order-evidence/42/capture-a.jpg",
                512,
                repeat('a', 64));

        assertEquals("42", draft.orderId());
        assertEquals("draft", draft.state());
        assertEquals(1, store.countForOrder("42"));
        assertTrue(storage.value.length > 0);

        MvsWorkOrderEvidenceDraftStore restored = new MvsWorkOrderEvidenceDraftStore(
                storage, () -> 1_700_000_100_000L);
        MvsWorkOrderEvidenceDraftStore.Draft restoredDraft =
                restored.draftsForOrder("42").get(0);
        assertEquals(draft.id(), restoredDraft.id());
        assertEquals("mvs-work-order-evidence/42/capture-a.jpg",
                restoredDraft.localReference());
        assertEquals("draft", restoredDraft.state());
    }

    @Test
    public void rejectsCrossOrderPathsAndCanDiscardOnlyTheRecordedDraft() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        MvsWorkOrderEvidenceDraftStore store = new MvsWorkOrderEvidenceDraftStore(storage);
        try {
            store.recordPhoto("42", "mvs-work-order-evidence/43/capture-a.jpg",
                    512, repeat('b', 64));
            throw new AssertionError("cross-order evidence path must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reference"));
        }

        MvsWorkOrderEvidenceDraftStore.Draft first = store.recordPhoto(
                "42", "mvs-work-order-evidence/42/capture-a.jpg", 512, repeat('b', 64));
        MvsWorkOrderEvidenceDraftStore.Draft second = store.recordPhoto(
                "43", "mvs-work-order-evidence/43/capture-b.jpg", 512, repeat('c', 64));

        assertEquals(first.id(), store.discard("42", first.id()).id());
        assertEquals(0, store.countForOrder("42"));
        assertEquals(second.id(), store.draftsForOrder("43").get(0).id());
        assertFalse(store.discard("42", second.id()) != null);
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
