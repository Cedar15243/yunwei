package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.sync.ExecutionContextDeviceClient;
import com.codex.air3nativecamera.sync.TaskSyncReporter;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Air3 audit against the real V9 gateway: pause, review, cancel, revalidate, resume. */
@RunWith(AndroidJUnit4.class)
public final class ManagedTaskRestoreDeviceTest {
    @Test
    public void realGatewayRequiresReviewAndFreshStateBeforeResumingTheExactTask()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            AtomicReference<TaskSession> created = new AtomicReference<TaskSession>();
            runOnMain(instrumentation, () -> {
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class },
                        new Object[] { "V9.0.20 项目恢复实机验收" });
                TaskSession active = manager(activity).active();
                assertNotNull(active);
                active.maintenanceTask().setDiagnosis(
                        "恢复门禁验收", "确认前不得激活，确认时刷新服务端状态。", 100);
                created.set(active);
                TaskSyncReporter reporter = (TaskSyncReporter) field(activity, "taskSyncReporter");
                assertNotNull(reporter);
                reporter.flush();
                invoke(activity, "returnToHudStandby",
                        new Class<?>[] { String.class },
                        new Object[] { "项目恢复实机验收暂停" });
            });

            TaskSession task = created.get();
            assertNotNull(task);
            assertNull(manager(activity).active());
            ExecutionContextDeviceClient client = (ExecutionContextDeviceClient) field(
                    activity, "executionContextDeviceClient");
            assertNotNull(client);
            ExecutionContextDeviceClient.ProjectDetail project = waitForServerProject(
                    client, task.projectId(), task.id(), 30_000L);

            AtomicReference<String> confirmationTitle = new AtomicReference<String>();
            runOnMain(instrumentation, () -> {
                setField(activity, "managedProjectDetail", project);
                invoke(activity, "requestManagedTaskRestore",
                        new Class<?>[] { String.class, String.class },
                        new Object[] { task.projectId(), task.id() });
                assertNull(manager(activity).active());
                assertNotNull(field(activity, "pendingManagedTaskRestoreDraft"));
                OperationDetail detail = (OperationDetail) field(activity, "hudOperationDetail");
                confirmationTitle.set(detail.title());
                invoke(activity, "performHudOperation",
                        new Class<?>[] { String.class },
                        new Object[] { "managed_task_restore_cancel" });
                assertNull(manager(activity).active());
                assertNull(field(activity, "pendingManagedTaskRestoreDraft"));

                invoke(activity, "requestManagedTaskRestore",
                        new Class<?>[] { String.class, String.class },
                        new Object[] { task.projectId(), task.id() });
                invoke(activity, "performHudOperation",
                        new Class<?>[] { String.class },
                        new Object[] { "managed_task_restore_confirm" });
            });

            assertEquals("确认恢复这个任务？", confirmationTitle.get());
            waitForActiveTask(activity, task.id(), 20_000L);
            assertEquals(task.id(), manager(activity).active().id());
            assertEquals(task.projectId(), manager(activity).active().projectId());
            assertEquals(task.conversationStartIndex(),
                    ((Integer) field(activity, "hudTaskMessageStartIndex")).intValue());
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static ExecutionContextDeviceClient.ProjectDetail waitForServerProject(
            ExecutionContextDeviceClient client,
            String projectId,
            String taskId,
            long timeoutMs
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                ExecutionContextDeviceClient.ProjectDetail detail = client.getProject(projectId);
                JSONArray tasks = detail.toJson().optJSONArray("tasks");
                for (int index = 0; tasks != null && index < tasks.length(); index++) {
                    JSONObject task = tasks.optJSONObject(index);
                    if (task != null && taskId.equals(task.optString("localTaskId", ""))
                            && "active".equals(task.optString("status", ""))) {
                        return detail;
                    }
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("server project did not expose the active task", lastFailure);
    }

    private static void waitForActiveTask(MainActivity activity, String taskId, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskSession active = manager(activity).active();
            if (active != null && taskId.equals(active.id())) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("task was not resumed after fresh server validation");
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

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
