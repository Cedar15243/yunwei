package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import com.codex.air3nativecamera.sync.ExecutionContextDeviceClient;
import com.codex.air3nativecamera.sync.TaskSyncReporter;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Air3 audit against the real V9 gateway for the governed task-end record loop. */
@RunWith(AndroidJUnit4.class)
public final class ManagedTaskEndRecordDeviceTest {
    private static final long SERVER_TIMEOUT_MS = 30_000L;

    @Test
    public void realGatewayKeepsTaskEndHumanReviewedArchivedAndIsolated() throws Exception {
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
                        new Object[] { "V9.0.25 任务结束记录实机验收" });
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class },
                        new Object[] { "控制器报警并伴随通信中断" });
                TaskSession session = manager(activity).active();
                assertNotNull(session);
                MaintenanceTask task = session.maintenanceTask();
                task.setDiagnosis("控制器供电与通信链路待复核",
                        "先核对供电，再检查总线端接与通信状态。", 82);
                task.replaceRepairSteps(new String[] {
                        "记录控制器报警代码",
                        "确认控制器供电电压",
                        "检查通信总线端接",
                        "核对配置页面参数",
                        "清除报警并观察复发",
                        "记录最终处理结果"
                });
                task.advanceRepairStep();
                task.advanceRepairStep();
                task.addEvidence("控制器近景照片", "photo://v9025-controller");
                task.addEvidence("配置页面照片", "photo://v9025-config");
                created.set(session);
                TaskSyncReporter reporter = (TaskSyncReporter) field(activity, "taskSyncReporter");
                assertNotNull(reporter);
                reporter.flush();
            });

            TaskSession session = created.get();
            ExecutionContextDeviceClient client = (ExecutionContextDeviceClient) field(
                    activity, "executionContextDeviceClient");
            assertNotNull(client);
            waitForServerTask(client, session.projectId(), session.id(), "active");

            runOnMain(instrumentation, () -> invoke(activity, "requestManagedTaskEnd",
                    new Class<?>[] { String.class }, new Object[] { "completed" }));
            ProjectGovernanceDraft firstDraft = waitForDraft(activity);
            assertEquals("completed", firstDraft.taskStatus());
            OperationDetail firstSummary = (OperationDetail) field(activity, "hudOperationDetail");
            assertEquals("任务结束摘要", firstSummary.title());
            assertTrue(firstSummary.items().toString().contains("已完成步骤：2/6"));
            assertTrue(firstSummary.items().toString().contains("控制器近景照片"));
            assertTrue(firstSummary.items().toString().contains("配置页面照片"));
            assertTrue(firstSummary.items().size() > 8);
            assertEquals(0, intField(activity, "hudOperationPageIndex"));
            captureScreen(instrumentation, activity, "01-task-end-summary-page-1");

            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class }, new Object[] { "operation_next_page" }));
            assertEquals(1, intField(activity, "hudOperationPageIndex"));
            captureScreen(instrumentation, activity, "02-task-end-summary-page-2");

            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_switch_end:closed" }));
            ProjectGovernanceDraft closedDraft = (ProjectGovernanceDraft) field(
                    activity, "pendingProjectGovernanceDraft");
            assertEquals("closed", closedDraft.taskStatus());
            assertEquals("确认关闭",
                    ((OperationDetail) field(activity, "hudOperationDetail")).primaryLabel());

            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_review_memory" }));
            assertEquals("结束前核对项目记忆",
                    ((OperationDetail) field(activity, "hudOperationDetail")).title());
            captureScreen(instrumentation, activity, "03-task-end-memory-review");
            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_return_end" }));
            assertEquals("任务结束摘要",
                    ((OperationDetail) field(activity, "hudOperationDetail")).title());

            int stepBeforeExpert = session.maintenanceTask().currentRepairStepNumber();
            runOnMain(instrumentation, () -> {
                invoke(activity, "performHudOperation",
                        new Class<?>[] { String.class },
                        new Object[] { "managed_governance_call_expert" });
                invoke(activity, "exitExpertMode");
            });
            assertEquals(session.id(), manager(activity).active().id());
            assertEquals(stepBeforeExpert,
                    manager(activity).active().maintenanceTask().currentRepairStepNumber());
            assertNotNull(field(activity, "pendingProjectGovernanceDraft"));
            captureScreen(instrumentation, activity, "04-expert-returned-to-task-end");

            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_cancel" }));
            assertNull(field(activity, "pendingProjectGovernanceDraft"));
            assertEquals(session.id(), manager(activity).active().id());
            assertEquals("已继续当前任务，结束操作未提交", field(activity, "chatStatus"));

            runOnMain(instrumentation, () -> invoke(activity, "requestManagedTaskEnd",
                    new Class<?>[] { String.class }, new Object[] { "completed" }));
            waitForDraft(activity);
            runOnMain(instrumentation, () -> invoke(activity, "performHudOperation",
                    new Class<?>[] { String.class },
                    new Object[] { "managed_governance_confirm" }));
            final String taskEndReceipt =
                    "任务已结束，项目记录已保存。下一次输入将创建新任务。";
            waitUntil("task end must return to standby", SERVER_TIMEOUT_MS,
                    () -> manager(activity).active() == null
                            && !booleanField(activity, "projectGovernanceWriteInFlight")
                            && field(activity, "pendingProjectGovernanceDraft") == null
                            && booleanField(activity, "requireNewTaskOnNextInput")
                            && taskEndReceipt.equals(field(activity, "chatStatus"))
                            && taskEndReceipt.equals(field(
                                    field(activity, "hudPresentation"),
                                    "pendingStandbyNotice")));
            assertEquals(TaskSession.Status.COMPLETED,
                    manager(activity).find(session.id()).status());
            assertTrue(booleanField(activity, "requireNewTaskOnNextInput"));
            assertEquals(taskEndReceipt, field(activity, "chatStatus"));
            Object presentation = field(activity, "hudPresentation");
            assertEquals(taskEndReceipt, field(presentation, "pendingStandbyNotice"));
            captureScreen(instrumentation, activity, "05-task-end-standby-receipt");

            ExecutionContextDeviceClient.ProjectDetail archived = waitForServerTask(
                    client, session.projectId(), session.id(), "completed");
            JSONObject archivedTask = taskPayload(archived.toJson(), session.id());
            assertTrue(archivedTask.has("skillVersionId"));
            assertTrue(archivedTask.has("knowledgeVersionIds"));
            assertTrue(archivedTask.has("contentManifestVersion"));
            assertTrue(archivedTask.has("contentSnapshotActive"));
            assertFalse(archivedTask.optBoolean("contentSnapshotActive", true));

            runOnMain(instrumentation, () -> {
                setField(activity, "managedProjectDetail", archived);
                invoke(activity, "requestManagedTaskRestore",
                        new Class<?>[] { String.class, String.class },
                        new Object[] { session.projectId(), session.id() });
            });
            assertNull(field(activity, "pendingManagedTaskRestoreDraft"));
            assertTrue(String.valueOf(field(activity, "chatStatus")).contains("不能恢复"));

            AtomicReference<TaskSession> next = new AtomicReference<TaskSession>();
            runOnMain(instrumentation, () -> {
                invoke(activity, "appendUserTranscriptMessage",
                        new Class<?>[] { String.class },
                        new Object[] { "下一张运维单需要检查新设备" });
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class },
                        new Object[] { "新设备无法启动" });
                next.set(manager(activity).active());
            });
            assertNotNull(next.get());
            assertNotEquals(session.id(), next.get().id());
            assertNotEquals(session.projectId(), next.get().projectId());
            writeResult(activity, "endedTask=" + session.id() + "\n"
                    + "endedProject=" + session.projectId() + "\n"
                    + "newTask=" + next.get().id() + "\n"
                    + "newProject=" + next.get().projectId() + "\n"
                    + "archivedSkillVersion=" + archivedTask.optString("skillVersionId", "")
                    + "\narchivedKnowledgeVersions="
                    + archivedTask.optJSONArray("knowledgeVersionIds") + "\n"
                    + "archivedManifestVersion="
                    + archivedTask.optInt("contentManifestVersion", 0) + "\n");
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static ProjectGovernanceDraft waitForDraft(MainActivity activity) throws Exception {
        waitUntil("task end draft must be generated", SERVER_TIMEOUT_MS,
                () -> field(activity, "pendingProjectGovernanceDraft") != null);
        return (ProjectGovernanceDraft) field(activity, "pendingProjectGovernanceDraft");
    }

    private static ExecutionContextDeviceClient.ProjectDetail waitForServerTask(
            ExecutionContextDeviceClient client,
            String projectId,
            String taskId,
            String expectedStatus
    ) throws Exception {
        long deadline = System.currentTimeMillis() + SERVER_TIMEOUT_MS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                ExecutionContextDeviceClient.ProjectDetail detail = client.getProject(projectId);
                JSONObject task = taskPayload(detail.toJson(), taskId);
                if (expectedStatus.equals(task.optString("status", ""))) return detail;
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("server task did not become " + expectedStatus, lastFailure);
    }

    private static JSONObject taskPayload(JSONObject project, String taskId) {
        JSONArray tasks = project == null ? null : project.optJSONArray("tasks");
        for (int index = 0; tasks != null && index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && taskId.equals(task.optString("localTaskId", ""))) return task;
        }
        return new JSONObject();
    }

    private static void captureScreen(Instrumentation instrumentation, MainActivity activity,
            String name) throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(500L);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("screenshot must be available: " + name, screenshot);
        File directory = new File(activity.getExternalFilesDir(null), "qa-task-end");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private static void writeResult(MainActivity activity, String text) throws Exception {
        File file = new File(activity.getFilesDir(), "qa-task-end-record.txt");
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

    private static int intField(Object target, String name) throws Exception {
        return ((Integer) field(target, name)).intValue();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) return;
            Thread.sleep(100L);
        }
        assertTrue(message, condition.matches());
    }

    private interface Condition {
        boolean matches() throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
