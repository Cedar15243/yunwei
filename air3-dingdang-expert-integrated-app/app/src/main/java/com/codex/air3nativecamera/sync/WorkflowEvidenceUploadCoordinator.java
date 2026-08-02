package com.codex.air3nativecamera.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Uploads unresolved workflow evidence already persisted in workflow snapshots. */
public final class WorkflowEvidenceUploadCoordinator {
    private static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    private static final int MAX_UPLOADS_PER_RUN = 8;

    public interface PendingSource {
        List<PendingEvidence> pending();
    }

    public interface Transport {
        String upload(PendingEvidence evidence, byte[] bytes, String capturedAt)
                throws IOException;
    }

    public interface Acknowledger {
        boolean acknowledge(String assignmentId, String localEvidenceId, String remoteAssetId);
    }

    interface Clock {
        long now();
    }

    public static final class PendingEvidence {
        private final String assignmentId;
        private final String executionId;
        private final String localEvidenceId;
        private final String nodeId;
        private final String evidenceKey;
        private final String localReference;

        public PendingEvidence(
                String assignmentId,
                String executionId,
                String localEvidenceId,
                String nodeId,
                String evidenceKey,
                String localReference
        ) {
            this.assignmentId = identifier(assignmentId, 200);
            this.executionId = uuid(executionId);
            this.localEvidenceId = identifier(localEvidenceId, 160);
            this.nodeId = identifier(nodeId, 160);
            this.evidenceKey = identifier(evidenceKey, 160);
            this.localReference = reference(localReference);
        }

        public String assignmentId() { return assignmentId; }
        public String executionId() { return executionId; }
        public String localEvidenceId() { return localEvidenceId; }
        public String nodeId() { return nodeId; }
        public String evidenceKey() { return evidenceKey; }
        public String localReference() { return localReference; }
    }

    private final File filesRoot;
    private final PendingSource source;
    private final Transport transport;
    private final Acknowledger acknowledger;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Integer> failureCounts = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private boolean running;
    private boolean rerunRequested;

    public WorkflowEvidenceUploadCoordinator(
            File filesRoot,
            PendingSource source,
            Transport transport,
            Acknowledger acknowledger,
            Executor executor
    ) {
        this(filesRoot, source, transport, acknowledger, executor,
                System::currentTimeMillis);
    }

    WorkflowEvidenceUploadCoordinator(
            File filesRoot,
            PendingSource source,
            Transport transport,
            Acknowledger acknowledger,
            Executor executor,
            Clock clock
    ) {
        if (filesRoot == null || source == null || transport == null
                || acknowledger == null || executor == null || clock == null) {
            throw new IllegalArgumentException("workflow evidence uploader configuration is invalid");
        }
        this.filesRoot = filesRoot;
        this.source = source;
        this.transport = transport;
        this.acknowledger = acknowledger;
        this.executor = executor;
        this.clock = clock;
    }

    public synchronized void request() {
        if (running) {
            rerunRequested = true;
            return;
        }
        running = true;
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        deliver();
                    } finally {
                        boolean rerun;
                        synchronized (WorkflowEvidenceUploadCoordinator.this) {
                            running = false;
                            rerun = rerunRequested;
                            rerunRequested = false;
                        }
                        if (rerun) request();
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            running = false;
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private void deliver() {
        List<PendingEvidence> values;
        try {
            List<PendingEvidence> pending = source.pending();
            values = pending == null
                    ? Collections.<PendingEvidence>emptyList()
                    : new ArrayList<>(pending);
        } catch (RuntimeException exception) {
            return;
        }
        Set<String> seen = new HashSet<>();
        int attempted = 0;
        for (PendingEvidence evidence : values) {
            if (evidence == null || attempted >= MAX_UPLOADS_PER_RUN) break;
            String key = evidence.assignmentId() + ":" + evidence.localEvidenceId();
            if (!seen.add(key) || !ready(key, clock.now())) continue;
            attempted++;
            try {
                File file = resolve(evidence.localReference());
                byte[] bytes = read(file);
                String remoteAssetId = transport.upload(
                        evidence, bytes, timestamp(file.lastModified()));
                if (!acknowledger.acknowledge(
                        evidence.assignmentId(), evidence.localEvidenceId(), remoteAssetId)) {
                    throw new IOException("workflow_evidence_acknowledge_failed");
                }
                synchronized (this) {
                    failureCounts.remove(key);
                    retryAt.remove(key);
                }
            } catch (IOException | RuntimeException exception) {
                markFailed(key, clock.now());
            }
        }
    }

    private synchronized boolean ready(String key, long now) {
        Long next = retryAt.get(key);
        return next == null || next <= now;
    }

    private synchronized void markFailed(String key, long now) {
        int failures = Math.min(10, failureCounts.containsKey(key)
                ? failureCounts.get(key) + 1 : 1);
        failureCounts.put(key, failures);
        long delay = Math.min(60_000L, 1_000L << Math.min(6, failures - 1));
        retryAt.put(key, now + delay);
    }

    private File resolve(String localReference) throws IOException {
        File root = filesRoot.getCanonicalFile();
        File target = new File(root, localReference).getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        if (!target.getPath().startsWith(rootPrefix) || !target.isFile()) {
            throw new IOException("workflow evidence file is unavailable");
        }
        return target;
    }

    private static byte[] read(File file) throws IOException {
        long length = file.length();
        if (length < 1L || length > MAX_PHOTO_BYTES) {
            throw new IOException("workflow evidence file size is invalid");
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length || input.read() != -1) {
                throw new IOException("workflow evidence file changed during upload");
            }
        }
        return bytes;
    }

    private static String timestamp(long value) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(Math.max(0L, value)));
    }

    private static String identifier(String value, int maxLength) {
        String accepted = value == null ? "" : value.trim();
        if (accepted.length() > maxLength
                || !accepted.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]*$")) {
            throw new IllegalArgumentException("workflow evidence identifier is invalid");
        }
        return accepted;
    }

    private static String uuid(String value) {
        String accepted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!accepted.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("workflow execution identifier is invalid");
        }
        return accepted;
    }

    private static String reference(String value) {
        String accepted = value == null ? "" : value.trim();
        if (accepted.isEmpty() || accepted.length() > 1024
                || accepted.startsWith("/") || accepted.contains("\\")
                || accepted.contains(":") || accepted.contains("..")
                || !accepted.matches("^[A-Za-z0-9][A-Za-z0-9_./-]*$")) {
            throw new IllegalArgumentException("workflow evidence reference is invalid");
        }
        return accepted;
    }
}
