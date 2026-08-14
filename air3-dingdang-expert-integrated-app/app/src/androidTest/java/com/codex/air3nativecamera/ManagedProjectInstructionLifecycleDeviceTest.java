package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.graphics.Bitmap;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.governance.ProjectGovernanceDraft;
import com.codex.air3nativecamera.runtime.ManagedRuntimeConfiguration;
import com.codex.air3nativecamera.sync.AiExecutionContext;
import com.codex.air3nativecamera.sync.BackendDiagnosisRequest;
import com.codex.air3nativecamera.sync.DeviceSessionManager;
import com.codex.air3nativecamera.sync.ExecutionContextDeviceClient;
import com.codex.air3nativecamera.sync.TaskSyncReporter;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Air3 audit for the complete governed project-instruction lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class ManagedProjectInstructionLifecycleDeviceTest {
    private static final long SERVER_TIMEOUT_MS = 45_000L;
    private static final String MARKER = "生命周期9028已应用";

    @Test
    public void realGatewayPreservesLifecycleVersionsAndRefreshesStaleConflicts()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        AtomicReference<TaskSession> created = new AtomicReference<TaskSession>();
        try {
            runOnMain(instrumentation, () -> {
                invoke(activity, "cancelForegroundVoiceListening");
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                invoke(activity, "appendUserTranscriptMessage",
                        new Class<?>[] { String.class },
                        new Object[] { "V9 项目指令生命周期实机验收" });
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class },
                        new Object[] { "控制器报警并伴随供电异常" });
                created.set(manager(activity).active());
                TaskSyncReporter reporter = (TaskSyncReporter) requiredField(
                        activity, "taskSyncReporter");
                assertNotNull(reporter);
                reporter.flush();
            });

            TaskSession session = created.get();
            assertNotNull(session);
            ExecutionContextDeviceClient client = (ExecutionContextDeviceClient) field(
                    activity, "executionContextDeviceClient");
            assertNotNull(client);
            waitForServerTask(client, session.projectId(), session.id());

            String initialRule = "以后在这个项目遇到控制器报警先记录故障码";
            runOnMain(instrumentation, () -> invoke(activity,
                    "requestManagedProjectInstruction",
                    new Class<?>[] { String.class }, new Object[] { initialRule }));
            ProjectGovernanceDraft createdDraft = pendingDraft(activity);
            confirmPending(instrumentation, activity);
            JSONObject v1 = waitForInstructionVersion(
                    client, session.projectId(), createdDraft.instructionId(), 1);
            assertInstruction(v1, 1, "active", initialRule);

            waitForActivityProjectVersion(activity, session.projectId(),
                    createdDraft.instructionId(), 1);
            perform(instrumentation, activity, "managed_project_instruction_open:"
                    + session.projectId() + ":" + createdDraft.instructionId());
            OperationDetail opened = (OperationDetail) field(activity, "hudOperationDetail");
            assertTrue(opened.itemActions().toString()
                    .contains("managed_project_instruction_disable:"));
            captureScreen(instrumentation, activity, "01-v1-open");

            perform(instrumentation, activity, "managed_project_instruction_edit:"
                    + session.projectId() + ":" + createdDraft.instructionId());
            assertTrue(booleanField(activity, "managedProjectInstructionEditPending"));
            String revisedRule = "以后在这个项目遇到控制器报警先在回答开头写“"
                    + MARKER + "”，再给一个检查动作";
            runOnMain(instrumentation, () -> invoke(activity,
                    "handleProjectGovernanceVoice",
                    new Class<?>[] { String.class }, new Object[] { revisedRule }));
            ProjectGovernanceDraft revisedDraft = pendingDraft(activity);
            assertEquals(1, revisedDraft.expectedInstructionVersion());
            assertEquals("active", revisedDraft.instructionStatus());
            confirmPending(instrumentation, activity);
            JSONObject v2 = waitForInstructionVersion(
                    client, session.projectId(), createdDraft.instructionId(), 2);
            assertInstruction(v2, 2, "active", revisedRule);

            waitForActivityProjectVersion(activity, session.projectId(),
                    createdDraft.instructionId(), 2);
            perform(instrumentation, activity, "managed_project_instruction_disable:"
                    + session.projectId() + ":" + createdDraft.instructionId());
            ProjectGovernanceDraft disabledDraft = pendingDraft(activity);
            assertEquals("disabled", disabledDraft.instructionStatus());
            assertEquals(revisedRule, disabledDraft.instruction());
            confirmPending(instrumentation, activity);
            JSONObject v3 = waitForInstructionVersion(
                    client, session.projectId(), createdDraft.instructionId(), 3);
            assertInstruction(v3, 3, "disabled", revisedRule);
            assertFalse(diagnose(activity, session,
                    "控制器报警，请只给一个检查动作。",
                    "qa-lifecycle-disabled-" + System.currentTimeMillis()).contains(MARKER));

            waitForActivityProjectVersion(activity, session.projectId(),
                    createdDraft.instructionId(), 3);
            perform(instrumentation, activity, "managed_project_instruction_enable:"
                    + session.projectId() + ":" + createdDraft.instructionId());
            ProjectGovernanceDraft enabledDraft = pendingDraft(activity);
            assertEquals("active", enabledDraft.instructionStatus());
            confirmPending(instrumentation, activity);
            JSONObject v4 = waitForInstructionVersion(
                    client, session.projectId(), createdDraft.instructionId(), 4);
            assertInstruction(v4, 4, "active", revisedRule);
            assertTrue(diagnose(activity, session,
                    "控制器报警，请按当前项目规则回答。",
                    "qa-lifecycle-enabled-" + System.currentTimeMillis()).contains(MARKER));

            waitForActivityProjectVersion(activity, session.projectId(),
                    createdDraft.instructionId(), 4);
            perform(instrumentation, activity, "managed_project_instruction_delete:"
                    + session.projectId() + ":" + createdDraft.instructionId());
            ProjectGovernanceDraft deletedDraft = pendingDraft(activity);
            assertEquals("deleted", deletedDraft.instructionStatus());
            assertTrue(deletedDraft.confirmationDetail(session.projectId())
                    .description().contains("历史版本"));
            confirmPending(instrumentation, activity);
            JSONObject v5 = waitForInstructionVersion(
                    client, session.projectId(), createdDraft.instructionId(), 5);
            assertInstruction(v5, 5, "deleted", revisedRule);
            assertFalse(diagnose(activity, session,
                    "控制器报警，请只给一个检查动作。",
                    "qa-lifecycle-deleted-" + System.currentTimeMillis()).contains(MARKER));
            captureScreen(instrumentation, activity, "02-v5-deleted");

            verifyStaleConflictRefresh(instrumentation, activity, client, session);
            writeResult(activity,
                    "project=" + session.projectId() + "\n"
                            + "task=" + session.id() + "\n"
                            + "instruction=" + createdDraft.instructionId() + "\n"
                            + "versions=active@v1,active@v2,disabled@v3,active@v4,deleted@v5\n"
                            + "modelBaseline=qwen3-vl-plus/fun-asr-realtime\n"
                            + "voiceprint=s1aa729d0\n");
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static void verifyStaleConflictRefresh(
            Instrumentation instrumentation,
            MainActivity activity,
            ExecutionContextDeviceClient client,
            TaskSession session
    ) throws Exception {
        String rule = "以后在这个项目遇到通信中断先检查网线状态";
        runOnMain(instrumentation, () -> invoke(activity,
                "requestManagedProjectInstruction",
                new Class<?>[] { String.class }, new Object[] { rule }));
        ProjectGovernanceDraft draft = pendingDraft(activity);
        confirmPending(instrumentation, activity);
        JSONObject v1 = waitForInstructionVersion(
                client, session.projectId(), draft.instructionId(), 1);
        waitForActivityProjectVersion(activity, session.projectId(), draft.instructionId(), 1);

        perform(instrumentation, activity, "managed_project_instruction_disable:"
                + session.projectId() + ":" + draft.instructionId());
        ProjectGovernanceDraft stale = pendingDraft(activity);
        assertEquals(1, stale.expectedInstructionVersion());

        String authoritativeRule = "以后在这个项目遇到通信中断先检查交换机端口状态";
        client.confirmProjectInstruction(
                session.projectId(),
                draft.instructionId(),
                1,
                "active",
                new JSONObject(v1.getJSONObject("condition").toString()),
                authoritativeRule,
                Collections.<String>emptyList(),
                "trace-air3-authority-" + System.currentTimeMillis(),
                "air3-authority-" + System.currentTimeMillis());
        waitForInstructionVersion(client, session.projectId(), draft.instructionId(), 2);

        confirmPending(instrumentation, activity);
        waitUntil("stale instruction conflict must clear the old draft", SERVER_TIMEOUT_MS,
                () -> field(activity, "pendingProjectGovernanceDraft") == null
                        && !booleanField(activity, "projectGovernanceWriteInFlight"));
        OperationDetail conflict = (OperationDetail) field(activity, "hudOperationDetail");
        assertTrue(conflict.title().contains("未保存"));
        assertTrue(conflict.items().toString().contains("旧草稿已丢弃"));
        assertTrue(conflict.primaryAction().startsWith(
                "managed_project_instruction_refresh:"));

        perform(instrumentation, activity, conflict.primaryAction());
        waitUntil("authority refresh must show server version 2", SERVER_TIMEOUT_MS, () -> {
            Object value = field(activity, "managedProjectInstruction");
            return value instanceof ExecutionContextDeviceClient.ProjectInstruction
                    && ((ExecutionContextDeviceClient.ProjectInstruction) value).version() == 2;
        });
        ExecutionContextDeviceClient.ProjectInstruction refreshed =
                (ExecutionContextDeviceClient.ProjectInstruction) field(
                        activity, "managedProjectInstruction");
        assertEquals(authoritativeRule, refreshed.action());
        assertEquals("active", refreshed.status());
        captureScreen(instrumentation, activity, "03-conflict-refreshed");
    }

    private static void confirmPending(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                new Class<?>[] { String.class },
                new Object[] { "managed_governance_confirm" }));
        waitUntil("governance write must finish", SERVER_TIMEOUT_MS,
                () -> !booleanField(activity, "projectGovernanceWriteInFlight"));
    }

    private static void perform(
            Instrumentation instrumentation,
            MainActivity activity,
            String action
    ) throws Exception {
        runOnMain(instrumentation, () -> invoke(activity, "performManagedExecutionOperation",
                new Class<?>[] { String.class }, new Object[] { action }));
    }

    private static ProjectGovernanceDraft pendingDraft(MainActivity activity) throws Exception {
        waitUntil("project instruction draft must be visible", SERVER_TIMEOUT_MS,
                () -> field(activity, "pendingProjectGovernanceDraft") != null);
        return (ProjectGovernanceDraft) field(activity, "pendingProjectGovernanceDraft");
    }

    private static void waitForActivityProjectVersion(
            MainActivity activity,
            String projectId,
            String instructionId,
            int version
    ) throws Exception {
        waitUntil("activity project detail must refresh", SERVER_TIMEOUT_MS, () -> {
            Object raw = field(activity, "managedProjectDetail");
            if (!(raw instanceof ExecutionContextDeviceClient.ProjectDetail)) return false;
            ExecutionContextDeviceClient.ProjectDetail detail =
                    (ExecutionContextDeviceClient.ProjectDetail) raw;
            ExecutionContextDeviceClient.ProjectInstruction instruction =
                    detail.instruction(instructionId);
            return projectId.equals(detail.localProjectId())
                    && instruction != null && instruction.version() == version;
        });
    }

    private static void waitForServerTask(
            ExecutionContextDeviceClient client,
            String projectId,
            String taskId
    ) throws Exception {
        waitUntil("server task must become active", SERVER_TIMEOUT_MS, () -> {
            JSONArray tasks = client.getProject(projectId).toJson().optJSONArray("tasks");
            for (int index = 0; tasks != null && index < tasks.length(); index++) {
                JSONObject task = tasks.optJSONObject(index);
                if (taskId.equals(task == null ? "" : task.optString("localTaskId", ""))
                        && "active".equals(task.optString("status", ""))) return true;
            }
            return false;
        });
    }

    private static JSONObject waitForInstructionVersion(
            ExecutionContextDeviceClient client,
            String projectId,
            String instructionId,
            int version
    ) throws Exception {
        AtomicReference<JSONObject> result = new AtomicReference<JSONObject>();
        waitUntil("instruction version must appear", SERVER_TIMEOUT_MS, () -> {
            JSONArray values = client.getProject(projectId).toJson()
                    .optJSONArray("projectInstructions");
            for (int index = 0; values != null && index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value != null && instructionId.equals(value.optString("instructionId", ""))
                        && value.optInt("version", 0) == version) {
                    result.set(value);
                    return true;
                }
            }
            return false;
        });
        return result.get();
    }

    private static void assertInstruction(
            JSONObject value,
            int version,
            String status,
            String action
    ) throws Exception {
        assertEquals(version, value.getInt("version"));
        assertEquals(status, value.getString("status"));
        assertEquals(action, value.getString("action"));
    }

    private static String diagnose(
            MainActivity activity,
            TaskSession session,
            String prompt,
            String clientSessionId
    ) throws Exception {
        ManagedRuntimeConfiguration runtime = (ManagedRuntimeConfiguration) field(
                activity, "runtimeConfiguration");
        DeviceSessionManager sessions = (DeviceSessionManager) field(
                activity, "deviceSessionManager");
        assertNotNull(runtime);
        assertNotNull(sessions);
        HttpURLConnection connection = (HttpURLConnection) new URL(
                runtime.backendBaseUrl() + "/sessions/" + clientSessionId
                        + "/diagnose/stream").openConnection();
        OutputStream output = null;
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + sessions.accessToken());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            JSONObject payload = BackendDiagnosisRequest.create(
                    "", prompt, new AiExecutionContext(session.projectId(), session.id()));
            output = connection.getOutputStream();
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            output.close();
            output = null;
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (status < 200 || status >= 300) {
                throw new AssertionError("diagnose failed: HTTP " + status);
            }
            return readSse(stream);
        } finally {
            if (output != null) output.close();
            connection.disconnect();
        }
    }

    private static String readSse(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        String event = "";
        StringBuilder data = new StringBuilder();
        StringBuilder reply = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if ("delta".equals(event) && data.length() > 0) {
                    reply.append(new JSONObject(data.toString()).optString("text", ""));
                } else if ("done".equals(event)) {
                    return reply.toString();
                } else if ("error".equals(event)) {
                    throw new AssertionError("diagnose stream failed: " + data);
                }
                event = "";
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        throw new AssertionError("diagnose stream ended without done");
    }

    private static MainActivity activity(ActivityScenario<MainActivity> scenario) {
        AtomicReference<MainActivity> result = new AtomicReference<MainActivity>();
        scenario.onActivity(result::set);
        return result.get();
    }

    private static TaskSessionManager manager(MainActivity activity) {
        try {
            return (TaskSessionManager) field(activity, "taskSessionManager");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object requiredField(Object target, String name) {
        try {
            return field(target, name);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        return (Boolean) field(target, name);
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object[] arguments
    ) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void runOnMain(Instrumentation instrumentation, Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private static void waitUntil(
            String message,
            long timeoutMs,
            CheckedCondition condition
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.evaluate()) return;
            } catch (Throwable failure) {
                last = failure;
            }
            Thread.sleep(200L);
        }
        AssertionError failure = new AssertionError(message);
        if (last != null) failure.initCause(last);
        throw failure;
    }

    private static void captureScreen(
            Instrumentation instrumentation,
            MainActivity activity,
            String name
    ) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File file = new File(activity.getExternalFilesDir(null), name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private static void writeResult(MainActivity activity, String content) throws Exception {
        File file = new File(activity.getExternalFilesDir(null),
                "project-instruction-lifecycle-result.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
