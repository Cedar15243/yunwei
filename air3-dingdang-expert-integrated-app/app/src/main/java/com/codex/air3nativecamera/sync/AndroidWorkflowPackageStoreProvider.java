package com.codex.air3nativecamera.sync;

import android.util.AtomicFile;

import com.codex.air3nativecamera.workflow.AndroidAtomicWorkflowSnapshotStorage;
import com.codex.air3nativecamera.workflow.WorkflowPackageStore;
import com.codex.air3nativecamera.workflow.WorkflowPackageVerifier;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AndroidWorkflowPackageStoreProvider
        implements VerifiedWorkflowPackageCache.StoreProvider {
    private final File directory;
    private final WorkflowPackageVerifier verifier;

    public AndroidWorkflowPackageStoreProvider(File directory, WorkflowPackageVerifier verifier) {
        if (directory == null || verifier == null) {
            throw new IllegalArgumentException("workflow package store configuration is invalid");
        }
        this.directory = directory;
        this.verifier = verifier;
    }

    @Override
    public WorkflowPackageStore storeFor(String assignmentId) {
        String fileName = fileNameForAssignment(assignmentId);
        return new WorkflowPackageStore(
                new AndroidAtomicWorkflowSnapshotStorage(directory, fileName),
                verifier);
    }

    @Override
    public boolean invalidate(String assignmentId) {
        String fileName = fileNameForAssignment(assignmentId);
        if (!directory.exists()) return true;
        AtomicFile file = new AtomicFile(new File(directory, fileName));
        file.delete();
        return !file.getBaseFile().exists();
    }

    static String fileNameForAssignment(String assignmentId) {
        String id = clean(assignmentId);
        if (!id.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
            throw new IllegalArgumentException("workflow assignment identifier is invalid");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("workflow-");
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.append(".json").toString();
        } catch (Exception exception) {
            throw new IllegalStateException("workflow package file digest is unavailable", exception);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
