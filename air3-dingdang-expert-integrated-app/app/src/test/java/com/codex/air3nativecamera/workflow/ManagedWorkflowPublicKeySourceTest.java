package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class ManagedWorkflowPublicKeySourceTest {
    @Test
    public void loadsARotatableEd25519TrustSetFromManagedJson() throws Exception {
        KeyPair first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject managed = new JSONObject()
                .put("schemaVersion", 1)
                .put("keys", new JSONArray()
                        .put(key("workflow-2026-08", first))
                        .put(key("workflow-2026-09", second)));

        ManagedWorkflowPublicKeySource source =
                ManagedWorkflowPublicKeySource.fromManagedJson(managed.toString());

        assertEquals(2, source.size());
        assertNotNull(source.find("workflow-2026-08"));
        assertNotNull(source.find("workflow-2026-09"));
        assertNull(source.find("unknown"));
    }

    @Test
    public void rejectsMalformedDuplicateOrExpandedTrustConfiguration() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JSONObject validKey = key("workflow-2026-08", pair);

        assertThrows(IllegalArgumentException.class,
                () -> ManagedWorkflowPublicKeySource.fromManagedJson(""));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedWorkflowPublicKeySource.fromManagedJson(
                        new JSONObject().put("schemaVersion", 1.5)
                                .put("keys", new JSONArray().put(validKey)).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedWorkflowPublicKeySource.fromManagedJson(
                        new JSONObject().put("schemaVersion", 1)
                                .put("keys", new JSONArray().put(validKey).put(validKey)).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedWorkflowPublicKeySource.fromManagedJson(
                        new JSONObject().put("schemaVersion", 1)
                                .put("keys", new JSONArray().put(
                                        new JSONObject(validKey.toString())
                                                .put("algorithm", "RSA"))).toString()));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedWorkflowPublicKeySource.fromManagedJson(
                        new JSONObject().put("schemaVersion", 1)
                                .put("keys", new JSONArray().put(
                                        new JSONObject(validKey.toString())
                                                .put("unexpected", true))).toString()));
    }

    private static JSONObject key(String keyId, KeyPair pair) throws Exception {
        return new JSONObject()
                .put("keyId", keyId)
                .put("algorithm", "Ed25519")
                .put("publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
}
