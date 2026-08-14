package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.sync.DeviceAccessTokenProvider;
import com.codex.air3nativecamera.sync.DeviceSyncConfiguration;
import com.codex.air3nativecamera.sync.SkillKnowledgeManifestClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class ExecutionContextHudPresenterManifestTest {
    @Test
    public void cachedSkillDirectoryIsVisibleButHasNoActivationActions() throws Exception {
        SkillKnowledgeManifestClient.Manifest manifest = manifest();

        OperationDetail detail = new ExecutionContextHudPresenter()
                .skillManifestCatalog(manifest);

        assertEquals("AI运维技能", detail.title());
        assertEquals(1, detail.items().size());
        assertTrue(detail.items().get(0).contains("HVAC maintenance"));
        assertEquals("", detail.itemActions().get(0));
        assertEquals("managed_skill_refresh", detail.primaryAction());
    }

    @Test
    public void knowledgeDirectoryShowsOnlyAuthorizedReferencesAndConfirmationState()
            throws Exception {
        SkillKnowledgeManifestClient.Manifest manifest = manifest();
        ExecutionContextHudPresenter presenter = new ExecutionContextHudPresenter();

        OperationDetail cached = presenter.knowledgeCatalog(manifest, false);
        OperationDetail confirmed = presenter.knowledgeCatalog(manifest, true);

        assertEquals("华方知识库", cached.title());
        assertEquals(1, cached.items().size());
        assertTrue(cached.items().get(0).contains("HVAC service manual"));
        assertTrue(cached.description().contains("等待服务端确认"));
        assertTrue(confirmed.description().contains("服务端已确认"));
        assertEquals("managed_knowledge_refresh", confirmed.primaryAction());
    }

    private static SkillKnowledgeManifestClient.Manifest manifest() throws Exception {
        final byte[] value = manifestJson().getBytes(StandardCharsets.UTF_8);
        SkillKnowledgeManifestClient.Storage storage = new SkillKnowledgeManifestClient.Storage() {
            @Override public byte[] read(String localProjectId) { return value; }
            @Override public void writeAtomically(String localProjectId, byte[] next) { }
            @Override public void delete(String localProjectId) { }
        };
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        SkillKnowledgeManifestClient client = new SkillKnowledgeManifestClient(
                configuration,
                new DeviceAccessTokenProvider() {
                    @Override public String accessToken() { return "access-a"; }
                },
                storage);
        return client.loadCached("project-a");
    }

    private static String manifestJson() throws Exception {
        JSONObject skill = new JSONObject()
                .put("skillId", "skill-hvac")
                .put("versionId", "skill-hvac@1.0.0")
                .put("version", "1.0.0")
                .put("name", "HVAC maintenance")
                .put("description", "Authorized workflow")
                .put("contentSha256", repeat('1', 64))
                .put("knowledgeScopes", new JSONArray().put("hvac-manuals"))
                .put("authorizationScope", "project");
        JSONObject knowledge = new JSONObject()
                .put("knowledgeId", "knowledge-hvac")
                .put("versionId", "knowledge-hvac@3")
                .put("version", 3)
                .put("title", "HVAC service manual")
                .put("summary", "Authorized reference")
                .put("language", "zh-CN")
                .put("sensitivity", "internal")
                .put("sourceType", "document")
                .put("sourceReference", "kb://hvac/manual-3")
                .put("contentSha256", repeat('2', 64))
                .put("knowledgeScopes", new JSONArray().put("hvac-manuals"))
                .put("validFrom", JSONObject.NULL)
                .put("expiresAt", JSONObject.NULL)
                .put("authorizationScope", "skill_version");
        return new JSONObject()
                .put("manifestVersion", 7)
                .put("etag", repeat('a', 64))
                .put("localProjectId", "project-a")
                .put("projectId", "project-cloud-a")
                .put("generatedAt", "2026-08-03T07:00:00Z")
                .put("expiresAt", "2099-12-31T23:59:00Z")
                .put("skills", new JSONArray().put(skill))
                .put("knowledge", new JSONArray().put(knowledge))
                .toString();
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
