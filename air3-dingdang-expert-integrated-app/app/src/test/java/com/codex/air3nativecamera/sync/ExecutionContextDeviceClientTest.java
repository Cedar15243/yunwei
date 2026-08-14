package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.features.operations.ExecutionContextHudPresenter;
import com.codex.air3nativecamera.features.operations.OperationDetail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Queue;

public final class ExecutionContextDeviceClientTest {
    @Test
    public void acceptsMinimalServerSkillCatalogWithoutRuleBodies() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"versionId\":\"skill-hvac@1.0.0\"," +
                "\"skillId\":\"skill-hvac\",\"version\":\"1.0.0\"," +
                "\"name\":\"HVAC maintenance\",\"sha256\":\"" + repeat('a', 64) +
                "\",\"activeForTask\":false}]}");

        ExecutionContextDeviceClient.SkillCatalog catalog =
                client(connections).listSkills("project-a", "task-a");

        assertEquals(1, catalog.items().size());
        assertEquals("HVAC maintenance", catalog.items().get(0).name());
        assertFalse(catalog.items().get(0).activeForTask());
    }

    @Test
    public void readsAuthorizedSkillsAndProjectsWithTheShortDeviceSession() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"versionId\":\"skill-hvac@1.0.0\"," +
                "\"skillId\":\"skill-hvac\",\"version\":\"1.0.0\"," +
                "\"name\":\"暖通维修\",\"rules\":{},\"sha256\":\"" + repeat('a', 64) +
                "\",\"activeForTask\":true}]}");
        connections.enqueue(200, "{\"items\":[{\"projectId\":\"server-project-a\"," +
                "\"localProjectId\":\"project-a\",\"title\":\"冷站维护\"," +
                "\"status\":\"active\",\"taskCount\":3,\"activeTaskCount\":1," +
                "\"memoryRevision\":3}]}");
        ExecutionContextDeviceClient client = client(connections);

        ExecutionContextDeviceClient.SkillCatalog skills =
                client.listSkills("project-a", "task-a");
        ExecutionContextDeviceClient.ProjectCatalog projects = client.listProjects();

        assertEquals(1, skills.items().size());
        assertEquals("skill-hvac@1.0.0", skills.items().get(0).versionId());
        assertTrue(skills.items().get(0).activeForTask());
        assertEquals(1, projects.items().size());
        assertEquals(3, projects.items().get(0).memoryRevision());
        assertTrue(connections.opened.get(0).endsWith(
                "/device-sync/skills?localProjectId=project-a&localTaskId=task-a"));
        assertTrue(connections.opened.get(1).endsWith("/device-sync/projects"));
        assertEquals("Bearer access-a",
                connections.used.get(0).getRequestProperty("Authorization"));
        assertEquals("no-store",
                connections.used.get(0).getRequestProperty("Cache-Control"));
        assertFalse(connections.used.get(0).getRequestProperty("Authorization")
                .contains("bootstrap-a"));
    }

    @Test
    public void writesOnlyWhitelistedGovernedCommandsWithFixedConfirmations() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"active\":true,\"duplicate\":false," +
                "\"skill\":{\"versionId\":\"skill-hvac@1.0.0\"}}");
        connections.enqueue(200, "{\"taskStatus\":\"completed\"," +
                "\"memoryRevision\":4,\"duplicate\":false}");
        connections.enqueue(200, "{\"instructionId\":\"instruction-a\"," +
                "\"version\":1,\"status\":\"active\",\"duplicate\":false}");
        ExecutionContextDeviceClient client = client(connections);

        client.activateSkill("project-a", "task-a", "skill-hvac@1.0.0", "skill-command-a");
        client.endTask("project-a", "task-a", "completed",
                "现场问题：控制器报警\n处理结果：人工确认恢复正常", 3,
                Arrays.asList("报警已清除"), Arrays.asList("不是断电"),
                Arrays.<String>asList(), "end-task-a");
        client.confirmProjectInstruction("project-a", "instruction-a", 0, "active",
                new JSONObject().put("containsAny", new JSONArray().put("报警")),
                "以后先读取报警代码", Arrays.asList("完全断电时先检查供电"),
                "trace-a", "instruction-command-a");

        JSONObject activate = connections.used.get(0).requestJson();
        JSONObject end = connections.used.get(1).requestJson();
        JSONObject instruction = connections.used.get(2).requestJson();
        assertEquals("ACTIVATE_SKILL", activate.getString("confirmation"));
        assertEquals("skill-command-a", activate.getString("idempotencyKey"));
        assertEquals("END_TASK", end.getString("confirmation"));
        assertEquals(3, end.getInt("expectedMemoryRevision"));
        assertEquals("现场问题：控制器报警\n处理结果：人工确认恢复正常",
                end.getString("summary"));
        assertEquals("CONFIRM_PROJECT_INSTRUCTION",
                instruction.getString("confirmation"));
        assertTrue(connections.opened.get(0).endsWith("/device-sync/tasks/task-a/skill"));
        assertTrue(connections.opened.get(1).endsWith("/device-sync/tasks/task-a/end-summary"));
        assertTrue(connections.opened.get(2).endsWith(
                "/device-sync/projects/project-a/instructions"));
    }

    @Test
    public void serverAuthorizationFailureIsExplicitAndNeverReturnsAFakeSkillState() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(403, "{\"ok\":false,\"error\":\"skill_not_authorized\"}");

        try {
            client(connections).activateSkill(
                    "project-a", "task-a", "skill-hvac@1.0.0", "skill-command-a");
        } catch (IOException error) {
            assertEquals("skill_not_authorized", error.getMessage());
            return;
        }
        throw new AssertionError("authorization failure must throw");
    }

    @Test
    public void rejectsHiddenControlCharactersInTaskEndSummary() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();

        try {
            client(connections).endTask("project-a", "task-a", "completed",
                    "现场问题：控制器报警\u0001处理结果：已恢复", 3,
                    Arrays.asList("报警已清除"), Arrays.asList("不是断电"),
                    Collections.<String>emptyList(), "end-task-control");
        } catch (IllegalArgumentException error) {
            assertTrue(connections.opened.isEmpty());
            return;
        }
        throw new AssertionError("hidden control character must be rejected before HTTP");
    }

    @Test
    public void managedHudUsesOnlyServerProjectsSkillsAndLocallyRecoverableOpenTasks()
            throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"projectId\":\"server-project-a\"," +
                "\"localProjectId\":\"project-a\",\"title\":\"冷站维护\"," +
                "\"status\":\"active\",\"taskCount\":2,\"activeTaskCount\":1," +
                "\"memoryRevision\":3}]}");
        connections.enqueue(200, "{\"projectId\":\"server-project-a\"," +
                "\"localProjectId\":\"project-a\",\"title\":\"冷站维护\"," +
                "\"status\":\"active\",\"projectMemory\":{" +
                "\"summary\":\"控制器已恢复供电，仍需观察报警。\"," +
                "\"confirmedFacts\":[\"电源指示灯常亮\"]," +
                "\"excludedFacts\":[\"不是完全断电\"]," +
                "\"risks\":[\"带电操作风险\"],\"revision\":3}," +
                "\"tasks\":[{" +
                "\"taskId\":\"server-task-a\",\"localTaskId\":\"task-a\"," +
                "\"title\":\"控制器报警\",\"status\":\"active\"," +
                "\"activeSkillVersionId\":\"skill-hvac@1.0.0\",\"endSummary\":\"\"},{" +
                "\"taskId\":\"server-task-b\",\"localTaskId\":\"task-b\"," +
                "\"title\":\"历史故障\",\"status\":\"completed\"," +
                "\"activeSkillVersionId\":\"\",\"endSummary\":\"人工确认恢复。\"},{" +
                "\"taskId\":\"server-task-c\",\"localTaskId\":\"task-c\"," +
                "\"title\":\"状态冲突任务\",\"status\":\"paused\"," +
                "\"activeSkillVersionId\":\"\",\"endSummary\":\"\"}]," +
                "\"projectInstructions\":[{" +
                "\"instructionId\":\"instruction-a\",\"version\":1," +
                "\"status\":\"active\",\"condition\":{}," +
                "\"action\":\"以后先记录报警代码。\",\"exceptions\":[]," +
                "\"sourceTraceId\":\"trace-a\"},{" +
                "\"instructionId\":\"instruction-b\",\"version\":3," +
                "\"status\":\"disabled\",\"condition\":{\"containsAny\":[\"供电异常\"]}," +
                "\"action\":\"以后遇到供电异常先检查保险。\",\"exceptions\":[]," +
                "\"sourceTraceId\":\"trace-b\"},{" +
                "\"instructionId\":\"instruction-c\",\"version\":5," +
                "\"status\":\"deleted\",\"condition\":{}," +
                "\"action\":\"旧规则，仅保留审计。\",\"exceptions\":[]," +
                "\"sourceTraceId\":\"trace-c\"}]}");
        connections.enqueue(200, "{\"items\":[{" +
                "\"versionId\":\"skill-hvac@1.0.0\",\"skillId\":\"skill-hvac\"," +
                "\"version\":\"1.0.0\",\"name\":\"暖通维修\"," +
                "\"rules\":{},\"sha256\":\"" + repeat('b', 64) + "\"," +
                "\"activeForTask\":true}]}");
        ExecutionContextDeviceClient client = client(connections);
        ExecutionContextHudPresenter presenter = new ExecutionContextHudPresenter();

        OperationDetail projects = presenter.projectCatalog(client.listProjects());
        ExecutionContextDeviceClient.ProjectDetail projectDetail =
                client.getProject("project-a");
        OperationDetail project = presenter.projectDetail(
                projectDetail, new java.util.LinkedHashSet<String>(
                        Arrays.asList("task-a", "task-c")));
        ExecutionContextDeviceClient.SkillCatalog skillCatalog =
                client.listSkills("project-a", "task-a");
        OperationDetail skills = presenter.skillCatalog(skillCatalog);

        assertEquals("项目记忆", projects.title());
        assertTrue(projects.description().contains("服务端"));
        assertEquals("managed_project_open:project-a", projects.itemActions().get(0));
        assertTrue(project.items().get(0).contains("控制器已恢复供电"));
        assertTrue(project.items().toString().contains("以后先记录报警代码"));
        assertTrue(project.itemActions().contains(
                "managed_project_instruction_open:project-a:instruction-a"));
        assertTrue(project.itemActions().contains(
                "managed_project_instruction_open:project-a:instruction-b"));
        assertTrue(project.itemActions().contains(
                "managed_project_instruction_open:project-a:instruction-c"));
        assertTrue(project.itemActions().contains(
                "managed_project_resume:project-a:task-a"));
        assertFalse(project.itemActions().contains(
                "managed_project_resume:project-a:task-b"));
        assertFalse(project.itemActions().contains(
                "managed_project_resume:project-a:task-c"));
        assertTrue(project.items().toString().contains("状态不允许恢复"));
        assertEquals("managed_project_new_task:project-a", project.primaryAction());
        assertEquals("AI运维技能", skills.title());
        assertTrue(skills.items().get(0).contains("服务端已启用"));
        assertFalse(skills.items().get(0).contains("本机技能"));
        assertEquals("managed_skill_deactivate:skill-hvac@1.0.0",
                skills.itemActions().get(0));
        assertEquals("managed_skill_deactivate:skill-hvac@1.0.0",
                presenter.skillActionFromVoice("停用暖通维修技能", true,
                        skillCatalog));
        assertEquals("", presenter.skillActionFromVoice(
                "停用暖通维修技能", false, skillCatalog));

        java.lang.reflect.Method instructionDetailMethod;
        try {
            instructionDetailMethod = ExecutionContextHudPresenter.class.getMethod(
                    "projectInstructionDetail",
                    ExecutionContextDeviceClient.ProjectDetail.class,
                    String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("project instruction governance detail is missing", missing);
        }
        OperationDetail activeInstruction = (OperationDetail) instructionDetailMethod.invoke(
                presenter, projectDetail, "instruction-a");
        OperationDetail disabledInstruction = (OperationDetail) instructionDetailMethod.invoke(
                presenter, projectDetail, "instruction-b");
        OperationDetail deletedInstruction = (OperationDetail) instructionDetailMethod.invoke(
                presenter, projectDetail, "instruction-c");
        assertTrue(activeInstruction.itemActions().contains(
                "managed_project_instruction_edit:project-a:instruction-a"));
        assertTrue(activeInstruction.itemActions().contains(
                "managed_project_instruction_disable:project-a:instruction-a"));
        assertTrue(activeInstruction.itemActions().contains(
                "managed_project_instruction_delete:project-a:instruction-a"));
        assertTrue(disabledInstruction.itemActions().contains(
                "managed_project_instruction_edit:project-a:instruction-b"));
        assertTrue(disabledInstruction.itemActions().contains(
                "managed_project_instruction_enable:project-a:instruction-b"));
        assertTrue(disabledInstruction.itemActions().contains(
                "managed_project_instruction_delete:project-a:instruction-b"));
        assertFalse(deletedInstruction.itemActions().toString().contains(
                "managed_project_instruction_"));
        assertEquals("managed_project_open:project-a", deletedInstruction.primaryAction());
    }

    @Test
    public void rejectsMalformedProjectInstructionSnapshotsBeforeRendering() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"projectId\":\"server-project-a\"," +
                "\"localProjectId\":\"project-a\",\"title\":\"冷站维护\"," +
                "\"status\":\"active\",\"projectMemory\":{" +
                "\"summary\":\"\",\"confirmedFacts\":[],\"excludedFacts\":[]," +
                "\"risks\":[],\"revision\":0},\"tasks\":[]," +
                "\"projectInstructions\":[{" +
                "\"instructionId\":\"instruction-a\",\"version\":1," +
                "\"status\":\"enabled\",\"condition\":{}," +
                "\"action\":\"无效状态\",\"exceptions\":[]," +
                "\"sourceTraceId\":\"trace-a\"}]}");

        try {
            client(connections).getProject("project-a");
        } catch (IOException rejected) {
            assertEquals("project_detail_response_invalid", rejected.getMessage());
            return;
        }
        throw new AssertionError("malformed instruction snapshot must be rejected");
    }

    @Test
    public void managedHudFailureIsRecoverableAndNeverPretendsTheSkillIsEnabled() {
        ExecutionContextHudPresenter presenter = new ExecutionContextHudPresenter();

        OperationDetail loading = presenter.loading("project_memory");
        OperationDetail failure = presenter.failure(
                "agent_center", "skill_not_authorized");

        assertEquals("项目记忆", loading.title());
        assertTrue(loading.items().get(0).contains("正在从服务端读取"));
        assertEquals("AI运维技能", failure.title());
        assertTrue(failure.description().contains("服务请求失败"));
        assertTrue(failure.items().get(0).contains("未授权"));
        assertFalse(failure.items().get(0).contains("已启用"));
        assertEquals("managed_skill_refresh", failure.primaryAction());
    }

    private ExecutionContextDeviceClient client(QueueConnectionFactory connections) {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        return new ExecutionContextDeviceClient(
                configuration,
                new DeviceAccessTokenProvider() {
                    @Override public String accessToken() { return "access-a"; }
                },
                connections);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        @Override public HttpURLConnection open(String endpoint) throws IOException {
            Response response = responses.remove();
            CapturingConnection connection = new CapturingConnection(
                    new URL(endpoint), response.status, response.body);
            opened.add(endpoint);
            used.add(connection);
            return connection;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        private CapturingConnection(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void setRequestMethod(String method) { }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }

        private JSONObject requestJson() throws Exception {
            return new JSONObject(new String(request.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
