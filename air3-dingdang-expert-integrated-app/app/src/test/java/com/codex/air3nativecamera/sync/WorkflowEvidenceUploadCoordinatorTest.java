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
                            assertEquals("photo", item.kind());
                            assertEquals("image/jpeg", item.contentType());
                            assertEquals(0, item.durationSeconds());
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
    public void uploadsAValidatedShortVideoWithoutBlockingTheCaller() throws Exception {
        File root = Files.createTempDirectory("workflow-video-evidence").toFile();
        write(root, "task-evidence/clip.mp4", new byte[]{1, 2, 3, 4});
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending =
                new ArrayList<>();
        pending.add(new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                "assignment-video",
                "11111111-1111-4111-8111-111111111111",
                "local-video",
                "video-a",
                "control-panel",
                WorkflowEvidenceUploadCoordinator.MediaKind.VIDEO,
                "task-evidence/clip.mp4",
                12));
        List<Runnable> jobs = new ArrayList<>();
        List<String> acknowledged = new ArrayList<>();
        WorkflowEvidenceUploadCoordinator coordinator =
                new WorkflowEvidenceUploadCoordinator(
                        root,
                        () -> new ArrayList<>(pending),
                        (item, bytes, capturedAt) -> {
                            assertEquals("video", item.kind());
                            assertEquals("video/mp4", item.contentType());
                            assertEquals(12, item.durationSeconds());
                            assertEquals(4, bytes.length);
                            return "77777777-7777-4777-8777-777777777777";
                        },
                        (assignmentId, localEvidenceId, remoteAssetId) -> {
                            acknowledged.add(localEvidenceId);
                            pending.clear();
                            return true;
                        },
                        jobs::add,
                        () -> 1000L);

        coordinator.request();
        assertTrue(coordinator.isRunning());
        jobs.remove(0).run();

        assertEquals(Arrays.asList("local-video"), acknowledged);
        assertFalse(coordinator.isRunning());
    }

    @Test
    public void prefersResumableTransportWithoutLoadingTheWholeMediaIntoLegacyTransport() throws Exception {
        File root = Files.createTempDirectory("workflow-resumable-evidence").toFile();
        write(root, "task-evidence/clip.mp4", new byte[]{1, 2, 3, 4});
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending = new ArrayList<>();
        pending.add(new WorkflowEvidenceUploadCoordinator.PendingEvidence(
                "assignment-video",
                "11111111-1111-4111-8111-111111111111",
                "local-video",
                "video-a",
                "control-panel",
                WorkflowEvidenceUploadCoordinator.MediaKind.VIDEO,
                "task-evidence/clip.mp4",
                12));
        List<Runnable> jobs = new ArrayList<>();
        List<String> resumableCalls = new ArrayList<>();
        WorkflowEvidenceUploadCoordinator coordinator =
                new WorkflowEvidenceUploadCoordinator(
                        root,
                        () -> pending,
                        new WorkflowEvidenceUploadCoordinator.ResumableTransport() {
                            @Override
                            public String upload(
                                    WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
                                    byte[] bytes,
                                    String capturedAt
                            ) {
                                throw new AssertionError("legacy transport must not be used");
                            }

                            @Override
                            public String uploadResumable(
                                    WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
                                    File file,
                                    String capturedAt
                            )
                                    throws IOException {
                                resumableCalls.add(file.getName());
                                assertEquals(4L, file.length());
                                return "77777777-7777-4777-8777-777777777777";
                            }
                        },
                        (assignmentId, localEvidenceId, remoteAssetId) -> {
                            pending.clear();
                            return true;
                        },
                        jobs::add,
                        () -> 1000L);

        coordinator.request();
        jobs.remove(0).run();

        assertEquals(Arrays.asList("clip.mp4"), resumableCalls);
        assertFalse(coordinator.isRunning());
    }

    @Test
    public void cancellationSignalsTheActiveResumableUploadAndPreventsAcknowledgeOrRerun()
            throws Exception {
        File root = Files.createTempDirectory("workflow-cancel-evidence").toFile();
        write(root, "task-evidence/cancel.jpg", new byte[]{1, 2, 3});
        List<WorkflowEvidenceUploadCoordinator.PendingEvidence> pending =
                new ArrayList<>();
        pending.add(evidence("assignment-cancel", "local-cancel", "task-evidence/cancel.jpg"));
        List<Runnable> jobs = new ArrayList<>();
        List<String> acknowledged = new ArrayList<>();
        boolean[] cancellationSignalled = {false};
        WorkflowEvidenceUploadCoordinator[] coordinator =
                new WorkflowEvidenceUploadCoordinator[1];
        coordinator[0] = new WorkflowEvidenceUploadCoordinator(
                root,
                () -> new ArrayList<>(pending),
                new WorkflowEvidenceUploadCoordinator.CancellableResumableTransport() {
                    @Override
                    public String upload(
                            WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
                            byte[] bytes,
                            String capturedAt
                    ) {
                        throw new AssertionError("legacy transport must not be used");
                    }

                    @Override
                    public String uploadResumable(
                            WorkflowEvidenceUploadCoordinator.PendingEvidence evidence,
                            File file,
                            String capturedAt
                    ) throws IOException {
                        coordinator[0].cancel();
                        assertTrue(cancellationSignalled[0]);
                        throw new WorkflowEvidenceUploadCoordinator.UploadCancelledException();
                    }

                    @Override
                    public void cancelActiveUpload() {
                        cancellationSignalled[0] = true;
                    }
                },
                (assignmentId, localEvidenceId, remoteAssetId) -> {
                    acknowledged.add(localEvidenceId);
                    return true;
                },
                jobs::add,
                () -> 1000L);

        coordinator[0].request();
        jobs.remove(0).run();

        assertTrue(cancellationSignalled[0]);
        assertTrue(acknowledged.isEmpty());
        assertFalse(coordinator[0].isRunning());
        coordinator[0].request();
        assertTrue(jobs.isEmpty());
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
                WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO,
                "../outside.jpg",
                0);
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
                WorkflowEvidenceUploadCoordinator.MediaKind.PHOTO,
                reference,
                0);
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
