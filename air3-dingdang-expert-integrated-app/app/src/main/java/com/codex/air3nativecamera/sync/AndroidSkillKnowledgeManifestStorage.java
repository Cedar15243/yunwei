package com.codex.air3nativecamera.sync;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Project-isolated atomic storage for the minimal Skill and knowledge directory. */
public final class AndroidSkillKnowledgeManifestStorage
        implements SkillKnowledgeManifestClient.Storage {
    private final File directory;

    public AndroidSkillKnowledgeManifestStorage(File directory) {
        if (directory == null) {
            throw new IllegalArgumentException("content manifest directory is required");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("content manifest directory is unavailable");
        }
        this.directory = directory;
    }

    @Override
    public synchronized byte[] read(String localProjectId) throws IOException {
        AtomicFile file = file(localProjectId);
        return file.getBaseFile().exists() ? file.readFully() : null;
    }

    @Override
    public synchronized void writeAtomically(String localProjectId, byte[] value)
            throws IOException {
        if (value == null) throw new IllegalArgumentException("content manifest value is required");
        AtomicFile file = file(localProjectId);
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

    @Override
    public synchronized void delete(String localProjectId) {
        file(localProjectId).delete();
    }

    private AtomicFile file(String localProjectId) {
        return new AtomicFile(new File(directory, fileName(localProjectId)));
    }

    static String fileName(String localProjectId) {
        String value = localProjectId == null ? "" : localProjectId.trim();
        if (!value.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$")) {
            throw new IllegalArgumentException("content manifest project is invalid");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("content-manifest-");
            for (byte item : digest) name.append(String.format("%02x", item & 0xff));
            return name.append(".json").toString();
        } catch (Exception exception) {
            throw new IllegalStateException("content manifest digest is unavailable", exception);
        }
    }
}
