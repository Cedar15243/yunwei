package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.LinkedHashSet;

public final class WorkflowPackageStoreTest {
    @Test
    public void savesAndRestoresOnlyAReverifiedSnapshotForTheSameImmutableVersion() throws Exception {
        Fixture fixture = fixture();
        MemoryStorage storage = new MemoryStorage();
        WorkflowPackageStore store = new WorkflowPackageStore(storage, fixture.verifier);
        WorkflowPackageVerification verified = fixture.verifier.verify(fixture.envelope);
        WorkflowRuntimeState state = new WorkflowStateMachine(verified.workflowPackage()).start();

        WorkflowStoreResult saved = store.save(fixture.envelope, state);
        WorkflowStoreResult loaded = store.load();

        assertTrue(saved.succeeded());
        assertEquals(WorkflowStoreResult.Code.NONE, saved.code());
        assertTrue(loaded.succeeded());
        assertNotNull(loaded.snapshot());
        assertEquals(verified.workflowPackage().workflowVersionId(),
                loaded.snapshot().workflowPackage().workflowVersionId());
        assertEquals("photo", loaded.snapshot().runtimeState().currentNodeId());
    }

    @Test
    public void preservesThePreviousSnapshotWhenANewPackageIsTamperedOrAtomicWriteFails() throws Exception {
        Fixture fixture = fixture();
        MemoryStorage storage = new MemoryStorage();
        WorkflowPackageStore store = new WorkflowPackageStore(storage, fixture.verifier);
        WorkflowPackage workflowPackage = fixture.verifier.verify(fixture.envelope).workflowPackage();
        WorkflowRuntimeState state = new WorkflowStateMachine(workflowPackage).start();
        assertTrue(store.save(fixture.envelope, state).succeeded());
        byte[] previous = storage.read();

        JSONObject tampered = new JSONObject(fixture.envelope.toString());
        tampered.getJSONObject("executionPackage").getJSONArray("nodes")
                .getJSONObject(1).getJSONObject("config").put("title", "篡改内容");
        WorkflowStoreResult rejected = store.save(tampered, state);
        assertFalse(rejected.succeeded());
        assertEquals(WorkflowStoreResult.Code.PACKAGE_REJECTED, rejected.code());
        assertArrayEquals(previous, storage.read());

        storage.failWrites = true;
        WorkflowStoreResult writeFailed = store.save(fixture.envelope, state);
        assertFalse(writeFailed.succeeded());
        assertEquals(WorkflowStoreResult.Code.WRITE_FAILED, writeFailed.code());
        assertArrayEquals(previous, storage.read());
    }

    @Test
    public void refusesToRestoreCorruptBytesOrAStateFromAnotherWorkflowVersion() throws Exception {
        Fixture fixture = fixture();
        MemoryStorage storage = new MemoryStorage();
        WorkflowPackageStore store = new WorkflowPackageStore(storage, fixture.verifier);
        storage.value = "not-json".getBytes(StandardCharsets.UTF_8);

        WorkflowStoreResult corrupt = store.load();
        assertFalse(corrupt.succeeded());
        assertEquals(WorkflowStoreResult.Code.SNAPSHOT_CORRUPT, corrupt.code());

        WorkflowPackage workflowPackage = fixture.verifier.verify(fixture.envelope).workflowPackage();
        WorkflowRuntimeState wrongVersion = new WorkflowRuntimeState(
                "44444444-4444-4444-8444-444444444444",
                workflowPackage.startNodeId(),
                WorkflowRuntimeState.Status.ACTIVE,
                0,
                new JSONObject());
        WorkflowStoreResult rejected = store.save(fixture.envelope, wrongVersion);
        assertEquals(WorkflowStoreResult.Code.STATE_INVALID, rejected.code());
    }

    private Fixture fixture() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject executionPackage = executionPackage();
        String digest = sha256(canonicalUnsignedPackage());
        executionPackage.put("contentSha256", digest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(digest.getBytes(StandardCharsets.UTF_8));
        String signature = java.util.Base64.getEncoder().encodeToString(signer.sign());
        JSONObject envelope = new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("schemaVersion", 1)
                .put("executionPackage", executionPackage)
                .put("contentSha256", digest)
                .put("packageSignature", signature)
                .put("signatureKeyId", "workflow-key-a")
                .put("requiredCapabilities", new JSONArray()
                        .put("camera.photo")
                        .put("workflow.runtime.v1"))
                .put("minAppVersionCode", 9000);
        WorkflowPackageVerifier verifier = new WorkflowPackageVerifier(
                keyId -> "workflow-key-a".equals(keyId) ? keyPair.getPublic() : null,
                9002,
                1,
                new LinkedHashSet<>(Arrays.asList("workflow.runtime.v1", "camera.photo")));
        return new Fixture(envelope, verifier);
    }

    private JSONObject executionPackage() throws Exception {
        return new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "设备收货检查")
                .put("nodes", new JSONArray()
                        .put(node("complete", "complete", new JSONObject()))
                        .put(node("photo", "photo_capture", new JSONObject()
                                .put("minCount", 1).put("title", "拍摄设备铭牌")))
                        .put(node("start", "start", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-photo", "start", "photo"))
                        .put(transition("photo-complete", "photo", "complete")))
                .put("requiredCapabilities", new JSONArray()
                        .put("camera.photo")
                        .put("workflow.runtime.v1"));
    }

    private JSONObject node(String id, String type, JSONObject config) throws Exception {
        return new JSONObject().put("config", config).put("nodeId", id).put("type", type);
    }

    private JSONObject transition(String id, String from, String to) throws Exception {
        return new JSONObject().put("fromNodeId", from).put("toNodeId", to).put("transitionId", id);
    }

    private String canonicalUnsignedPackage() {
        return "{\"nodes\":["
                + "{\"config\":{},\"nodeId\":\"complete\",\"type\":\"complete\"},"
                + "{\"config\":{\"minCount\":1,\"title\":\"拍摄设备铭牌\"},\"nodeId\":\"photo\",\"type\":\"photo_capture\"},"
                + "{\"config\":{},\"nodeId\":\"start\",\"type\":\"start\"}],"
                + "\"requiredCapabilities\":[\"camera.photo\",\"workflow.runtime.v1\"],"
                + "\"schemaVersion\":1,\"title\":\"设备收货检查\","
                + "\"transitions\":["
                + "{\"fromNodeId\":\"start\",\"toNodeId\":\"photo\",\"transitionId\":\"start-photo\"},"
                + "{\"fromNodeId\":\"photo\",\"toNodeId\":\"complete\",\"transitionId\":\"photo-complete\"}],"
                + "\"workflowId\":\"22222222-2222-4222-8222-222222222222\"}";
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static final class Fixture {
        private final JSONObject envelope;
        private final WorkflowPackageVerifier verifier;

        private Fixture(JSONObject envelope, WorkflowPackageVerifier verifier) {
            this.envelope = envelope;
            this.verifier = verifier;
        }
    }

    private static final class MemoryStorage implements WorkflowSnapshotStorage {
        private byte[] value;
        private boolean failWrites;

        @Override
        public byte[] read() {
            return value == null ? null : value.clone();
        }

        @Override
        public void writeAtomically(byte[] next) throws IOException {
            if (failWrites) throw new IOException("simulated write failure");
            value = next.clone();
        }
    }
}
