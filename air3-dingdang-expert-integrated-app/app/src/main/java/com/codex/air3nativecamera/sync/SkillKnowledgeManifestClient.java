package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads the server-authoritative Skill and knowledge directory without rule bodies. */
public final class SkillKnowledgeManifestClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Set<String> MANIFEST_FIELDS = setOf(
            "manifestVersion", "etag", "localProjectId", "projectId",
            "generatedAt", "expiresAt", "skills", "knowledge");
    private static final Set<String> SKILL_FIELDS = setOf(
            "skillId", "versionId", "version", "name", "description",
            "contentSha256", "knowledgeScopes", "authorizationScope");
    private static final Set<String> KNOWLEDGE_FIELDS = setOf(
            "knowledgeId", "versionId", "version", "title", "summary", "language",
            "sensitivity", "sourceType", "sourceReference", "contentSha256",
            "knowledgeScopes", "validFrom", "expiresAt", "authorizationScope");
    private static final Set<String> DIRECT_SCOPES = setOf(
            "organization", "project", "profile", "device");
    private static final Set<String> KNOWLEDGE_SCOPES = setOf(
            "organization", "project", "profile", "device", "skill_version");

    public interface Storage {
        byte[] read(String localProjectId) throws IOException;
        void writeAtomically(String localProjectId, byte[] value) throws IOException;
        void delete(String localProjectId) throws IOException;
    }

    public interface Clock {
        long nowEpochMillis();
    }

    public static final class SkillItem {
        private final String skillId;
        private final String versionId;
        private final String version;
        private final String name;
        private final String description;
        private final String contentSha256;
        private final List<String> knowledgeScopes;
        private final String authorizationScope;

        private SkillItem(JSONObject value) throws IOException {
            requireFields(value, SKILL_FIELDS, "content_manifest_skill_invalid");
            skillId = identifier(value.optString("skillId", ""),
                    "content_manifest_skill_invalid");
            versionId = identifier(value.optString("versionId", ""),
                    "content_manifest_skill_invalid");
            version = text(value.optString("version", ""), 40,
                    "content_manifest_skill_invalid");
            if (!version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")) {
                throw new IOException("content_manifest_skill_invalid");
            }
            name = text(value.optString("name", ""), 240,
                    "content_manifest_skill_invalid");
            description = text(value.optString("description", ""), 2_000,
                    "content_manifest_skill_invalid");
            contentSha256 = sha256(value.optString("contentSha256", ""),
                    "content_manifest_skill_invalid");
            knowledgeScopes = stringList(value.optJSONArray("knowledgeScopes"), 100, 200,
                    "content_manifest_skill_invalid");
            authorizationScope = text(value.optString("authorizationScope", ""), 40,
                    "content_manifest_skill_invalid");
            if (!DIRECT_SCOPES.contains(authorizationScope)) {
                throw new IOException("content_manifest_skill_invalid");
            }
        }

        public String skillId() { return skillId; }
        public String versionId() { return versionId; }
        public String version() { return version; }
        public String name() { return name; }
        public String description() { return description; }
        public String contentSha256() { return contentSha256; }
        public List<String> knowledgeScopes() { return knowledgeScopes; }
        public String authorizationScope() { return authorizationScope; }

        private JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("skillId", skillId)
                    .put("versionId", versionId)
                    .put("version", version)
                    .put("name", name)
                    .put("description", description)
                    .put("contentSha256", contentSha256)
                    .put("knowledgeScopes", new JSONArray(knowledgeScopes))
                    .put("authorizationScope", authorizationScope);
        }
    }

    public static final class KnowledgeItem {
        private final String knowledgeId;
        private final String versionId;
        private final int version;
        private final String title;
        private final String summary;
        private final String language;
        private final String sensitivity;
        private final String sourceType;
        private final String sourceReference;
        private final String contentSha256;
        private final List<String> knowledgeScopes;
        private final String validFrom;
        private final String expiresAt;
        private final String authorizationScope;

        private KnowledgeItem(JSONObject value) throws IOException {
            requireFields(value, KNOWLEDGE_FIELDS, "content_manifest_knowledge_invalid");
            knowledgeId = identifier(value.optString("knowledgeId", ""),
                    "content_manifest_knowledge_invalid");
            versionId = identifier(value.optString("versionId", ""),
                    "content_manifest_knowledge_invalid");
            version = positiveInt(value, "version", "content_manifest_knowledge_invalid");
            title = text(value.optString("title", ""), 300,
                    "content_manifest_knowledge_invalid");
            summary = text(value.optString("summary", ""), 4_000,
                    "content_manifest_knowledge_invalid");
            language = text(value.optString("language", ""), 40,
                    "content_manifest_knowledge_invalid");
            sensitivity = text(value.optString("sensitivity", ""), 40,
                    "content_manifest_knowledge_invalid");
            sourceType = text(value.optString("sourceType", ""), 80,
                    "content_manifest_knowledge_invalid");
            sourceReference = text(value.optString("sourceReference", ""), 1_000,
                    "content_manifest_knowledge_invalid");
            contentSha256 = sha256(value.optString("contentSha256", ""),
                    "content_manifest_knowledge_invalid");
            knowledgeScopes = stringList(value.optJSONArray("knowledgeScopes"), 100, 200,
                    "content_manifest_knowledge_invalid");
            validFrom = optionalTimestamp(value, "validFrom",
                    "content_manifest_knowledge_invalid");
            expiresAt = optionalTimestamp(value, "expiresAt",
                    "content_manifest_knowledge_invalid");
            if (!validFrom.isEmpty() && !expiresAt.isEmpty()
                    && epochMillis(expiresAt, "content_manifest_knowledge_invalid")
                    <= epochMillis(validFrom, "content_manifest_knowledge_invalid")) {
                throw new IOException("content_manifest_knowledge_invalid");
            }
            authorizationScope = text(value.optString("authorizationScope", ""), 40,
                    "content_manifest_knowledge_invalid");
            if (!KNOWLEDGE_SCOPES.contains(authorizationScope)) {
                throw new IOException("content_manifest_knowledge_invalid");
            }
        }

        public String knowledgeId() { return knowledgeId; }
        public String versionId() { return versionId; }
        public int version() { return version; }
        public String title() { return title; }
        public String summary() { return summary; }
        public String language() { return language; }
        public String sensitivity() { return sensitivity; }
        public String sourceType() { return sourceType; }
        public String sourceReference() { return sourceReference; }
        public String contentSha256() { return contentSha256; }
        public List<String> knowledgeScopes() { return knowledgeScopes; }
        public String validFrom() { return validFrom; }
        public String expiresAt() { return expiresAt; }
        public String authorizationScope() { return authorizationScope; }

        private JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("knowledgeId", knowledgeId)
                    .put("versionId", versionId)
                    .put("version", version)
                    .put("title", title)
                    .put("summary", summary)
                    .put("language", language)
                    .put("sensitivity", sensitivity)
                    .put("sourceType", sourceType)
                    .put("sourceReference", sourceReference)
                    .put("contentSha256", contentSha256)
                    .put("knowledgeScopes", new JSONArray(knowledgeScopes))
                    .put("validFrom", validFrom.isEmpty() ? JSONObject.NULL : validFrom)
                    .put("expiresAt", expiresAt.isEmpty() ? JSONObject.NULL : expiresAt)
                    .put("authorizationScope", authorizationScope);
        }
    }

    public static final class Manifest {
        private final int manifestVersion;
        private final String etag;
        private final String localProjectId;
        private final String projectId;
        private final String generatedAt;
        private final String expiresAt;
        private final long generatedAtMillis;
        private final long expiresAtMillis;
        private final List<SkillItem> skills;
        private final List<KnowledgeItem> knowledge;
        private final boolean networkConfirmed;

        private Manifest(JSONObject value, String expectedLocalProjectId,
                boolean networkConfirmed) throws IOException {
            requireFields(value, MANIFEST_FIELDS, "content_manifest_invalid");
            manifestVersion = positiveInt(value, "manifestVersion", "content_manifest_invalid");
            etag = sha256(value.optString("etag", ""), "content_manifest_invalid");
            localProjectId = identifier(value.optString("localProjectId", ""),
                    "content_manifest_invalid");
            if (!localProjectId.equals(expectedLocalProjectId)) {
                throw new IOException("content_manifest_project_mismatch");
            }
            projectId = identifier(value.optString("projectId", ""),
                    "content_manifest_invalid");
            generatedAt = text(value.optString("generatedAt", ""), 80,
                    "content_manifest_invalid");
            expiresAt = text(value.optString("expiresAt", ""), 80,
                    "content_manifest_invalid");
            generatedAtMillis = epochMillis(generatedAt, "content_manifest_invalid");
            expiresAtMillis = epochMillis(expiresAt, "content_manifest_invalid");
            if (generatedAtMillis >= expiresAtMillis) {
                throw new IOException("content_manifest_invalid");
            }
            skills = skillItems(value.optJSONArray("skills"));
            knowledge = knowledgeItems(value.optJSONArray("knowledge"));
            this.networkConfirmed = networkConfirmed;
        }

        private Manifest(Manifest source, String expiresAt, boolean networkConfirmed)
                throws IOException {
            this.manifestVersion = source.manifestVersion;
            this.etag = source.etag;
            this.localProjectId = source.localProjectId;
            this.projectId = source.projectId;
            this.generatedAt = source.generatedAt;
            this.expiresAt = expiresAt;
            this.generatedAtMillis = source.generatedAtMillis;
            this.expiresAtMillis = epochMillis(expiresAt, "content_manifest_invalid");
            if (generatedAtMillis >= expiresAtMillis) {
                throw new IOException("content_manifest_invalid");
            }
            this.skills = source.skills;
            this.knowledge = source.knowledge;
            this.networkConfirmed = networkConfirmed;
        }

        public int manifestVersion() { return manifestVersion; }
        public String etag() { return etag; }
        public String localProjectId() { return localProjectId; }
        public String projectId() { return projectId; }
        public String generatedAt() { return generatedAt; }
        public String expiresAt() { return expiresAt; }
        public List<SkillItem> skills() { return skills; }
        public List<KnowledgeItem> knowledge() { return knowledge; }
        public boolean networkConfirmed() { return networkConfirmed; }
        public boolean expiredAt(long epochMillis) { return expiresAtMillis <= epochMillis; }

        private Manifest confirmed(String renewedExpiresAt) throws IOException {
            return new Manifest(this,
                    renewedExpiresAt == null || renewedExpiresAt.trim().isEmpty()
                            ? expiresAt : renewedExpiresAt.trim(),
                    true);
        }

        private JSONObject toJson() throws JSONException {
            JSONArray skillValues = new JSONArray();
            for (SkillItem item : skills) skillValues.put(item.toJson());
            JSONArray knowledgeValues = new JSONArray();
            for (KnowledgeItem item : knowledge) knowledgeValues.put(item.toJson());
            return new JSONObject()
                    .put("manifestVersion", manifestVersion)
                    .put("etag", etag)
                    .put("localProjectId", localProjectId)
                    .put("projectId", projectId)
                    .put("generatedAt", generatedAt)
                    .put("expiresAt", expiresAt)
                    .put("skills", skillValues)
                    .put("knowledge", knowledgeValues);
        }
    }

    private final String manifestEndpoint;
    private final DeviceAccessTokenProvider tokenProvider;
    private final Storage storage;
    private final HttpConnectionFactory connectionFactory;
    private final Clock clock;

    public SkillKnowledgeManifestClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            Storage storage
    ) {
        this(configuration, tokenProvider, storage, HttpConnectionFactory.DEFAULT,
                new Clock() {
                    @Override public long nowEpochMillis() { return System.currentTimeMillis(); }
                });
    }

    SkillKnowledgeManifestClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            Storage storage,
            HttpConnectionFactory connectionFactory,
            Clock clock
    ) {
        if (configuration == null || tokenProvider == null || storage == null
                || connectionFactory == null || clock == null) {
            throw new IllegalArgumentException("content manifest client configuration is invalid");
        }
        String endpoint = configuration.endpoint();
        if (!endpoint.endsWith("/events")) {
            throw new IllegalArgumentException("device sync endpoint is invalid");
        }
        manifestEndpoint = endpoint.substring(0, endpoint.length() - "/events".length())
                + "/content-manifest";
        this.tokenProvider = tokenProvider;
        this.storage = storage;
        this.connectionFactory = connectionFactory;
        this.clock = clock;
    }

    public Manifest loadCached(String localProjectId) throws IOException {
        String projectId = identifier(localProjectId, "content_manifest_project_invalid");
        Manifest cached = readCached(projectId, false);
        if (cached == null) return null;
        if (cached.expiredAt(clock.nowEpochMillis())) {
            throw new IOException("content_manifest_cache_expired");
        }
        return cached;
    }

    public Manifest refresh(String localProjectId) throws IOException {
        String projectId = identifier(localProjectId, "content_manifest_project_invalid");
        Manifest cached = readCached(projectId, true);
        return request(projectId, cached, true);
    }

    private Manifest request(String localProjectId, Manifest cached, boolean allowRetry)
            throws IOException {
        String endpoint = manifestEndpoint + "?localProjectId=" + encode(localProjectId);
        HttpURLConnection connection = connectionFactory.open(endpoint);
        try {
            String accessToken = tokenProvider.accessToken();
            if (accessToken == null || accessToken.trim().isEmpty()) {
                throw new IOException("device_session_missing");
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (cached != null) {
                connection.setRequestProperty("If-None-Match", '"' + cached.etag() + '"');
            }
            int status = connection.getResponseCode();
            if (status == 304) {
                String responseEtag = normalizeEtag(connection.getHeaderField("ETag"));
                if (cached != null
                        && (responseEtag.isEmpty() || !responseEtag.equals(cached.etag()))) {
                    throw new IOException("content_manifest_etag_mismatch");
                }
                String versionHeader = clean(connection.getHeaderField("X-Manifest-Version"));
                String expiresHeader = clean(connection.getHeaderField("X-Manifest-Expires-At"));
                if (cached != null && !versionHeader.isEmpty()) {
                    try {
                        if (Integer.parseInt(versionHeader) != cached.manifestVersion()) {
                            throw new IOException("content_manifest_version_mismatch");
                        }
                    } catch (NumberFormatException exception) {
                        throw new IOException("content_manifest_version_mismatch", exception);
                    }
                }
                Manifest confirmed = cached == null ? null : cached.confirmed(expiresHeader);
                if (confirmed != null && !confirmed.expiredAt(clock.nowEpochMillis())) {
                    persist(confirmed);
                    return confirmed;
                }
                if (allowRetry) return request(localProjectId, null, false);
                throw new IOException("content_manifest_cache_expired");
            }
            String responseBody = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException(errorCode(responseBody));
            }
            JSONObject response;
            try {
                response = new JSONObject(responseBody);
            } catch (JSONException exception) {
                throw new IOException("content_manifest_response_invalid", exception);
            }
            Manifest manifest = new Manifest(response, localProjectId, true);
            String responseEtag = normalizeEtag(connection.getHeaderField("ETag"));
            if (responseEtag.isEmpty() || !responseEtag.equals(manifest.etag())) {
                throw new IOException("content_manifest_etag_mismatch");
            }
            if (manifest.expiredAt(clock.nowEpochMillis())) {
                throw new IOException("content_manifest_expired");
            }
            persist(manifest);
            return manifest;
        } finally {
            connection.disconnect();
        }
    }

    private Manifest readCached(String localProjectId, boolean allowExpired) throws IOException {
        byte[] value = storage.read(localProjectId);
        if (value == null || value.length == 0) return null;
        if (value.length > MAX_RESPONSE_BYTES) {
            throw new IOException("content_manifest_cache_too_large");
        }
        Manifest manifest;
        try {
            manifest = new Manifest(
                    new JSONObject(new String(value, StandardCharsets.UTF_8)),
                    localProjectId,
                    false);
        } catch (JSONException exception) {
            throw new IOException("content_manifest_cache_invalid", exception);
        }
        if (!allowExpired && manifest.expiredAt(clock.nowEpochMillis())) {
            throw new IOException("content_manifest_cache_expired");
        }
        return manifest;
    }

    private void persist(Manifest manifest) throws IOException {
        try {
            storage.writeAtomically(manifest.localProjectId(),
                    manifest.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            throw new IOException("content_manifest_cache_invalid", exception);
        }
    }

    private static List<SkillItem> skillItems(JSONArray values) throws IOException {
        if (values == null || values.length() > 100) {
            throw new IOException("content_manifest_invalid");
        }
        List<SkillItem> result = new ArrayList<>();
        Set<String> versionIds = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IOException("content_manifest_skill_invalid");
            SkillItem item = new SkillItem(value);
            if (!versionIds.add(item.versionId())) {
                throw new IOException("content_manifest_skill_duplicate");
            }
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<KnowledgeItem> knowledgeItems(JSONArray values) throws IOException {
        if (values == null || values.length() > 1_000) {
            throw new IOException("content_manifest_invalid");
        }
        List<KnowledgeItem> result = new ArrayList<>();
        Set<String> versionIds = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IOException("content_manifest_knowledge_invalid");
            KnowledgeItem item = new KnowledgeItem(value);
            if (!versionIds.add(item.versionId())) {
                throw new IOException("content_manifest_knowledge_duplicate");
            }
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static void requireFields(JSONObject value, Set<String> expected, String error)
            throws IOException {
        if (value == null) throw new IOException(error);
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(expected)) throw new IOException(error);
    }

    private static int positiveInt(JSONObject value, String key, String error) throws IOException {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IOException(error);
        Number number = (Number) raw;
        long parsed = number.longValue();
        if (parsed <= 0 || parsed > Integer.MAX_VALUE
                || number.doubleValue() != (double) parsed) throw new IOException(error);
        return (int) parsed;
    }

    private static List<String> stringList(JSONArray values, int maximumItems,
            int maximumLength, String error) throws IOException {
        if (values == null || values.length() > maximumItems) throw new IOException(error);
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            Object raw = values.opt(index);
            if (!(raw instanceof String)) throw new IOException(error);
            String item = text((String) raw, maximumLength, error);
            if (!unique.add(item)) throw new IOException(error);
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static String optionalTimestamp(JSONObject value, String key, String error)
            throws IOException {
        if (value.isNull(key)) return "";
        String result = text(value.optString(key, ""), 80, error);
        epochMillis(result, error);
        return result;
    }

    private static long epochMillis(String value, String error) throws IOException {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new IOException(error, exception);
        }
    }

    private static String identifier(String value, String error) throws IOException {
        String result = clean(value);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$")) {
            throw new IOException(error);
        }
        return result;
    }

    private static String text(String value, int maximum, String error) throws IOException {
        String result = clean(value);
        if (result.isEmpty() || result.length() > maximum) throw new IOException(error);
        for (int index = 0; index < result.length(); index++) {
            if (result.charAt(index) < 32) throw new IOException(error);
        }
        return result;
    }

    private static String sha256(String value, String error) throws IOException {
        String result = clean(value).toLowerCase(Locale.ROOT);
        if (!result.matches("^[0-9a-f]{64}$")) throw new IOException(error);
        return result;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String normalizeEtag(String value) {
        String result = clean(value);
        if (result.startsWith("W/")) result = result.substring(2).trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        return result.matches("^[0-9a-f]{64}$") ? result : "";
    }

    private static String errorCode(String body) {
        try {
            String error = new JSONObject(body).optString("error", "request_failed").trim();
            return error.matches("^[a-z0-9_]{1,120}$") ? error : "request_failed";
        } catch (JSONException ignored) {
            return "request_failed";
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("content_manifest_response_too_large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
