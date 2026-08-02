package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WorkflowEvidenceUploadCoordinatorTest {
    @Test
    public void persistsThroughSnapshotSourceAndDoesNotLetOneFailureBlockAnotherTask()
            throws Exception {
        File root = Files.createTempDirectory("workflow-evidence").toFile();
        write(root, "task-evidence/a.jpg", new byte[]{1});
        write(root, "task-evidence/b.jpg", new byte[]{2});
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending = new ArrayList<>(Arrays.asList(
                evidence("assignment-a", "local-a", "task-evidence/a.jpg"),
                evidence("assignment-b", "local-b", "task-evidence/b.jpg")));
        List<String> attempts = new ArrayList<>();
        List<String> acknowledged = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        long[] now = {1000L};
        boolean[] failFirst = {true};
        WorkflowEvidenceUploadCoordinator coordinator =
                new WorkflowEvidenceUploadCoordinator(
                        root,
                        () -> new ArrayList<>(pending),
                        (item, bytes, capturedAt) -> {
                            attempts.add(item.localEvidenceId());
                            assertEquals(1, bytes.length);
                            assertTrue(capturedAt.endsWith("Z"));
                            if ("local-a".equals(item.localEvidenceId()) && failFirst[0]) {
                                throw new IOException("offline");
                            }
                            return "77777777-7777-4777-8777-777777777777";
                        },
                        (assignmentId, localEvidenceId, remoteAssetId) -> {
                            acknowledged.add(localEvidenceId);
                            pending.removeIf(item -> item.localEvidenceId().equals(localEvidenceId));
                            return true;
                        },
                        jobs::add,
                        () -> now[0]);

        coordinator.request();
        assertEquals(1, jobs.size());
        jobs.remove(0).run();

        assertEquals(Arrays.asList("local-a", "local-b"), attempts);
        assertEquals(Arrays.asList("local-b"), acknowledged);
        assertFalse(coordinator.isRunning());

        coordinator.request();
        jobs.remove(0).run();
        assertEquals(2, attempts.size());

        now[0] = 2500L;
        failFirst[0] = false;
        coordinator.request();
        jobs.remove(0).run();
        assertEquals(Arrays.asList("local-a", "local-b", "local-a"), attempts);
        assertEquals(Arrays.asList("local-b", "local-a"), acknowledged);
    }

    @Test
    public void coalescesARequestDuringDeliveryIntoOneFollowUpRun() throws Exception {
        File root = Files.createTempDirectory("workflow-evidence-rerun").toFile();
        write(root, "task-evidence/a.jpg", new byte[]{1});
        write(root, "task-evidence/b.jpg", new byte[]{2});
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending =
                new ArrayList<>();
        pending.add(evidence("assignment-a", "local-a", "task-evidence/a.jpg"));
        List<String> attempts = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        WorkflowEvidenceUploadCoordinator[] coordinator =
                new WorkflowEvidenceUploadCoordinator[1];
        coordinator[0] = new WorkflowEvidenceUploadCoordinator(
                root,
                () -> new ArrayList<>(pending),
                (item, bytes, capturedAt) -> {
                    attempts.add(item.localEvidenceId());
                    if ("local-a".equals(item.localEvidenceId())) {
                        pending.add(evidence(
                                "assignment-b", "local-b", "task-evidence/b.jpg"));
                        coordinator[0].request();
                    }
                    return "77777777-7777-4777-8777-777777777777";
                },
                (assignmentId, localEvidenceId, remoteAssetId) -> {
                    pending.removeIf(item -> item.localEvidenceId().equals(localEvidenceId));
                    return true;
                },
                jobs::add,
                () -> 1000L);

        coordinator[0].request();
        jobs.remove(0).run();

        assertEquals(1, jobs.size());
        assertTrue(coordinator[0].isRunning());
        jobs.remove(0).run();
        assertEquals(Arrays.asList("local-a", "local-b"), attempts);
        assertFalse(coordinator[0].isRunning());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEvidenceReferencesOutsideThePrivateRoot() {
        new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                "assignment-a",
                "11111111-1111-4111-8111-111111111111",
                "local-a",
                "photo-a",
                "nameplate",
                "../outside.jpg");
    }

    private static WorkflowEvidenceUploadCoordinator.PendingEvidence evidence(
            String assignmentId,
            String localId,
            String reference
    ) {
        return new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                assignmentId,
                "11111111-1111-4111-8111-111111111111",
                localId,
                "photo-a",
                "nameplate",
                reference);
    }

    private static void write(File root, String relative, byte[] bytes) throws IOException {
        File file = new File(root, relative);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("unable to create test evidence directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }
}
