package com.codex.air3nativecamera;

import android.app.Instrumentation;
import android.content.Context;
import android.os.PowerManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;
import com.codex.air3nativecamera.voice.VoiceEventStateMachine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Runs the investor demo in one Activity, matching the real no-navigation workflow. */
@RunWith(AndroidJUnit4.class)
public final class HoneywellFullFlowDeviceTest {
    private static final long AI_TIMEOUT_MS = 90_000L;
    private static final long FIELD_PAUSE_MS = 15_000L;
    private static final String SKILL = "honeywell-temp-humidity";

    @Test
    public void fullDemoStaysInOneTaskWithoutThermalStop() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        List<String> evidence = new ArrayList<String>();
        try {
            runOnMain(instrumentation, () -> {
                invoke(activity, "setEnvironmentAgentEnabled",
                        new Class<?>[] { boolean.class }, new Object[] { true });
                invoke(activity, "createNewProjectChat",
                        new Class<?>[] { boolean.class }, new Object[] { false });
            });

            String previousReply = "";
            for (Step step : steps()) {
                dispatch(instrumentation, activity, step);
                waitForAi(instrumentation, activity);
                TaskSession session = taskManager(activity).active();
                assertNotNull("active task missing at " + step.name, session);
                assertEquals("unexpected scene step at " + step.name,
                        step.expectedStep, session.sceneStepId());
                assertEquals("unexpected scene skill at " + step.name,
                        step.expectedStep.length() == 0 ? "" : SKILL, session.sceneSkillId());
                String reply = latestAssistantReply(activity);
                assertFalse("empty reply at " + step.name, reply.trim().isEmpty());
                assertNotEquals("repeated reply at " + step.name, previousReply, reply);
                assertContainsAny(step.name, reply, step.terms);
                previousReply = reply;
                int thermal = currentThermalStatus(activity);
                evidence.add(step.name + "|" + step.expectedStep + "|" + thermal + "|" + reply);
                assertTrue("thermal stop at " + step.name + ": " + thermal, thermal < 3);
                if (step.expectedStep.length() > 0) {
                    Thread.sleep(FIELD_PAUSE_MS);
                }
            }

            runOnMain(instrumentation, () -> invoke(activity, "createNewProjectChat",
                    new Class<?>[] { boolean.class }, new Object[] { false }));
            Step ordinary = new Step("10-ordinary-isolation", "black.jpg",
                    decode("5pyN5Yqh5Zmo5peg5rOV5ZCv5Yqo77yM5biu5oiR55yL55yL"), "",
                    new String[0]);
            dispatch(instrumentation, activity, ordinary);
            waitForAi(instrumentation, activity);
            TaskSession ordinarySession = taskManager(activity).active();
            assertNotNull(ordinarySession);
            assertEquals("", ordinarySession.sceneSkillId());
            assertEquals("", ordinarySession.sceneStepId());
            String ordinaryReply = latestAssistantReply(activity);
            for (String forbidden : new String[] {
                    decode("6ZyN5bC86Z+m5bCU"), decode("5rip5rm/5bqm"),
                    decode("RERD"), decode("572R5YWzNDg1") }) {
                assertFalse("ordinary task inherited demo wording: " + ordinaryReply,
                        ordinaryReply.contains(forbidden));
            }
            evidence.add("10-ordinary-isolation|||" + ordinaryReply);
            writeEvidence(activity, evidence);
        } finally {
            scenario.close();
        }
    }

    private static void dispatch(Instrumentation instrumentation, MainActivity activity, Step step)
            throws Exception {
        byte[] photo = step.photo.length() == 0 ? new byte[0]
                : readBytes(new File(new File(activity.getFilesDir(), "qa-input/full-flow"), step.photo));
        runOnMain(instrumentation, () -> {
            invoke(activity, "cancelForegroundVoiceListening");
            if (photo.length == 0) {
                setField(activity, "composerTranscript", step.prompt);
                invoke(activity, "sendComposerToAi");
                return;
            }
            invoke(activity, "confirmCapturedPhoto", new Class<?>[] { byte[].class },
                    new Object[] { photo });
            invoke(activity, "cancelForegroundVoiceListening");
            VoiceEventStateMachine stateMachine = (VoiceEventStateMachine) field(
                    activity, "voiceEventStateMachine");
            VoiceEventStateMachine.Signal signal = stateMachine.onDescriptionFinal(step.prompt);
            invoke(activity, "submitVoiceEvent",
                    new Class<?>[] { VoiceEventStateMachine.Signal.class }, new Object[] { signal });
        });
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
            Thread.sleep(250L);
        } while (System.currentTimeMillis() < deadline);
        assertFalse("AI request timed out", "AI_PENDING".equals(state) || streamingIndex >= 0);
        assertEquals("", String.valueOf(field(activity, "recoverableAiError")));
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

    private static void assertContainsAny(String name, String reply, String[] terms) {
        for (String term : terms) {
            if (reply.contains(term)) return;
        }
        throw new AssertionError("reply mismatch at " + name + ": " + reply);
    }

    private static int currentThermalStatus(MainActivity activity) {
        PowerManager manager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        return manager == null ? -1 : manager.getCurrentThermalStatus();
    }

    private static TaskSessionManager taskManager(MainActivity activity) throws Exception {
        return (TaskSessionManager) field(activity, "taskSessionManager");
    }

    private static MainActivity activity(ActivityScenario<MainActivity> scenario) {
        AtomicReference<MainActivity> reference = new AtomicReference<MainActivity>();
        scenario.onActivity(reference::set);
        assertNotNull(reference.get());
        return reference.get();
    }

    private static void writeEvidence(MainActivity activity, List<String> lines) throws Exception {
        File output = new File(activity.getFilesDir(), "qa-full-flow-single-session.txt");
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            stream.write('\n');
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        assertTrue("missing QA photo: " + file, file.isFile());
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
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
        if (failure.get() != null) throw new AssertionError("main-thread action failed", failure.get());
    }

    private static List<Step> steps() {
        List<Step> steps = new ArrayList<Step>();
        steps.add(new Step("01-platform", "platform.jpg", decode("5bmz5Y+w5oql6K2m77yM55yL55yL5oCO5LmI5Zue5LqL"), "system-context",
                terms("5p625p6E5Zu+", "6L+e5o6l5YWz57O7", "57O757uf5oOF5Ya1")));
        steps.add(new Step("02-context", "context.jpg", decode("6L+Z5piv546w5Zy657O757uf5Zu+"), "ddc-power-photo",
                terms("RERD", "55S15rqQ6L6T5YWl")));
        steps.add(new Step("03-ddc-power", "ddc-power.jpg", decode("6L+Z5pivRERD5ZKM55S15rqQ5L2N572u"), "ddc-power-measurement",
                terms("5LiH55So6KGo", "5a6e5rWL55S15Y6L")));
        steps.add(new Step("04-ddc-voltage", "", decode("6aKd5a6aMjTkvI/vvIzlrp7mtYsyNOS8j++8jDI0VkFD5ZKMQ09N5LmL6Ze077yM6K+75pWw56iz5a6a"), "ddc-rs485",
                terms("NDg1")));
        steps.add(new Step("05-ddc-rs485", "ddc-rs485.jpg", decode("6L+Z5pivRERD55qENDg15o6l57q/"), "gateway-rs485",
                terms("572R5YWz", "NDg1")));
        steps.add(new Step("06-gateway", "gateway.jpg", decode("572R5YWzNDg15ZKM572R57uc54q25oCB6YO95Zyo6L+Z5byg5Zu+6YeM"), "sensor-device",
                terms("5Lyg5oSf5Zmo")));
        steps.add(new Step("07-sensor", "sensor.jpg", decode("6L+Z5piv5rip5rm/5bqm5Lyg5oSf5Zmo"), "sensor-wiring",
                terms("5o6l57q/", "5aSW5aOz")));
        steps.add(new Step("08-sensor-repair", "", decode("5o6l57q/54K56Jma5o6l77yM5bey57uP5o6l5aW977yM546w5Zyo55S15Y6L5q2j5bi4"), "platform-recovery",
                terms("5bmz5Y+w", "5oGi5aSN")));
        steps.add(new Step("09-recovery", "", decode("5bmz5Y+w5pWw5o2u5bey5oGi5aSN"), "",
                terms("6Zet546v", "5oGi5aSN", "5a6M5oiQ")));
        return steps;
    }

    private static String[] terms(String... values) {
        String[] decoded = new String[values.length];
        for (int i = 0; i < values.length; i++) decoded[i] = decode(values[i]);
        return decoded;
    }

    private static final class Step {
        final String name;
        final String photo;
        final String prompt;
        final String expectedStep;
        final String[] terms;

        Step(String name, String photo, String prompt, String expectedStep, String[] terms) {
            this.name = name;
            this.photo = photo;
            this.prompt = prompt;
            this.expectedStep = expectedStep;
            this.terms = terms;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
