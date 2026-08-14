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
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Air3 audit for human-reviewed project instructions and condition-scoped AI execution. */
@RunWith(AndroidJUnit4.class)
public final class ManagedProjectInstructionConditionDeviceTest {
    private static final long SERVER_TIMEOUT_MS = 30_000L;
    private static final String TRIGGER = "控制器报警";
    private static final String RULE_MARKER = "规则9027已应用";

    @Test
    public void realGatewayOnlyAppliesTheConfirmedInstructionToMatchingInput() throws Exception {
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
                        new Object[] { "V9 项目指令条件实机验收" });
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class },
                        new Object[] { "控制器报警并伴随通信中断" });
                created.set(manager(activity).active());
                TaskSyncReporter reporter = (TaskSyncReporter) field(activity, "taskSyncReporter");
                assertNotNull(reporter);
                reporter.flush();
            });

            TaskSession session = created.get();
            assertNotNull(session);
            ExecutionContextDeviceClient client = (ExecutionContextDeviceClient) field(
                    activity, "executionContextDeviceClient");
            assertNotNull(client);
            waitForServerTask(client, session.projectId(), session.id());

            String cancelledAction = "以后在这个项目遇到控制器报警先记录故障码";
            runOnMain(instrumentation, () -> invoke(activity,
                    "requestManagedProjectInstruction",
                    new Class<?>[] { String.class }, new Object[] { cancelledAction }));
            ProjectGovernanceDraft cancelledDraft = pendingDraft(activity);
            assertEquals(TRIGGER,
                    cancelledDraft.condition().getJSONArray("containsAny").getString(0));
            OperationDetail cancelledDetail = (OperationDetail) field(activity,
                    "hudOperationDetail");
            assertTrue(cancelledDetail.items().toString()
                    .contains("触发条件：后续问题包含“控制器报警”"));
            captureScreen(instrumentation, activity, "01-condition-review");

            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_cancel" }));
            assertNull(field(activity, "pendingProjectGovernanceDraft"));
            assertFalse(hasInstruction(client.getProject(session.projectId()).toJson(),
                    cancelledDraft.instructionId()));

            String confirmedAction = "以后在这个项目遇到控制器报警先在回答开头写“"
                    + RULE_MARKER + "”，再给一个检查动作";
            runOnMain(instrumentation, () -> invoke(activity,
                    "requestManagedProjectInstruction",
                    new Class<?>[] { String.class }, new Object[] { confirmedAction }));
            ProjectGovernanceDraft confirmedDraft = pendingDraft(activity);
            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_confirm" }));
            waitUntil("confirmed instruction must finish writing", SERVER_TIMEOUT_MS,
                    () -> field(activity, "pendingProjectGovernanceDraft") == null
                            && !booleanField(activity, "projectGovernanceWriteInFlight"));

            JSONObject saved = waitForInstruction(
                    client, session.projectId(), confirmedDraft.instructionId());
            assertEquals(1, saved.getInt("version"));
            assertEquals("active", saved.getString("status"));
            assertEquals(confirmedAction, saved.getString("action"));
            assertEquals(TRIGGER, saved.getJSONObject("condition")
                    .getJSONArray("containsAny").getString(0));
            captureScreen(instrumentation, activity, "02-confirmed-project-record");

            AiReply matching = diagnose(activity, session,
                    "控制器报警，请按当前项目要求给出下一项检查。",
                    "qa-rule-match-" + System.currentTimeMillis());
            assertTrue("matching reply must execute the confirmed rule: " + matching.text,
                    matching.text.contains(RULE_MARKER));

            AiReply nonMatching = diagnose(activity, session,
                    "现在仅检查风扇异响。本轮只回答一个检查动作，不要包含英文或数字编码。",
                    "qa-rule-miss-" + System.currentTimeMillis());
            assertFalse("unmatched input must not receive the project rule: " + nonMatching.text,
                    nonMatching.text.contains(RULE_MARKER));

            writeResult(activity,
                    "project=" + session.projectId() + "\n"
                            + "task=" + session.id() + "\n"
                            + "instruction=" + confirmedDraft.instructionId() + "\n"
                            + "condition=" + saved.getJSONObject("condition") + "\n"
                            + "version=" + saved.getInt("version") + "\n"
                            + "status=" + saved.getString("status") + "\n"
                            + "matchingTrace=" + matching.traceId + "\n"
                            + "matchingReply=" + matching.text + "\n"
                            + "nonMatchingTrace=" + nonMatching.traceId + "\n"
                            + "nonMatchingReply=" + nonMatching.text + "\n");
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static ProjectGovernanceDraft pendingDraft(MainActivity activity) throws Exception {
        waitUntil("project instruction draft must be visible", SERVER_TIMEOUT_MS,
                () -> field(activity, "pendingProjectGovernanceDraft") != null);
        return (ProjectGovernanceDraft) field(activity, "pendingProjectGovernanceDraft");
    }

    private static void waitForServerTask(ExecutionContextDeviceClient client,
            String projectId, String taskId) throws Exception {
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

    private static JSONObject waitForInstruction(ExecutionContextDeviceClient client,
            String projectId, String instructionId) throws Exception {
        AtomicReference<JSONObject> result = new AtomicReference<JSONObject>();
        waitUntil("confirmed instruction must appear in the server project", SERVER_TIMEOUT_MS,
                () -> {
                    JSONObject instruction = instruction(
                            client.getProject(projectId).toJson(), instructionId);
                    result.set(instruction);
                    return instruction != null;
                });
        return result.get();
    }

    private static boolean hasInstruction(JSONObject project, String instructionId) {
        return instruction(project, instructionId) != null;
    }

    private static JSONObject instruction(JSONObject project, String instructionId) {
        JSONArray values = project == null ? null : project.optJSONArray("projectInstructions");
        for (int index = 0; values != null && index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null && instructionId.equals(value.optString("instructionId", ""))) {
                return value;
            }
        }
        return null;
    }

    private static AiReply diagnose(MainActivity activity, TaskSession session, String prompt,
            String clientSessionId) throws Exception {
        ManagedRuntimeConfiguration runtime = (ManagedRuntimeConfiguration) field(
                activity, "runtimeConfiguration");
        DeviceSessionManager sessions = (DeviceSessionManager) field(
                activity, "deviceSessionManager");
        assertNotNull(runtime);
        assertNotNull(sessions);
        URL endpoint = new URL(runtime.backendBaseUrl() + "/sessions/" + clientSessionId
                + "/diagnose/stream");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
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
            String traceId = connection.getHeaderField("X-Trace-Id");
            if (status < 200 || status >= 300) {
                throw new AssertionError("diagnose failed: HTTP " + status + " "
                        + readAll(stream));
            }
            String text = readSse(stream);
            assertFalse("AI reply must not be empty", text.trim().isEmpty());
            assertNotNull("gateway trace id must be present", traceId);
            return new AiReply(text, traceId);
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
        boolean done = false;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if ("delta".equals(event) && data.length() > 0) {
                    reply.append(new JSONObject(data.toString()).optString("text", ""));
                } else if ("done".equals(event)) {
                    done = true;
                    break;
                } else if ("error".equals(event)) {
                    throw new AssertionError("diagnose stream failed: " + data);
                }
                event = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
        assertTrue("diagnose stream must finish with a done event", done);
        return reply.toString().trim();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        return result.toString();
    }

    private static void captureScreen(Instrumentation instrumentation, MainActivity activity,
            String name) throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(500L);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("screenshot must be available: " + name, screenshot);
        File directory = new File(activity.getExternalFilesDir(null),
                "qa-project-instruction-condition");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private static void writeResult(MainActivity activity, String text) throws Exception {
        File file = new File(activity.getFilesDir(), "qa-project-instruction-condition.txt");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static TaskSessionManager manager(MainActivity activity) throws Exception {
        return (TaskSessionManager) field(activity, "taskSessionManager");
    }

    private static MainActivity activity(ActivityScenario<MainActivity> scenario) {
        AtomicReference<MainActivity> reference = new AtomicReference<MainActivity>();
        scenario.onActivity(reference::set);
        assertNotNull(reference.get());
        return reference.get();
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        return ((Boolean) field(target, name)).booleanValue();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void runOnMain(Instrumentation instrumentation, ThrowingRunnable action)
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        instrumentation.runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError("main-thread action failed", failure.get());
        }
    }

    private static void waitUntil(String message, long timeoutMs, Condition condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.matches()) return;
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(100L);
        }
        if (lastFailure != null) throw new AssertionError(message, lastFailure);
        assertTrue(message, condition.matches());
    }

    private static final class AiReply {
        final String text;
        final String traceId;

        AiReply(String text, String traceId) {
            this.text = text;
            this.traceId = traceId;
        }
    }

    private interface Condition {
        boolean matches() throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
