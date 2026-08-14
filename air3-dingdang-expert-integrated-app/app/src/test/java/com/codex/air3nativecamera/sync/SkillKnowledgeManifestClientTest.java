package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public final class SkillKnowledgeManifestClientTest {
    private static final long NOW_MILLIS = Instant.parse("2026-08-03T08:00:00Z").toEpochMilli();

    @Test
    public void savesA200ManifestAndLoadsCacheWithoutAuthorizationConfirmation() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, manifest(7, repeat('a', 64), "project-a", true),
                header("ETag", "\"" + repeat('a', 64) + "\""));
        SkillKnowledgeManifestClient client = client(storage, connections, new MutableClock());

        SkillKnowledgeManifestClient.Manifest refreshed = client.refresh("project-a");
        SkillKnowledgeManifestClient.Manifest cached = client.loadCached("project-a");

        assertTrue(refreshed.networkConfirmed());
        assertFalse(cached.networkConfirmed());
        assertEquals(7, cached.manifestVersion());
        assertEquals("skill-hvac@1.0.0", cached.skills().get(0).versionId());
        assertEquals("knowledge-hvac@3", cached.knowledge().get(0).versionId());
        assertTrue(connections.opened.get(0).endsWith(
                "/device-sync/content-manifest?localProjectId=project-a"));
        assertEquals("Bearer access-a",
                connections.used.get(0).getRequestProperty("Authorization"));
        assertNull(connections.used.get(0).getRequestProperty("If-None-Match"));
    }

    @Test
    public void sendsIfNoneMatchAndUsesA304OnlyAsFreshNetworkConfirmation() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String etag = repeat('b', 64);
        connections.enqueue(200, manifest(8, etag, "project-a", true),
                header("ETag", "\"" + etag + "\""));
        connections.enqueue(304, "", headers(
                "ETag", "\"" + etag + "\"",
                "X-Manifest-Version", "8",
                "X-Manifest-Expires-At", "2026-08-03T08:15:00Z"));
        SkillKnowledgeManifestClient client = client(storage, connections, new MutableClock());

        client.refresh("project-a");
        SkillKnowledgeManifestClient.Manifest confirmed = client.refresh("project-a");

        assertTrue(confirmed.networkConfirmed());
        assertEquals("\"" + etag + "\"",
                connections.used.get(1).getRequestProperty("If-None-Match"));
        assertEquals(2, connections.used.size());
    }

    @Test
    public void rejectsA304WhoseEtagDoesNotMatchTheCachedManifest() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String cachedEtag = repeat('b', 64);
        connections.enqueue(200, manifest(8, cachedEtag, "project-a", true),
                header("ETag", "\"" + cachedEtag + "\""));
        connections.enqueue(304, "", headers(
                "ETag", "\"" + repeat('c', 64) + "\"",
                "X-Manifest-Version", "8",
                "X-Manifest-Expires-At", "2026-08-03T08:15:00Z"));
        SkillKnowledgeManifestClient client = client(storage, connections, new MutableClock());
        client.refresh("project-a");

        assertIOException("content_manifest_etag_mismatch", new ThrowingCall() {
            @Override public void run() throws Exception { client.refresh("project-a"); }
        });
    }

    @Test
    public void retriesWithoutEtagWhenAnExpiredCacheReceives304() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        MutableClock clock = new MutableClock();
        String oldEtag = repeat('c', 64);
        String newEtag = repeat('d', 64);
        connections.enqueue(200, manifestWithTimes(9, oldEtag, "project-a", true,
                        "2026-08-03T07:59:00Z", "2026-08-03T08:01:00Z"),
                header("ETag", "\"" + oldEtag + "\""));
        SkillKnowledgeManifestClient client = client(storage, connections, clock);
        client.refresh("project-a");
        clock.nowMillis = Instant.parse("2026-08-03T08:02:00Z").toEpochMilli();
        connections.enqueue(304, "", header("ETag", "\"" + oldEtag + "\""));
        connections.enqueue(200, manifestWithTimes(10, newEtag, "project-a", false,
                        "2026-08-03T08:02:00Z", "2026-08-03T08:17:00Z"),
                header("ETag", "\"" + newEtag + "\""));

        SkillKnowledgeManifestClient.Manifest refreshed = client.refresh("project-a");

        assertEquals(10, refreshed.manifestVersion());
        assertTrue(refreshed.skills().isEmpty());
        assertEquals("\"" + oldEtag + "\"",
                connections.used.get(1).getRequestProperty("If-None-Match"));
        assertNull(connections.used.get(2).getRequestProperty("If-None-Match"));
    }

    @Test
    public void replacementManifestPhysicallyRemovesRevokedSkillAndKnowledge() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        QueueConnectionFactory connections = new QueueConnectionFactory();
        String firstEtag = repeat('e', 64);
        String secondEtag = repeat('f', 64);
        connections.enqueue(200, manifest(11, firstEtag, "project-a", true),
                header("ETag", "\"" + firstEtag + "\""));
        connections.enqueue(200, manifest(12, secondEtag, "project-a", false),
                header("ETag", "\"" + secondEtag + "\""));
        SkillKnowledgeManifestClient client = client(storage, connections, new MutableClock());

        client.refresh("project-a");
        client.refresh("project-a");
        SkillKnowledgeManifestClient.Manifest cached = client.loadCached("project-a");

        assertTrue(cached.skills().isEmpty());
        assertTrue(cached.knowledge().isEmpty());
        assertEquals(12, cached.manifestVersion());
    }

    @Test
    public void rejectsProjectMismatchExpiredCacheAndOversizedResponses() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        storage.writeAtomically("project-a", manifest(7, repeat('a', 64), "project-b", true)
                .getBytes(StandardCharsets.UTF_8));
        SkillKnowledgeManifestClient client = client(
                storage, new QueueConnectionFactory(), new MutableClock());
        assertIOException("content_manifest_project_mismatch", new ThrowingCall() {
            @Override public void run() throws Exception { client.loadCached("project-a"); }
        });

        QueueConnectionFactory oversized = new QueueConnectionFactory();
        char[] values = new char[600_000];
        Arrays.fill(values, 'x');
        oversized.enqueue(200, new String(values), header("ETag", "\"" + repeat('a', 64) + "\""));
        SkillKnowledgeManifestClient oversizedClient = client(
                new MemoryStorage(), oversized, new MutableClock());
        assertIOException("content_manifest_response_too_large", new ThrowingCall() {
            @Override public void run() throws Exception { oversizedClient.refresh("project-a"); }
        });
    }

    private SkillKnowledgeManifestClient client(
            MemoryStorage storage,
            QueueConnectionFactory connections,
            MutableClock clock
    ) {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        return new SkillKnowledgeManifestClient(
                configuration,
                new DeviceAccessTokenProvider() {
                    @Override public String accessToken() { return "access-a"; }
                },
                storage,
                connections,
                clock);
    }

    private static String manifest(int version, String etag, String localProjectId,
            boolean includeContent) throws Exception {
        return manifestWithTimes(version, etag, localProjectId, includeContent,
                "2026-08-03T07:59:00Z", "2026-08-03T08:15:00Z");
    }

    private static String manifestWithTimes(int version, String etag, String localProjectId,
            boolean includeContent, String generatedAt, String expiresAt) throws Exception {
        JSONArray skills = new JSONArray();
        JSONArray knowledge = new JSONArray();
        if (includeContent) {
            skills.put(new JSONObject()
                    .put("skillId", "skill-hvac")
                    .put("versionId", "skill-hvac@1.0.0")
                    .put("version", "1.0.0")
                    .put("name", "HVAC maintenance")
                    .put("description", "Authorized HVAC workflow")
                    .put("contentSha256", repeat('1', 64))
                    .put("knowledgeScopes", new JSONArray().put("hvac-manuals"))
                    .put("authorizationScope", "project"));
            knowledge.put(new JSONObject()
                    .put("knowledgeId", "knowledge-hvac")
                    .put("versionId", "knowledge-hvac@3")
                    .put("version", 3)
                    .put("title", "HVAC service manual")
                    .put("summary", "Authorized manual reference")
                    .put("language", "zh-CN")
                    .put("sensitivity", "internal")
                    .put("sourceType", "document")
                    .put("sourceReference", "kb://hvac/manual-3")
                    .put("contentSha256", repeat('2', 64))
                    .put("knowledgeScopes", new JSONArray().put("hvac-manuals"))
                    .put("validFrom", JSONObject.NULL)
                    .put("expiresAt", JSONObject.NULL)
                    .put("authorizationScope", "skill_version"));
        }
        return new JSONObject()
                .put("manifestVersion", version)
                .put("etag", etag)
                .put("localProjectId", localProjectId)
                .put("projectId", "project-cloud-a")
                .put("generatedAt", generatedAt)
                .put("expiresAt", expiresAt)
                .put("skills", skills)
                .put("knowledge", knowledge)
                .toString();
    }

    private static Map<String, String> header(String key, String value) {
        Map<String, String> values = new HashMap<>();
        values.put(key, value);
        return values;
    }

    private static Map<String, String> headers(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static void assertIOException(String message, ThrowingCall call) throws Exception {
        try {
            call.run();
        } catch (IOException error) {
            assertEquals(message, error.getMessage());
            return;
        }
        throw new AssertionError("expected IOException: " + message);
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class MutableClock implements SkillKnowledgeManifestClient.Clock {
        private long nowMillis = NOW_MILLIS;

        @Override public long nowEpochMillis() { return nowMillis; }
    }

    private static final class MemoryStorage implements SkillKnowledgeManifestClient.Storage {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public byte[] read(String localProjectId) {
            byte[] value = values.get(localProjectId);
            return value == null ? null : Arrays.copyOf(value, value.length);
        }

        @Override public void writeAtomically(String localProjectId, byte[] value) {
            values.put(localProjectId, Arrays.copyOf(value, value.length));
        }

        @Override public void delete(String localProjectId) {
            values.remove(localProjectId);
        }
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body, Map<String, String> headers) {
            responses.add(new Response(status, body, headers));
        }

        @Override public HttpURLConnection open(String endpoint) throws IOException {
            Response response = responses.remove();
            CapturingConnection connection = new CapturingConnection(
                    new URL(endpoint), response.status, response.body, response.headers);
            opened.add(endpoint);
            used.add(connection);
            return connection;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final Map<String, String> headers;

        private Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final Map<String, String> responseHeaders;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        private CapturingConnection(URL url, int status, String body,
                Map<String, String> responseHeaders) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
            this.responseHeaders = responseHeaders;
        }

        @Override public void setRequestMethod(String method) { }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return responseHeaders.get(name); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
