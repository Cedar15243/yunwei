package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;

public final class WorkflowAssignmentRepositoryTest {
    @Test
    public void persistsMultipleAssignmentsAndAdvancesTheCursorAtomically() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);

        WorkflowAssignmentRepository.ApplyResult result = repository.apply(page(
                5L,
                assignment("assignment-a", "order-a", "version-a", "required", "queued", 4L),
                assignment("assignment-b", "order-b", "version-b", "optional", "ready", 5L)));

        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED, result);
        assertEquals(5L, repository.cursor());
        assertEquals(2, repository.assignments().size());
        WorkflowAssignmentRepository restored = new WorkflowAssignmentRepository(storage);
        assertEquals(5L, restored.cursor());
        assertEquals("version-a", restored.find("assignment-a").workflowVersionId());
        assertEquals("ready", restored.find("assignment-b").status());
    }

    @Test
    public void aHigherSequenceRevocationInvalidatesTheCachedPackage() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED, repository.apply(page(
                7L,
                assignment("assignment-a", "order-a", "version-a", "required", "ready", 7L))));
        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED,
                repository.markPackageCached("assignment-a", "version-a"));
        assertTrue(repository.find("assignment-a").packageCached());

        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED, repository.apply(page(
                8L,
                assignment("assignment-a", "order-a", "version-a", "required", "revoked", 8L))));

        WorkflowAssignmentRepository.CachedAssignment revoked = repository.find("assignment-a");
        assertNotNull(revoked);
        assertEquals("revoked", revoked.status());
        assertFalse(revoked.packageCached());
        assertEquals(8L, repository.cursor());
    }

    @Test
    public void rejectsVersionMutationAndCursorRegression() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        repository.apply(page(
                7L,
                assignment("assignment-a", "order-a", "version-a", "required", "ready", 7L)));

        assertEquals(WorkflowAssignmentRepository.ApplyResult.REJECTED, repository.apply(page(
                8L,
                assignment("assignment-a", "order-a", "version-b", "required", "ready", 8L))));
        assertEquals(WorkflowAssignmentRepository.ApplyResult.REJECTED,
                repository.apply(new WorkflowDeviceHttpClient.AssignmentPage(
                        Collections.<WorkflowDeviceHttpClient.Assignment>emptyList(), 6L)));
        assertEquals(7L, repository.cursor());
        assertEquals("version-a", repository.find("assignment-a").workflowVersionId());
    }

    @Test
    public void failedAtomicWriteKeepsThePreviousCursorAndAssignments() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        repository.apply(page(
                3L,
                assignment("assignment-a", "order-a", "version-a", "required", "queued", 3L)));
        storage.failNextWrite = true;

        assertEquals(WorkflowAssignmentRepository.ApplyResult.PERSIST_FAILED, repository.apply(page(
                4L,
                assignment("assignment-b", "order-b", "version-b", "required", "queued", 4L))));

        assertEquals(3L, repository.cursor());
        assertNotNull(repository.find("assignment-a"));
        assertEquals(null, repository.find("assignment-b"));
    }

    @Test
    public void missingOrDeletedPackageCanBeMarkedForRefetch() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        repository.apply(page(
                7L,
                assignment("assignment-a", "order-a", "version-a", "required", "ready", 7L)));
        repository.markPackageCached("assignment-a", "version-a");

        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED,
                repository.markPackageUnavailable("assignment-a", "version-a"));
        assertFalse(repository.find("assignment-a").packageCached());
        assertFalse(new WorkflowAssignmentRepository(storage)
                .find("assignment-a").packageCached());
    }

    @Test
    public void persistsTheWhitelistedWorkOrderSummaryAcrossRestart() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);

        assertEquals(WorkflowAssignmentRepository.ApplyResult.APPLIED,
                repository.apply(page(7L, assignmentWithSummary())));

        WorkflowAssignmentRepository.CachedAssignment restored =
                new WorkflowAssignmentRepository(storage).find("assignment-a");
        Object summary = restored.getClass().getMethod("workOrder").invoke(restored);
        assertEquals("冷水机组控制器故障",
                summary.getClass().getMethod("title").invoke(summary));
        assertEquals("HF-CH-01", summary.getClass().getMethod("assetModel").invoke(summary));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsFractionalCacheSchemaInsteadOfTruncatingIt() {
        MemoryStorage storage = new MemoryStorage();
        storage.bytes = ("{\"schema_version\":1.5,\"cursor\":0,\"assignments\":[]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        new WorkflowAssignmentRepository(storage);
    }

    private static WorkflowDeviceHttpClient.AssignmentPage page(
            long nextSequence,
            WorkflowDeviceHttpClient.Assignment... assignments
    ) {
        return new WorkflowDeviceHttpClient.AssignmentPage(Arrays.asList(assignments), nextSequence);
    }

    private static WorkflowDeviceHttpClient.Assignment assignment(
            String assignmentId,
            String workOrderId,
            String workflowVersionId,
            String mode,
            String status,
            long deliverySequence
    ) {
        return new WorkflowDeviceHttpClient.Assignment(
                assignmentId,
                workOrderId,
                "project-a",
                workflowVersionId,
                mode,
                status,
                deliverySequence,
                "2026-08-01T00:00:00Z");
    }

    private static WorkflowDeviceHttpClient.Assignment assignmentWithSummary() throws Exception {
        Class<?> summaryType = Class.forName(
                "com.codex.air3nativecamera.sync.WorkflowDeviceHttpClient$WorkOrderSummary");
        Constructor<?> summaryConstructor = Arrays.stream(summaryType.getDeclaredConstructors())
                .filter(item -> item.getParameterTypes().length == 14)
                .findFirst()
                .orElseThrow(NoSuchMethodException::new);
        summaryConstructor.setAccessible(true);
        Object summary = summaryConstructor.newInstance(
                "MVS-20260801-001", "冷水机组控制器故障", "控制器报警",
                "customer-a", "repair", "asset-a", "hvac", "Huafang",
                "HF-CH-01", "high", "medium", "received",
                "2026-08-02T01:00:00Z", "2026-08-01T00:30:00Z");
        Constructor<WorkflowDeviceHttpClient.Assignment> constructor =
                WorkflowDeviceHttpClient.Assignment.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        String.class, String.class, long.class, String.class, summaryType);
        constructor.setAccessible(true);
        return constructor.newInstance(
                "assignment-a", "order-a", "project-a", "version-a", "required",
                "ready", 7L, "2026-08-01T00:00:00Z", summary);
    }

    private static final class MemoryStorage implements WorkflowAssignmentRepository.Storage {
        private byte[] bytes;
        private boolean failNextWrite;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void writeAtomically(byte[] next) throws IOException {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IOException("disk_full");
            }
            bytes = next.clone();
        }
    }
}
