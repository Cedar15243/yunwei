package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.workflow.WorkflowPackageStore;
import com.codex.air3nativecamera.workflow.WorkflowPackageVerifier;
import com.codex.air3nativecamera.workflow.WorkflowPublicKeySource;
import com.codex.air3nativecamera.workflow.WorkflowSnapshotStorage;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class VerifiedWorkflowPackageCacheTest {
    @Test
    public void installsOnlyAVerifiedPackageForTheExactAssignmentAndVersion() throws Exception {
        Fixture fixture = fixture("assignment-a", "version-a");
        TestStoreProvider stores = new TestStoreProvider(fixture.verifier);
        VerifiedWorkflowPackageCache cache = new VerifiedWorkflowPackageCache(
                fixture.verifier, stores);
        WorkflowAssignmentRepository.CachedAssignment assignment = assignment(
                "assignment-a", "version-a");

        assertTrue(cache.install(assignment, fixture.envelope));
        assertTrue(cache.isAvailable(assignment));

        WorkflowAssignmentRepository.CachedAssignment wrongAssignment = assignment(
                "assignment-b", "version-a");
        assertFalse(cache.install(wrongAssignment, fixture.envelope));
        assertFalse(cache.isAvailable(wrongAssignment));
    }

    @Test
    public void tamperingAndInvalidationFailClosed() throws Exception {
        Fixture fixture = fixture("assignment-a", "version-a");
        TestStoreProvider stores = new TestStoreProvider(fixture.verifier);
        VerifiedWorkflowPackageCache cache = new VerifiedWorkflowPackageCache(
                fixture.verifier, stores);
        WorkflowAssignmentRepository.CachedAssignment assignment = assignment(
                "assignment-a", "version-a");
        JSONObject tampered = new JSONObject(fixture.envelope.toString());
        tampered.getJSONObject("executionPackage").put("title", "tampered");

        assertFalse(cache.install(assignment, tampered));
        assertFalse(cache.isAvailable(assignment));
        assertTrue(cache.install(assignment, fixture.envelope));
        assertTrue(cache.invalidate("assignment-a"));
        assertFalse(cache.isAvailable(assignment));
    }

    @Test
    public void androidStoreNamesAreContentAddressedAndPathSafe() {
        String first = AndroidWorkflowPackageStoreProvider.fileNameForAssignment(
                "assignment-a");
        String second = AndroidWorkflowPackageStoreProvider.fileNameForAssignment(
                "assignment-b");

        assertTrue(first.matches("^workflow-[0-9a-f]{64}\\.json$"));
        assertFalse(first.contains("assignment-a"));
        assertFalse(first.equals(second));
    }

    private static WorkflowAssignmentRepository.CachedAssignment assignment(
            String assignmentId,
            String versionId
    ) {
        WorkflowAssignmentRepository repository = new WorkflowAssignmentRepository(
                new MemoryAssignmentStorage());
        repository.apply(new WorkflowDeviceHttpClient.AssignmentPage(
                Collections.singletonList(new WorkflowDeviceHttpClient.Assignment(
                        assignmentId,
                        "order-a",
                        "project-a",
                        versionId,
                        "required",
                        "queued",
                        1L,
                        "2026-08-01T00:00:00Z")),
                1L));
        return repository.find(assignmentId);
    }

    private static Fixture fixture(String assignmentId, String versionId) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "workflow-a")
                .put("schemaVersion", 1)
                .put("title", "Receiving")
                .put("requiredCapabilities", new JSONArray(Arrays.asList(
                        "workflow.runtime.v1", "camera.photo")))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("photo", "photo_capture", new JSONObject()
                                .put("evidenceKey", "photo")
                                .put("minCount", 1)))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-photo", "start", "photo"))
                        .put(transition("photo-complete", "photo", "complete")));
        String digest = sha256(canonicalUnsigned(executionPackage));
        executionPackage.put("contentSha256", digest);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(digest.getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject()
                .put("assignmentId", assignmentId)
                .put("workflowVersionId", versionId)
                .put("schemaVersion", 1)
                .put("executionPackage", executionPackage)
                .put("contentSha256", digest)
                .put("packageSignature", Base64.getEncoder().encodeToString(signature.sign()))
                .put("signatureKeyId", "key-a")
                .put("requiredCapabilities", new JSONArray(Arrays.asList(
                        "workflow.runtime.v1", "camera.photo")))
                .put("minAppVersionCode", 9000);
        LinkedHashSet<String> capabilities = new LinkedHashSet<>(Arrays.asList(
                "workflow.runtime.v1", "camera.photo"));
        WorkflowPackageVerifier verifier = new WorkflowPackageVerifier(
                new WorkflowPublicKeySource() {
                    @Override
                    public java.security.PublicKey find(String keyId) {
                        return "key-a".equals(keyId) ? keyPair.getPublic() : null;
                    }
                },
                9003,
                1,
                capabilities);
        return new Fixture(envelope, verifier);
    }

    private static JSONObject node(String id, String type, JSONObject config) throws Exception {
        return new JSONObject().put("nodeId", id).put("type", type).put("config", config);
    }

    private static JSONObject transition(String id, String from, String to) throws Exception {
        return new JSONObject()
                .put("transitionId", id)
                .put("fromNodeId", from)
                .put("toNodeId", to);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : digest) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static String canonicalUnsigned(JSONObject value) throws Exception {
        JSONObject unsigned = new JSONObject(value.toString());
        unsigned.remove("contentSha256");
        return canonical(unsigned);
    }

    private static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return JSONObject.numberToString((Number) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < array.length(); index += 1) {
                if (index > 0) output.append(',');
                output.append(canonical(array.get(index)));
            }
            return output.append(']').toString();
        }
        JSONObject object = (JSONObject) value;
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        StringBuilder output = new StringBuilder("{");
        for (int index = 0; index < keys.size(); index += 1) {
            if (index > 0) output.append(',');
            String key = keys.get(index);
            output.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
        }
        return output.append('}').toString();
    }

    private static final class Fixture {
        private final JSONObject envelope;
        private final WorkflowPackageVerifier verifier;

        private Fixture(JSONObject envelope, WorkflowPackageVerifier verifier) {
            this.envelope = envelope;
            this.verifier = verifier;
        }
    }

    private static final class TestStoreProvider implements VerifiedWorkflowPackageCache.StoreProvider {
        private final WorkflowPackageVerifier verifier;
        private final Map<String, MemorySnapshotStorage> values = new HashMap<>();

        private TestStoreProvider(WorkflowPackageVerifier verifier) {
            this.verifier = verifier;
        }

        @Override
        public WorkflowPackageStore storeFor(String assignmentId) {
            MemorySnapshotStorage storage = values.get(assignmentId);
            if (storage == null) {
                storage = new MemorySnapshotStorage();
                values.put(assignmentId, storage);
            }
            return new WorkflowPackageStore(storage, verifier);
        }

        @Override
        public boolean invalidate(String assignmentId) {
            MemorySnapshotStorage storage = values.remove(assignmentId);
            if (storage != null) storage.bytes = null;
            return true;
        }
    }

    private static final class MemorySnapshotStorage implements WorkflowSnapshotStorage {
        private byte[] bytes;

        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public void writeAtomically(byte[] value) { bytes = value.clone(); }
    }

    private static final class MemoryAssignmentStorage implements WorkflowAssignmentRepository.Storage {
        private byte[] bytes;

        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public void writeAtomically(byte[] value) { bytes = value.clone(); }
    }
}
