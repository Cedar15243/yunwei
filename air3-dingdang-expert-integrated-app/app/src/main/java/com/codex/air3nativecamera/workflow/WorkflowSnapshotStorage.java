package com.codex.air3nativecamera.workflow;

import java.io.IOException;

public interface WorkflowSnapshotStorage {
    byte[] read() throws IOException;

    void writeAtomically(byte[] value) throws IOException;
}
