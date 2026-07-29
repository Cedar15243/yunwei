package com.codex.air3nativecamera;

import android.app.Instrumentation;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;
import com.codex.air3nativecamera.skills.SceneReferenceGuide;
import com.codex.air3nativecamera.voice.VoiceEventStateMachine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Injects a field JPEG at the Camera2 completion boundary for device-only integration QA. */
@RunWith(AndroidJUnit4.class)
public final class FieldEvidenceInjectionTest {
    private static final long AI_TIMEOUT_MS = 90_000L;

    @Test
    public void testInjectPhotoAndPromptThroughProductionWorkflow() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        AtomicReference<MainActivity> activityReference = new AtomicReference<MainActivity>();
        scenario.onActivity(activityReference::set);
        MainActivity activity = activityReference.get();
        assertTrue("MainActivity must launch", activity != null);
        File inputDirectory = new File(activity.getFilesDir(), "qa-input");
        String mode = readOptionalText(new File(inputDirectory, "mode.txt"));
        boolean textOnly = mode.toLowerCase(java.util.Locale.ROOT).contains("text");
        boolean startNewTask = mode.toLowerCase(java.util.Locale.ROOT).contains("new");
        byte[] jpegBytes = textOnly
                ? new byte[0]
                : readBytes(new File(inputDirectory, "current.jpg"));
        String prompt = new String(
                readBytes(new File(inputDirectory, "prompt.txt")), StandardCharsets.UTF_8).trim();
        assertTrue("QA JPEG must not be empty for a photo turn", textOnly || jpegBytes.length > 0);
        assertTrue("QA prompt must not be empty", prompt.length() > 0);

        AtomicReference<Throwable> dispatchFailure = new AtomicReference<Throwable>();
        instrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    invoke(activity, "setEnvironmentAgentEnabled",
                            new Class<?>[] { boolean.class }, new Object[] { true });
                    if (startNewTask) {
                        invoke(activity, "createNewProjectChat",
                                new Class<?>[] { boolean.class }, new Object[] { false });
                    } else {
                        resumeLatestSceneTaskIfNeeded(activity);
                    }
                    invoke(activity, "cancelForegroundVoiceListening", new Class<?>[0], new Object[0]);
                    if (textOnly) {
                        setField(activity, "composerTranscript", prompt);
                        invoke(activity, "sendComposerToAi", new Class<?>[0], new Object[0]);
                    } else {
                        invoke(activity, "confirmCapturedPhoto", new Class<?>[] { byte[].class },
                                new Object[] { jpegBytes });
                        invoke(activity, "cancelForegroundVoiceListening", new Class<?>[0], new Object[0]);
                        VoiceEventStateMachine stateMachine = (VoiceEventStateMachine) field(
                                activity, "voiceEventStateMachine");
                        VoiceEventStateMachine.Signal signal = stateMachine.onDescriptionFinal(prompt);
                        invoke(activity, "submitVoiceEvent",
                                new Class<?>[] { VoiceEventStateMachine.Signal.class },
                                new Object[] { signal });
                    }
                } catch (Throwable error) {
                    dispatchFailure.set(error);
                }
            }
        });
        if (dispatchFailure.get() != null) {
            throw new AssertionError("Unable to dispatch QA evidence turn", dispatchFailure.get());
        }

        long deadline = System.currentTimeMillis() + AI_TIMEOUT_MS;
        String state = "";
        int streamingIndex = -1;
        do {
            instrumentation.waitForIdleSync();
            state = String.valueOf(field(activity, "voiceStreamState"));
            streamingIndex = ((Integer) field(activity, "streamingAssistantIndex")).intValue();
            if (!"AI_PENDING".equals(state) && streamingIndex < 0) {
                break;
            }
            Thread.sleep(250L);
        } while (System.currentTimeMillis() < deadline);

        String recoverableError = String.valueOf(field(activity, "recoverableAiError"));
        assertFalse("AI request did not finish before timeout",
                "AI_PENDING".equals(state) || streamingIndex >= 0);
        assertEquals("AI request must complete without a recoverable transport error",
                "", recoverableError);

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) field(activity, "chatMessages");
        assertFalse("Evidence turn must append chat messages", messages.isEmpty());
        String lastRole = "";
        String lastText = "";
        int referenceImages = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object message = messages.get(i);
            String role = String.valueOf(field(message, "role"));
            String kind = String.valueOf(field(message, "kind"));
            String imageId = String.valueOf(field(message, "imageId"));
            if (SceneReferenceGuide.isReferenceImageId(imageId)) {
                referenceImages++;
            }
            if (lastText.length() == 0 && "assistant".equals(role) && "text".equals(kind)) {
                lastRole = role;
                lastText = String.valueOf(field(message, "text"));
            }
        }
        assertEquals("assistant", lastRole);
        assertTrue("AI response must not be empty", lastText.trim().length() > 0);

        AtomicReference<Throwable> persistenceFailure = new AtomicReference<Throwable>();
        instrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    invoke(activity, "persistChatProjects", new Class<?>[0], new Object[0]);
                } catch (Throwable error) {
                    persistenceFailure.set(error);
                }
            }
        });
        if (persistenceFailure.get() != null) {
            throw new AssertionError("Unable to persist QA task state", persistenceFailure.get());
        }
        assertTrue("QA task state must be flushed before the target process exits",
                activity.getSharedPreferences("dingdang_chat_projects", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong("qa_last_flush_ms", System.currentTimeMillis())
                        .commit());

        TaskSession activeSession = ((TaskSessionManager) field(activity,
                "taskSessionManager")).active();
        String result = "state=" + state + "\n"
                + "messages=" + messages.size() + "\n"
                + "lastRole=" + lastRole + "\n"
                + "lastText=" + lastText + "\n"
                + "referenceImages=" + referenceImages + "\n"
                + "sceneSkill=" + (activeSession == null ? "" : activeSession.sceneSkillId()) + "\n"
                + "sceneStep=" + (activeSession == null ? "" : activeSession.sceneStepId()) + "\n";
        writeBytes(new File(activity.getFilesDir(), "qa-result.txt"),
                result.getBytes(StandardCharsets.UTF_8));
        scenario.close();
    }

    private static void resumeLatestSceneTaskIfNeeded(MainActivity activity) throws Exception {
        TaskSessionManager manager = (TaskSessionManager) field(activity, "taskSessionManager");
        TaskSession active = manager.active();
        if (active != null && active.sceneSkillId().length() > 0) {
            return;
        }
        List<TaskSession> sessions = manager.sessions();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            TaskSession session = sessions.get(i);
            if (session.status() != TaskSession.Status.COMPLETED
                    && session.sceneSkillId().length() > 0) {
                invoke(activity, "resumeTaskWorkspace", new Class<?>[] { String.class },
                        new Object[] { session.id() });
                return;
            }
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

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
            Object[] arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static byte[] readBytes(File file) throws Exception {
        assertFile(file);
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String readOptionalText(File file) throws Exception {
        return file != null && file.isFile()
                ? new String(readBytes(file), StandardCharsets.UTF_8).trim()
                : "photo";
    }

    private static void assertFile(File file) {
        if (file == null || !file.isFile()) {
            throw new AssertionError("Missing QA input: " + file);
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
        }
    }
}
