package com.codex.air3nativecamera;

import android.app.Instrumentation;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies that an ordinary maintenance task keeps its context across AI turns. */
@RunWith(AndroidJUnit4.class)
public final class MultiTurnTaskDeviceTest {
    private static final long AI_TIMEOUT_MS = 90_000L;

    @Test
    public void sameWorkspaceRemembersTheDeviceAndFaultAcrossTwoAiTurns() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            runOnMain(instrumentation, () -> {
                invoke(activity, "setEnvironmentAgentEnabled",
                        new Class<?>[] { boolean.class }, new Object[] { false });
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
            });

            String firstReply = sendText(instrumentation, activity,
                    "服务器无法启动，设备型号为 H3C UniServer R4900 G5。");
            String secondReply = sendText(instrumentation, activity,
                    "刚才的设备型号和故障是什么？只回答设备型号和故障。");

            assertTrue("follow-up must retain the exact device model: " + secondReply,
                    secondReply.toUpperCase(java.util.Locale.ROOT).contains("R4900"));
            assertTrue("follow-up must retain the startup fault: " + secondReply,
                    secondReply.contains("启动"));

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) field(activity, "chatMessages");
            assertTrue("two AI turns must remain in one visible conversation", messages.size() >= 4);
            TaskSession session = ((TaskSessionManager) field(activity, "taskSessionManager")).active();
            assertNotNull("ordinary task must stay active between turns", session);
            assertTrue("ordinary task must not bind the demo skill", session.sceneSkillId().isEmpty());

            String taskId = session.id();
            runOnMain(instrumentation, () -> {
                boolean handled = (Boolean) invoke(activity, "handleVoicePreviewInteraction",
                        new Class<?>[] { String.class }, new Object[] { "返回首页" });
                assertTrue("global home command must be handled in the task workspace", handled);
            });
            TaskSessionManager manager = (TaskSessionManager) field(activity, "taskSessionManager");
            assertTrue("home must close the task workspace",
                    !((Boolean) field(activity, "hudTaskWorkspaceActive")).booleanValue());
            assertTrue("home must pause rather than discard the task", manager.active() == null);
            assertNotNull("paused task must remain recoverable", manager.find(taskId));
            assertTrue("task must be paused after returning home",
                    manager.find(taskId).status() == TaskSession.Status.PAUSED);

            String result = "messages=" + messages.size() + "\n"
                    + "firstReply=" + firstReply + "\n"
                    + "secondReply=" + secondReply + "\n"
                    + "sceneSkill=" + session.sceneSkillId() + "\n"
                    + "returnedHome=true\n"
                    + "taskStatus=" + manager.find(taskId).status() + "\n";
            writeResult(activity, result);
        } finally {
            scenario.close();
        }
    }

    private static String sendText(Instrumentation instrumentation, MainActivity activity,
            String text) throws Exception {
        runOnMain(instrumentation, () -> {
            invoke(activity, "cancelForegroundVoiceListening");
            setField(activity, "composerTranscript", text);
            invoke(activity, "sendComposerToAi");
        });
        waitForAi(instrumentation, activity);
        String reply = latestAssistantReply(activity);
        assertFalse("AI reply must not be empty", reply.trim().isEmpty());
        return reply;
    }

    private static void waitForAi(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        long deadline = System.currentTimeMillis() + AI_TIMEOUT_MS;
        String state;
        int streamingIndex;
        do {
            instrumentation.waitForIdleSync();
            state = String.valueOf(field(activity, "voiceStreamState"));
            streamingIndex = ((Integer) field(activity, "streamingAssistantIndex")).intValue();
            if (!"AI_PENDING".equals(state) && streamingIndex < 0) {
                break;
            }
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < deadline);
        assertFalse("AI request timed out", "AI_PENDING".equals(state) || streamingIndex >= 0);
        assertTrue("AI transport error: " + field(activity, "recoverableAiError"),
                String.valueOf(field(activity, "recoverableAiError")).isEmpty());
    }

    private static String latestAssistantReply(MainActivity activity) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) field(activity, "chatMessages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object message = messages.get(i);
            if ("assistant".equals(String.valueOf(field(message, "role")))
                    && "text".equals(String.valueOf(field(message, "kind")))) {
                return String.valueOf(field(message, "text"));
            }
        }
        return "";
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

    private static void writeResult(MainActivity activity, String text) throws Exception {
        File file = new File(activity.getFilesDir(), "qa-multiturn-task.txt");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
