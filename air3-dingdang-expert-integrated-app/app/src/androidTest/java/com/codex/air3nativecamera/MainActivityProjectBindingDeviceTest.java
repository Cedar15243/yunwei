package com.codex.air3nativecamera;

import android.app.Instrumentation;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.skills.HoneywellTempHumiditySkill;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/** Covers the field path where visible project messages and the active task became detached. */
@RunWith(AndroidJUnit4.class)
public final class MainActivityProjectBindingDeviceTest {
    @Test
    public void sendingFromAVisibleHistoricalProjectRestoresItsPausedSceneTask() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            AtomicReference<TaskSession> sceneTask = new AtomicReference<TaskSession>();
            AtomicReference<TaskSession> ordinaryTask = new AtomicReference<TaskSession>();
            runOnMain(instrumentation, () -> {
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                TaskSession first = activeTask(activity,
                        invoke(activity, "ensureMaintenanceTask",
                                new Class<?>[] { String.class }, new Object[] { "平台报警" }));
                first.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                        HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT);
                sceneTask.set(first);

                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                ordinaryTask.set(activeTask(activity,
                        invoke(activity, "ensureMaintenanceTask",
                                new Class<?>[] { String.class }, new Object[] { "服务器无法启动" })));

                // Reproduce a restored UI that loaded the older project without rebinding its task.
                setField(activity, "currentProjectIndex", 1);
                invoke(activity, "loadCurrentProjectMessages");
                setField(activity, "hudTaskWorkspaceActive", true);

                MaintenanceTask selected = (MaintenanceTask) invoke(activity,
                        "ensureMaintenanceTask", new Class<?>[] { String.class },
                        new Object[] { "24V正常" });
                assertNotNull(selected);
            });

            TaskSession active = ((TaskSessionManager) field(activity, "taskSessionManager")).active();
            assertNotNull(active);
            assertNotEquals(sceneTask.get().id(), ordinaryTask.get().id());
            assertEquals("visible historical project must restore its own task",
                    sceneTask.get().id(), active.id());
            assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                    active.sceneStepId());
        } finally {
            scenario.close();
        }
    }

    @Test
    public void startingAgainFromHomeCreatesANewOrdinaryTaskInsteadOfResumingTheOldScene()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            AtomicReference<TaskSession> oldSceneTask = new AtomicReference<TaskSession>();
            runOnMain(instrumentation, () -> {
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                TaskSession scene = activeTask(activity,
                        invoke(activity, "ensureMaintenanceTask",
                                new Class<?>[] { String.class }, new Object[] { "平台报警" }));
                scene.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                        HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT);
                oldSceneTask.set(scene);

                invoke(activity, "activateHudTaskWorkspace");
                invoke(activity, "returnToHudStandby",
                        new Class<?>[] { String.class }, new Object[] { "语音待命" });
                // Touch-to-talk and camera entry display the task HUD before evidence is sent.
                invoke(activity, "activateHudTaskWorkspace");
                invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[] { String.class }, new Object[] { "交换机告警" });
            });

            TaskSession active = ((TaskSessionManager) field(activity, "taskSessionManager")).active();
            assertNotNull(active);
            assertNotEquals("home input must not continue the unfinished scene task",
                    oldSceneTask.get().id(), active.id());
            assertNotEquals("home input must start in a new project",
                    oldSceneTask.get().projectId(), active.projectId());
            assertEquals(TaskSession.Status.PAUSED, oldSceneTask.get().status());
            assertEquals("", active.sceneSkillId());
        } finally {
            scenario.close();
        }
    }

    private static TaskSession activeTask(MainActivity activity, Object task) throws Exception {
        assertNotNull(task);
        TaskSession active = ((TaskSessionManager) field(activity, "taskSessionManager")).active();
        assertNotNull(active);
        return active;
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
