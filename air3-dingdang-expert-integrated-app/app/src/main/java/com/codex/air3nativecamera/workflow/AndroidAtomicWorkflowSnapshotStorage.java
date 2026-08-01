package com.codex.air3nativecamera.workflow;

import android.util.AtomicFile;

import com.codex.air3nativecamera.sync.WorkflowAssignmentRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class AndroidAtomicWorkflowSnapshotStorage
        implements WorkflowSnapshotStorage, WorkflowAssignmentRepository.Storage {
    private final AtomicFile file;

    public AndroidAtomicWorkflowSnapshotStorage(File directory, String fileName) {
        if (directory == null || fileName == null || !fileName.matches("^[A-Za-z0-9_.-]{1,120}$")) {
            throw new IllegalArgumentException("workflow snapshot path is invalid");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("workflow snapshot directory is unavailable");
        }
        file = new AtomicFile(new File(directory, fileName));
    }

    @Override
    public synchronized byte[] read() throws IOException {
        if (!file.getBaseFile().exists()) return null;
        return file.readFully();
    }

    @Override
    public synchronized void writeAtomically(byte[] value) throws IOException {
        if (value == null) throw new IllegalArgumentException("workflow snapshot value is required");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(value);
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException exception) {
            if (output != null) file.failWrite(output);
            throw exception;
        }
    }
}
