package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Short-session client for the V9 Skill, project memory and task governance whitelist. */
public final class ExecutionContextDeviceClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    public static final class Skill {
        private final String versionId;
        private final String skillId;
        private final String version;
        private final String name;
        private final String sha256;
        private final boolean activeForTask;

        private Skill(JSONObject value) throws IOException {
            versionId = requiredIdentifier(value.optString("versionId", ""));
            skillId = requiredIdentifier(value.optString("skillId", ""));
            version = requiredText(value.optString("version", ""), 80);
            name = requiredText(value.optString("name", ""), 240);
            sha256 = requiredSha256(value.optString("sha256", ""));
            activeForTask = value.optBoolean("activeForTask", false);
        }

        public String versionId() { return versionId; }
        public String skillId() { return skillId; }
        public String version() { return version; }
        public String name() { return name; }
        public String sha256() { return sha256; }
        public boolean activeForTask() { return activeForTask; }
    }

    public static final class SkillCatalog {
        private final List<Skill> items;

        private SkillCatalog(List<Skill> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public List<Skill> items() { return items; }
    }

    public static final class ProjectSummary {
        private final String projectId;
        private final String localProjectId;
        private final String title;
        private final String status;
        private final int taskCount;
        private final int activeTaskCount;
        private final int memoryRevision;

        private ProjectSummary(JSONObject value) throws IOException {
            projectId = requiredIdentifier(value.optString("projectId", ""));
            localProjectId = requiredIdentifier(value.optString("localProjectId", ""));
            title = requiredText(value.optString("title", ""), 240);
            status = requiredText(value.optString("status", ""), 80);
            taskCount = nonNegativeInt(value, "taskCount");
            activeTaskCount = nonNegativeInt(value, "activeTaskCount");
            memoryRevision = nonNegativeInt(value, "memoryRevision");
            if (activeTaskCount > taskCount) throw new IOException("project_response_invalid");
        }

        public String projectId() { return projectId; }
        public String localProjectId() { return localProjectId; }
        public String title() { return title; }
        public String status() { return status; }
        public int taskCount() { return taskCount; }
        public int activeTaskCount() { return activeTaskCount; }
        public int memoryRevision() { return memoryRevision; }
    }

    public static final class ProjectCatalog {
        private final List<ProjectSummary> items;

        private ProjectCatalog(List<ProjectSummary> items) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public List<ProjectSummary> items() { return items; }
    }

    public static final class ProjectInstruction {
        private final String instructionId;
        private final int version;
        private final String status;
        private final String conditionJson;
        private final String action;
        private final List<String> exceptions;
        private final String sourceTraceId;

        private ProjectInstruction(JSONObject value) throws IOException {
            try {
                instructionId = requiredIdentifier(value.optString("instructionId", ""));
                version = positiveInt(value, "version");
                status = requiredText(value.optString("status", ""), 80);
                if (!("active".equals(status) || "disabled".equals(status)
                        || "deleted".equals(status))) {
                    throw new IllegalArgumentException("instruction status is invalid");
                }
                JSONObject condition = value.optJSONObject("condition");
                validateProjectInstructionCondition(condition);
                conditionJson = condition.toString();
                action = requiredText(value.optString("action", ""), 4000);
                exceptions = Collections.unmodifiableList(parseStringList(
                        value.optJSONArray("exceptions"), 50, 1000));
                sourceTraceId = requiredIdentifier(value.optString("sourceTraceId", ""));
            } catch (IllegalArgumentException exception) {
                throw new IOException("project_detail_response_invalid", exception);
            }
        }

        public String instructionId() { return instructionId; }
        public int version() { return version; }
        public String status() { return status; }
        public String action() { return action; }
        public List<String> exceptions() { return exceptions; }
        public String sourceTraceId() { return sourceTraceId; }

        public JSONObject condition() {
            try {
                return new JSONObject(conditionJson);
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    public static final class ProjectDetail {
        private final String projectId;
        private final String localProjectId;
        private final String title;
        private final String status;
        private final List<ProjectInstruction> instructions;
        private final String payloadJson;

        private ProjectDetail(JSONObject value) throws IOException {
            projectId = requiredIdentifier(value.optString("projectId", ""));
            localProjectId = requiredIdentifier(value.optString("localProjectId", ""));
            title = requiredText(value.optString("title", ""), 240);
            status = requiredText(value.optString("status", ""), 80);
            JSONArray instructionValues = value.optJSONArray("projectInstructions");
            if (value.optJSONObject("projectMemory") == null
                    || value.optJSONArray("tasks") == null
                    || instructionValues == null || instructionValues.length() > 500) {
                throw new IOException("project_detail_response_invalid");
            }
            List<ProjectInstruction> accepted = new ArrayList<>();
            for (int index = 0; index < instructionValues.length(); index++) {
                JSONObject instruction = instructionValues.optJSONObject(index);
                if (instruction == null) throw new IOException("project_detail_response_invalid");
                accepted.add(new ProjectInstruction(instruction));
            }
            instructions = Collections.unmodifiableList(accepted);
            payloadJson = value.toString();
        }

        public String projectId() { return projectId; }
        public String localProjectId() { return localProjectId; }
        public String title() { return title; }
        public String status() { return status; }
        public List<ProjectInstruction> instructions() { return instructions; }

        public ProjectInstruction instruction(String instructionId) {
            String expected = instructionId == null ? "" : instructionId.trim();
            for (ProjectInstruction instruction : instructions) {
                if (instruction.instructionId().equals(expected)) return instruction;
            }
            return null;
        }

        public JSONObject toJson() {
            try {
                return new JSONObject(payloadJson);
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    public static final class CommandResult {
        private final boolean duplicate;
        private final String payloadJson;

        private CommandResult(JSONObject value) {
            duplicate = value.optBoolean("duplicate", false);
            payloadJson = value.toString();
        }

        public boolean duplicate() { return duplicate; }

        public JSONObject toJson() {
            try {
                return new JSONObject(payloadJson);
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private final String controlEndpoint;
    private final DeviceAccessTokenProvider tokenProvider;
    private final HttpConnectionFactory connectionFactory;

    public ExecutionContextDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider
    ) {
        this(configuration, tokenProvider, HttpConnectionFactory.DEFAULT);
    }

    ExecutionContextDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            HttpConnectionFactory connectionFactory
    ) {
        if (configuration == null || tokenProvider == null || connectionFactory == null) {
            throw new IllegalArgumentException("execution context client configuration is invalid");
        }
        String endpoint = configuration.endpoint();
        if (!endpoint.endsWith("/events")) {
            throw new IllegalArgumentException("device sync endpoint is invalid");
        }
        controlEndpoint = endpoint.substring(0, endpoint.length() - "/events".length());
        this.tokenProvider = tokenProvider;
        this.connectionFactory = connectionFactory;
    }

    public SkillCatalog listSkills(String localProjectId, String localTaskId) throws IOException {
        String projectId = requiredIdentifier(localProjectId);
        String taskId = requiredIdentifier(localTaskId);
        String query = "?localProjectId=" + encode(projectId)
                + "&localTaskId=" + encode(taskId);
        JSONObject response = request("GET", "/skills" + query, null);
        JSONArray values = response.optJSONArray("items");
        if (values == null || values.length() > 200) throw new IOException("skill_response_invalid");
        List<Skill> items = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IOException("skill_response_invalid");
            items.add(new Skill(value));
        }
        return new SkillCatalog(items);
    }

    public ProjectCatalog listProjects() throws IOException {
        JSONObject response = request("GET", "/projects", null);
        JSONArray values = response.optJSONArray("items");
        if (values == null || values.length() > 500) {
            throw new IOException("project_response_invalid");
        }
        List<ProjectSummary> items = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IOException("project_response_invalid");
            items.add(new ProjectSummary(value));
        }
        return new ProjectCatalog(items);
    }

    public ProjectDetail getProject(String localProjectId) throws IOException {
        return new ProjectDetail(request(
                "GET", "/projects/" + requiredIdentifier(localProjectId), null));
    }

    public CommandResult activateSkill(
            String localProjectId,
            String localTaskId,
            String skillVersionId,
            String idempotencyKey
    ) throws IOException {
        return setTaskSkill(localProjectId, localTaskId, "activate",
                requiredIdentifier(skillVersionId), "ACTIVATE_SKILL", idempotencyKey);
    }

    public CommandResult deactivateSkill(
            String localProjectId,
            String localTaskId,
            String idempotencyKey
    ) throws IOException {
        return setTaskSkill(localProjectId, localTaskId, "deactivate", "",
                "DEACTIVATE_SKILL", idempotencyKey);
    }

    private CommandResult setTaskSkill(
            String localProjectId,
            String localTaskId,
            String action,
            String skillVersionId,
            String confirmation,
            String idempotencyKey
    ) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("action", action)
                    .put("localProjectId", requiredIdentifier(localProjectId))
                    .put("confirmation", confirmation)
                    .put("idempotencyKey", requiredIdentifier(idempotencyKey));
            if (!skillVersionId.isEmpty()) body.put("skillVersionId", skillVersionId);
            return new CommandResult(request("POST",
                    "/tasks/" + requiredIdentifier(localTaskId) + "/skill", body));
        } catch (JSONException exception) {
            throw new IOException("skill_command_invalid", exception);
        }
    }

    public CommandResult endTask(
            String localProjectId,
            String localTaskId,
            String taskStatus,
            String summary,
            int expectedMemoryRevision,
            List<String> confirmedFacts,
            List<String> excludedFacts,
            List<String> risks,
            String idempotencyKey
    ) throws IOException {
        if (!("completed".equals(taskStatus) || "closed".equals(taskStatus))
                || expectedMemoryRevision < 0) {
            throw new IllegalArgumentException("task summary is invalid");
        }
        try {
            JSONObject body = new JSONObject()
                    .put("localProjectId", requiredIdentifier(localProjectId))
                    .put("taskStatus", taskStatus)
                    .put("summary", requiredDisplayText(summary, 8000))
                    .put("confirmedFacts", stringArray(confirmedFacts, 100, 1000))
                    .put("excludedFacts", stringArray(excludedFacts, 100, 1000))
                    .put("risks", stringArray(risks, 100, 1000))
                    .put("expectedMemoryRevision", expectedMemoryRevision)
                    .put("confirmation", "END_TASK")
                    .put("idempotencyKey", requiredIdentifier(idempotencyKey));
            return new CommandResult(request("POST",
                    "/tasks/" + requiredIdentifier(localTaskId) + "/end-summary", body));
        } catch (JSONException exception) {
            throw new IOException("task_summary_invalid", exception);
        }
    }

    public CommandResult confirmProjectInstruction(
            String localProjectId,
            String instructionId,
            int expectedVersion,
            String status,
            JSONObject condition,
            String action,
            List<String> exceptions,
            String sourceTraceId,
            String idempotencyKey
    ) throws IOException {
        if (expectedVersion < 0 || !("active".equals(status)
                || "disabled".equals(status) || "deleted".equals(status))
                || condition == null) {
            throw new IllegalArgumentException("project instruction is invalid");
        }
        try {
            JSONObject body = new JSONObject()
                    .put("instructionId", requiredIdentifier(instructionId))
                    .put("expectedVersion", expectedVersion)
                    .put("status", status)
                    .put("condition", new JSONObject(condition.toString()))
                    .put("action", requiredText(action, 4000))
                    .put("exceptions", stringArray(exceptions, 50, 1000))
                    .put("sourceTraceId", requiredIdentifier(sourceTraceId))
                    .put("confirmation", "CONFIRM_PROJECT_INSTRUCTION")
                    .put("idempotencyKey", requiredIdentifier(idempotencyKey));
            return new CommandResult(request("POST",
                    "/projects/" + requiredIdentifier(localProjectId) + "/instructions", body));
        } catch (JSONException exception) {
            throw new IOException("project_instruction_invalid", exception);
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection connection = connectionFactory.open(controlEndpoint + path);
        OutputStream output = null;
        try {
            String accessToken = tokenProvider.accessToken();
            if (accessToken == null || accessToken.trim().isEmpty()) {
                throw new IOException("device_session_missing");
            }
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.close();
                output = null;
            }
            int status = connection.getResponseCode();
            String responseBody = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            JSONObject response;
            try {
                response = new JSONObject(responseBody);
            } catch (JSONException exception) {
                throw new IOException("execution_context_response_invalid", exception);
            }
            if (status < 200 || status >= 300) {
                throw new IOException(safeErrorCode(response.optString("error", "request_failed")));
            }
            return response;
        } finally {
            if (output != null) {
                try { output.close(); } catch (IOException ignored) { }
            }
            connection.disconnect();
        }
    }

    private static JSONArray stringArray(List<String> values, int maximumItems, int maximumChars) {
        if (values == null || values.size() > maximumItems) {
            throw new IllegalArgumentException("text list is invalid");
        }
        JSONArray result = new JSONArray();
        for (String value : values) result.put(requiredText(value, maximumChars));
        return result;
    }

    private static int nonNegativeInt(JSONObject value, String key) throws IOException {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IOException("project_response_invalid");
        Number number = (Number) raw;
        long parsed = number.longValue();
        if (number.doubleValue() != (double) parsed || parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw new IOException("project_response_invalid");
        }
        return (int) parsed;
    }

    private static int positiveInt(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("integer is invalid");
        Number number = (Number) raw;
        long parsed = number.longValue();
        if (number.doubleValue() != (double) parsed || parsed < 1 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer is invalid");
        }
        return (int) parsed;
    }

    private static List<String> parseStringList(
            JSONArray values, int maximumItems, int maximumChars
    ) {
        if (values == null || values.length() > maximumItems) {
            throw new IllegalArgumentException("text list is invalid");
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(requiredText(values.optString(index, ""), maximumChars));
        }
        return result;
    }

    private static void validateProjectInstructionCondition(JSONObject condition) {
        if (condition == null) throw new IllegalArgumentException("condition is invalid");
        Iterator<String> keys = condition.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("containsAny".equals(key) || "systems".equals(key)
                    || "assetIds".equals(key))) {
                throw new IllegalArgumentException("condition is invalid");
            }
            parseStringList(condition.optJSONArray(key), 50, 200);
        }
    }

    private static String requiredIdentifier(String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$")) {
            throw new IllegalArgumentException("identifier is invalid");
        }
        return clean;
    }

    private static String requiredText(String value, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum) {
            throw new IllegalArgumentException("text is invalid");
        }
        for (int index = 0; index < clean.length(); index++) {
            if (clean.charAt(index) < 32) throw new IllegalArgumentException("text is invalid");
        }
        return clean;
    }

    private static String requiredDisplayText(String value, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum) {
            throw new IllegalArgumentException("text is invalid");
        }
        for (int index = 0; index < clean.length(); index++) {
            char character = clean.charAt(index);
            if (Character.getType(character) == Character.CONTROL
                    && character != '\t' && character != '\n' && character != '\r') {
                throw new IllegalArgumentException("text is invalid");
            }
        }
        return clean;
    }

    private static String requiredSha256(String value) throws IOException {
        String clean = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!clean.matches("^[0-9a-f]{64}$")) throw new IOException("skill_response_invalid");
        return clean;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String safeErrorCode(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("^[a-z0-9_]{1,120}$") ? clean : "request_failed";
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("execution_context_response_too_large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
