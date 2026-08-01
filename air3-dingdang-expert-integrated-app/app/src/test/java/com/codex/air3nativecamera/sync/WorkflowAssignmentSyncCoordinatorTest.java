package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WorkflowAssignmentSyncCoordinatorTest {
    @Test
    public void persistsTheCursorThenFetchesAndInstallsMissingPackages() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        FakeGateway gateway = new FakeGateway(page(
                2L,
                assignment("assignment-a", "order-a", "version-a", "required", "queued", 1L),
                assignment("assignment-b", "order-b", "version-b", "optional", "queued", 2L)));
        FakePackageCache packages = new FakePackageCache();
        WorkflowAssignmentSyncCoordinator coordinator = new WorkflowAssignmentSyncCoordinator(
                gateway, repository, packages, 4);

        WorkflowAssignmentSyncCoordinator.Result result = coordinator.syncOnce();

        assertEquals(WorkflowAssignmentSyncCoordinator.Code.SUCCESS, result.code());
        assertEquals(2L, repository.cursor());
        assertEquals(Arrays.asList("assignment-b", "assignment-a"), gateway.fetched);
        assertTrue(repository.find("assignment-a").packageCached());
        assertTrue(repository.find("assignment-b").packageCached());
        assertEquals(2L, new WorkflowAssignmentRepository(storage).cursor());
    }

    @Test
    public void aPackageFailureDoesNotRollBackThePersistedCursorAndRetriesWithoutAFullReplay() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        FakeGateway gateway = new FakeGateway(page(
                1L,
                assignment("assignment-a", "order-a", "version-a", "required", "queued", 1L)));
        gateway.failFetch.add("assignment-a");
        FakePackageCache packages = new FakePackageCache();
        WorkflowAssignmentSyncCoordinator coordinator = new WorkflowAssignmentSyncCoordinator(
                gateway, repository, packages, 4);

        assertEquals(WorkflowAssignmentSyncCoordinator.Code.PARTIAL_FAILURE,
                coordinator.syncOnce().code());
        assertEquals(1L, repository.cursor());
        assertFalse(repository.find("assignment-a").packageCached());

        gateway.failFetch.clear();
        gateway.page = page(1L);
        assertEquals(WorkflowAssignmentSyncCoordinator.Code.SUCCESS,
                coordinator.syncOnce().code());
        assertEquals(Arrays.asList("assignment-a", "assignment-a"), gateway.fetched);
        assertTrue(repository.find("assignment-a").packageCached());
    }

    @Test
    public void revocationInvalidatesAPreviouslyInstalledPackageWithoutRefetching() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        FakePackageCache packages = new FakePackageCache();
        FakeGateway gateway = new FakeGateway(page(
                1L,
                assignment("assignment-a", "order-a", "version-a", "required", "ready", 1L)));
        WorkflowAssignmentSyncCoordinator coordinator = new WorkflowAssignmentSyncCoordinator(
                gateway, repository, packages, 4);
        coordinator.syncOnce();
        gateway.page = page(
                2L,
                assignment("assignment-a", "order-a", "version-a", "required", "revoked", 2L));

        WorkflowAssignmentSyncCoordinator.Result result = coordinator.syncOnce();

        assertEquals(WorkflowAssignmentSyncCoordinator.Code.SUCCESS, result.code());
        assertTrue(packages.invalidated.contains("assignment-a"));
        assertFalse(repository.find("assignment-a").packageCached());
        assertEquals(1, gateway.fetched.size());
    }

    @Test
    public void limitsPackageWorkPerPassAndReportsMoreWork() {
        MemoryStorage storage = new MemoryStorage();
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(storage);
        FakeGateway gateway = new FakeGateway(page(
                3L,
                assignment("assignment-a", "order-a", "version-a", "required", "queued", 1L),
                assignment("assignment-b", "order-b", "version-b", "required", "queued", 2L),
                assignment("assignment-c", "order-c", "version-c", "required", "queued", 3L)));
        WorkflowAssignmentSyncCoordinator coordinator = new WorkflowAssignmentSyncCoordinator(
                gateway, repository, new FakePackageCache(), 2);

        WorkflowAssignmentSyncCoordinator.Result result = coordinator.syncOnce();

        assertEquals(2, result.packagesInstalled());
        assertTrue(result.moreWork());
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
                assignmentId, workOrderId, "project-a", workflowVersionId, mode, status,
                deliverySequence, "2026-08-01T00:00:00Z");
    }

    private static final class MemoryStorage implements WorkflowAssignmentRepository.Storage {
        private byte[] bytes;

        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public void writeAtomically(byte[] value) { bytes = value.clone(); }
    }

    private static final class FakeGateway implements WorkflowAssignmentGateway {
        private WorkflowDeviceHttpClient.AssignmentPage page;
        private final List<String> fetched = new ArrayList<>();
        private final Set<String> failFetch = new HashSet<>();

        private FakeGateway(WorkflowDeviceHttpClient.AssignmentPage page) {
            this.page = page;
        }

        @Override
        public WorkflowDeviceHttpClient.AssignmentPage listAssignments(long afterSequence, int limit) {
            return page;
        }

        @Override
        public JSONObject fetchPackage(String assignmentId) throws IOException {
            fetched.add(assignmentId);
            if (failFetch.contains(assignmentId)) throw new IOException("offline");
            try {
                return new JSONObject()
                        .put("assignmentId", assignmentId)
                        .put("workflowVersionId", "version-"
                                + assignmentId.substring(assignmentId.length() - 1));
            } catch (Exception exception) {
                throw new IOException("fixture_invalid", exception);
            }
        }
    }

    private static final class FakePackageCache implements WorkflowAssignmentSyncCoordinator.PackageCache {
        private final Set<String> available = new HashSet<>();
        private final Set<String> invalidated = new HashSet<>();

        @Override
        public boolean isAvailable(WorkflowAssignmentRepository.CachedAssignment assignment) {
            return available.contains(assignment.assignmentId());
        }

        @Override
        public boolean install(
                WorkflowAssignmentRepository.CachedAssignment assignment,
                JSONObject envelope
        ) {
            if (!assignment.assignmentId().equals(envelope.optString("assignmentId", ""))) return false;
            available.add(assignment.assignmentId());
            return true;
        }

        @Override
        public boolean invalidate(String assignmentId) {
            invalidated.add(assignmentId);
            available.remove(assignmentId);
            return true;
        }
    }
}
