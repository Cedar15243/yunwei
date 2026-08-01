package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.LinkedHashSet;

public final class WorkflowPackageVerifierTest {
    private static final String KEY_ID = "workflow-key-2026-a";
    private static final String WORKFLOW_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void acceptsOnlyACompatiblePackageWithMatchingDigestAndEd25519Signature() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject executionPackage = executionPackage();
        String digest = sha256(canonicalUnsignedPackage());
        executionPackage.put("contentSha256", digest);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(digest.getBytes(StandardCharsets.UTF_8));
        String signature = java.util.Base64.getEncoder().encodeToString(signer.sign());

        JSONObject envelope = envelope(executionPackage, digest, signature);
        WorkflowPackageVerifier verifier = new WorkflowPackageVerifier(
                keyId -> KEY_ID.equals(keyId) ? keyPair.getPublic() : null,
                9002,
                1,
                new LinkedHashSet<>(Arrays.asList(
                        "workflow.runtime.v1",
                        "camera.photo")));

        WorkflowPackageVerification result = verifier.verify(envelope);

        assertTrue(result.isAccepted());
        assertEquals(WorkflowPackageVerification.Error.NONE, result.error());
        assertNotNull(result.workflowPackage());
        assertEquals("start", result.workflowPackage().startNodeId());
        assertEquals("photo", result.workflowPackage().node("photo").nodeId());
    }

    @Test
    public void rejectsTamperingUnknownKeysAndIncompatibleCapabilitiesWithoutReturningAPackage() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject executionPackage = executionPackage();
        String digest = sha256(canonicalUnsignedPackage());
        executionPackage.put("contentSha256", digest);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(digest.getBytes(StandardCharsets.UTF_8));
        String signature = java.util.Base64.getEncoder().encodeToString(signer.sign());
        WorkflowPackageVerifier verifier = new WorkflowPackageVerifier(
                keyId -> KEY_ID.equals(keyId) ? keyPair.getPublic() : null,
                9002,
                1,
                new LinkedHashSet<>(Arrays.asList("workflow.runtime.v1")));

        WorkflowPackageVerification missingCapability = verifier.verify(
                envelope(executionPackage, digest, signature));
        assertFalse(missingCapability.isAccepted());
        assertEquals(WorkflowPackageVerification.Error.MISSING_CAPABILITY, missingCapability.error());
        assertEquals(null, missingCapability.workflowPackage());

        JSONObject tampered = new JSONObject(executionPackage.toString());
        tampered.getJSONArray("nodes").getJSONObject(1).getJSONObject("config")
                .put("title", "已被篡改");
        WorkflowPackageVerification digestMismatch = new WorkflowPackageVerifier(
                keyId -> KEY_ID.equals(keyId) ? keyPair.getPublic() : null,
                9002,
                1,
                new LinkedHashSet<>(Arrays.asList("workflow.runtime.v1", "camera.photo")))
                .verify(envelope(tampered, digest, signature));
        assertEquals(WorkflowPackageVerification.Error.CONTENT_DIGEST_MISMATCH, digestMismatch.error());

        JSONObject unknownKeyEnvelope = envelope(executionPackage, digest, signature);
        unknownKeyEnvelope.put("signatureKeyId", "unknown-key");
        WorkflowPackageVerification unknownKey = verifierWithAllCapabilities(keyPair)
                .verify(unknownKeyEnvelope);
        assertEquals(WorkflowPackageVerification.Error.UNKNOWN_SIGNATURE_KEY, unknownKey.error());
    }

    private WorkflowPackageVerifier verifierWithAllCapabilities(KeyPair keyPair) {
        return new WorkflowPackageVerifier(
                keyId -> KEY_ID.equals(keyId) ? keyPair.getPublic() : null,
                9002,
                1,
                new LinkedHashSet<>(Arrays.asList("workflow.runtime.v1", "camera.photo")));
    }

    private JSONObject envelope(JSONObject executionPackage, String digest, String signature) throws Exception {
        return new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("schemaVersion", 1)
                .put("executionPackage", executionPackage)
                .put("contentSha256", digest)
                .put("packageSignature", signature)
                .put("signatureKeyId", KEY_ID)
                .put("requiredCapabilities", new JSONArray()
                        .put("camera.photo")
                        .put("workflow.runtime.v1"))
                .put("minAppVersionCode", 9000);
    }

    private JSONObject executionPackage() throws Exception {
        JSONObject start = new JSONObject()
                .put("type", "start")
                .put("config", new JSONObject())
                .put("nodeId", "start");
        JSONObject photo = new JSONObject()
                .put("config", new JSONObject()
                        .put("minCount", 1)
                        .put("title", "拍摄设备铭牌"))
                .put("nodeId", "photo")
                .put("type", "photo_capture");
        JSONObject complete = new JSONObject()
                .put("nodeId", "complete")
                .put("type", "complete")
                .put("config", new JSONObject());
        return new JSONObject()
                .put("title", "设备收货检查")
                .put("workflowId", WORKFLOW_ID)
                .put("transitions", new JSONArray()
                        .put(new JSONObject()
                                .put("toNodeId", "photo")
                                .put("transitionId", "start-photo")
                                .put("fromNodeId", "start"))
                        .put(new JSONObject()
                                .put("fromNodeId", "photo")
                                .put("toNodeId", "complete")
                                .put("transitionId", "photo-complete")))
                .put("schemaVersion", 1)
                .put("requiredCapabilities", new JSONArray()
                        .put("camera.photo")
                        .put("workflow.runtime.v1"))
                .put("nodes", new JSONArray().put(complete).put(photo).put(start));
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
                + "\"workflowId\":\"" + WORKFLOW_ID + "\"}";
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }
}
