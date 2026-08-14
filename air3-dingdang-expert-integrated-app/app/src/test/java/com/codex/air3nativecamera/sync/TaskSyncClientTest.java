package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class TaskSyncClientTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exposesCorruptQueueInsteadOfSilentlyStartingEmpty() throws Exception {
        File queueFile = temporaryFolder.newFile("events.json");
        Files.write(queueFile.toPath(), "[{\"idempotencyKey\":\"broken\"}".getBytes(StandardCharsets.UTF_8));

        TaskSyncClient client = new TaskSyncClient(queueFile, event -> { });

        assertEquals(0, client.pendingCount());
        assertTrue(client.persistenceError().contains("queue"));
        assertEquals("[{\"idempotencyKey\":\"broken\"}",
                new String(Files.readAllBytes(queueFile.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, temporaryFolder.getRoot().listFiles((dir, name) -> name.startsWith("events.json.corrupt-")).length);
    }
}
