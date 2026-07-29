package com.codex.air3nativecamera;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.voice.WakeWordEngine;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Replays deterministic speech from Air3's speaker into the real wake/ASR pipeline. */
@RunWith(AndroidJUnit4.class)
public final class SyntheticVoiceLoopTest {
    private static final long WAKE_TIMEOUT_MS = 12_000L;
    private static final long COMMAND_TIMEOUT_MS = 40_000L;

    @Test
    public void wakeOpensVoiceHelpAndSecondWakeReturnsHome() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),
                Manifest.permission.RECORD_AUDIO);
        File inputDirectory = new File(context.getFilesDir(), "qa-input");
        File wakeFile = requiredFile(inputDirectory, "wake.wav");
        File openHelpFile = requiredFile(inputDirectory, "open-voice-help.wav");
        File returnHomeFile = requiredFile(inputDirectory, "return-home.wav");

        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        AtomicReference<MainActivity> activityReference = new AtomicReference<MainActivity>();
        scenario.onActivity(activityReference::set);
        MainActivity activity = activityReference.get();
        assertTrue("MainActivity must launch", activity != null);
        instrumentation.runOnMainSync(() -> {
            try {
                invoke(activity, "returnToHudHomeFromVoice");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        try {
            waitUntil("offline wake must arm on the home screen", WAKE_TIMEOUT_MS,
                    () -> wakeEngine(activity).isRunning());
            playSpeech(wakeFile);
            waitUntil("wake audio must open a command recording window", WAKE_TIMEOUT_MS,
                    () -> booleanField(activity, "recordingVoice"));
            playSpeech(openHelpFile);
            waitUntil("voice help must open after ASR routes the command", COMMAND_TIMEOUT_MS,
                    () -> booleanField(activity, "hudVoiceGuideVisible"));

            waitUntil("offline wake must re-arm on voice help", WAKE_TIMEOUT_MS,
                    () -> wakeEngine(activity).isRunning());
            playSpeech(wakeFile);
            waitUntil("second wake must open a command recording window", WAKE_TIMEOUT_MS,
                    () -> booleanField(activity, "recordingVoice"));
            playSpeech(returnHomeFile);
            waitUntil("return-home command must close voice help", COMMAND_TIMEOUT_MS,
                    () -> !booleanField(activity, "hudVoiceGuideVisible"));

            assertFalse("home must not retain a capability overlay",
                    booleanField(activity, "hudCapabilityVisible"));
            assertFalse("home must not retain the task workspace",
                    booleanField(activity, "hudTaskWorkspaceActive"));
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0);
            scenario.close();
        }
    }

    private static WakeWordEngine wakeEngine(MainActivity activity) throws Exception {
        return (WakeWordEngine) field(activity, "wakeWordEngine");
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
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void waitUntil(String message, long timeoutMs, Condition condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(message, condition.matches());
    }

    private static void playSpeech(File file) throws Exception {
        CountDownLatch completion = new CountDownLatch(1);
        MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(ignored -> completion.countDown());
            player.setOnErrorListener((ignored, what, extra) -> {
                completion.countDown();
                return false;
            });
            player.prepare();
            player.start();
            assertTrue("speech playback timed out: " + file.getName(),
                    completion.await(15L, TimeUnit.SECONDS));
        } finally {
            player.release();
        }
    }

    private static File requiredFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.isFile() || file.length() <= 44L) {
            throw new AssertionError("Missing QA speech input: " + file);
        }
        return file;
    }

    private interface Condition {
        boolean matches() throws Exception;
    }
}
