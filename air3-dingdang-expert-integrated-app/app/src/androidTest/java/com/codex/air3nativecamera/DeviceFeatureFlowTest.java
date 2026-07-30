package com.codex.air3nativecamera;

import android.Manifest;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import android.view.TextureView;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.features.inspection.InspectionRun;
import com.codex.air3nativecamera.features.operations.OperationDetailFactory;
import com.codex.air3nativecamera.ui.hud.HudWebPresentation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device-side integration audit for local voice routing, page bounds, camera and video. */
@RunWith(AndroidJUnit4.class)
public final class DeviceFeatureFlowTest {
    private static final long UI_TIMEOUT_MS = 15_000L;
    private static final long CAMERA_TIMEOUT_MS = 20_000L;

    @Test
    public void voiceRoutesReachEveryPrimaryPageAndReturnHome() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        prepareDevice(instrumentation);
        grantRuntimePermissions(instrumentation);
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            forceStandby(instrumentation, activity);
            waitForHudState(activity, "standby");
            captureScreen(instrumentation, activity, "00-home");

            dispatchVoice(instrumentation, activity, "打开语音帮助");
            assertTrue(booleanField(activity, "hudVoiceGuideVisible"));
            assertSingleScreen(activity, "voiceGuide");
            captureScreen(instrumentation, activity, "01-voice-help");
            returnHome(instrumentation, activity);

            dispatchVoice(instrumentation, activity, "眼镜使用教学");
            assertTrue(booleanField(activity, "hudGlassesGuideVisible"));
            assertSingleScreen(activity, "glassesGuide");
            captureScreen(instrumentation, activity, "02-glasses-guide");
            dispatchVoice(instrumentation, activity, "下一页");
            assertSingleScreen(activity, "glassesGuide");
            returnHome(instrumentation, activity);

            dispatchVoice(instrumentation, activity, "打开AI能力中心");
            assertTrue(booleanField(activity, "hudCapabilityVisible"));
            assertFalse(booleanField(activity, "capabilityDetailVisible"));
            assertSingleScreen(activity, "capabilities");
            captureScreen(instrumentation, activity, "03-capabilities");
            returnHome(instrumentation, activity);

            assertOperationPage(instrumentation, activity, "巡检任务", "inspection",
                    "04-inspection-catalog");
            dispatchVoice(instrumentation, activity, "进入第一个选项");
            InspectionRun inspectionRun = (InspectionRun) field(activity, "activeInspectionRun");
            assertNotNull("first inspection option must start a real run", inspectionRun);
            assertEquals("lab-training-room", inspectionRun.definition().id());
            assertSingleScreen(activity, "operationDetail");
            captureScreen(instrumentation, activity, "05-inspection-running");
            returnHome(instrumentation, activity);

            assertOperationPage(instrumentation, activity, "维修任务", "tasks",
                    "06-maintenance-tasks");
            returnHome(instrumentation, activity);

            assertOperationPage(instrumentation, activity, "华方知识库", "knowledge",
                    "07-knowledge");
            dispatchVoice(instrumentation, activity, "下一页");
            assertSingleScreen(activity, "operationDetail");
            dispatchVoice(instrumentation, activity, "上一页");
            returnHome(instrumentation, activity);

            assertOperationPage(instrumentation, activity, "设备记忆", "device_brain",
                    "08-device-memory");
            returnHome(instrumentation, activity);

            assertOperationPage(instrumentation, activity, "AI运维技能", "agent_center",
                    "09-agent-center");
            dispatchVoice(instrumentation, activity, "启用环境诊断技能");
            assertTrue(agentEnabled(activity));
            dispatchVoice(instrumentation, activity, "下一页");
            captureScreen(instrumentation, activity, "10-agent-enabled");
            dispatchVoice(instrumentation, activity, "停用环境诊断技能");
            assertFalse(agentEnabled(activity));
            returnHome(instrumentation, activity);

            dispatchVoice(instrumentation, activity, "呼叫专家");
            waitUntil("expert surface must open", UI_TIMEOUT_MS,
                    () -> "EXPERT".equals(String.valueOf(field(activity, "screenMode"))));
            captureScreen(instrumentation, activity, "11-expert-waiting");
            returnHome(instrumentation, activity);
            assertEquals("CHAT", String.valueOf(field(activity, "screenMode")));
        } finally {
            scenario.close();
        }
    }

    @Test
    public void cameraPhotoAndVideoKeepLandscapeEvidenceAndReturnToHud() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        prepareDevice(instrumentation);
        grantRuntimePermissions(instrumentation);
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            forceStandby(instrumentation, activity);
            waitForHudState(activity, "standby");

            runOnMain(instrumentation, () -> invoke(activity, "enterCameraScreen",
                    new Class<?>[] { String.class }, new Object[] { "qa-preview" }));
            waitForCameraReady(activity);
            assertImmersive(instrumentation, activity);
            captureScreen(instrumentation, activity, "12-camera-preview");
            runOnMain(instrumentation, () -> invoke(activity, "captureStillImage"));
            waitUntil("photo must return to the HUD", CAMERA_TIMEOUT_MS,
                    () -> "CHAT".equals(String.valueOf(field(activity, "screenMode")))
                            && byteArrayField(activity, "composerImageBytes").length > 0);
            byte[] jpeg = byteArrayField(activity, "composerImageBytes");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            assertLandscape16By9("captured JPEG", bounds.outWidth, bounds.outHeight);
            assertTrue("Air3 evidence width must improve on the old 1280px stream",
                    bounds.outWidth >= 1500);
            assertTrue("Air3 evidence pixel count must improve on the old 720p stream",
                    bounds.outWidth * bounds.outHeight > 1280 * 720);
            writeQaArtifact(activity, "captured-photo.jpg", jpeg);
            assertSingleScreen(activity, "photoDraft");
            assertImmersive(instrumentation, activity);
            captureScreen(instrumentation, activity, "13-photo-draft");
            returnHome(instrumentation, activity);

            int videosBefore = evidenceVideoCount(activity);
            dispatchVoice(instrumentation, activity, "短视频取证");
            waitUntil("video recording must start", CAMERA_TIMEOUT_MS,
                    () -> booleanField(activity, "sceneVideoRecording"));
            captureScreen(instrumentation, activity, "14-video-recording");
            Thread.sleep(2_000L);
            dispatchVoice(instrumentation, activity, "停止录像");
            waitUntil("video must stop and return to HUD", CAMERA_TIMEOUT_MS,
                    () -> !booleanField(activity, "sceneVideoRecording")
                            && "CHAT".equals(String.valueOf(field(activity, "screenMode")))
                            && evidenceVideoCount(activity) > videosBefore);
            File latestVideo = latestEvidenceVideo(activity);
            assertNotNull("recorded video file must exist", latestVideo);
            assertTrue("recorded video file must not be empty", latestVideo.length() > 0L);
            assertVideoLandscape16By9(latestVideo);
            captureScreen(instrumentation, activity, "15-video-complete");
            returnHome(instrumentation, activity);
        } finally {
            scenario.close();
        }
    }

    private static void assertOperationPage(Instrumentation instrumentation, MainActivity activity,
            String command, String abilityId, String screenshotName) throws Exception {
        dispatchVoice(instrumentation, activity, command);
        assertTrue("capability surface must remain visible for " + command,
                booleanField(activity, "hudCapabilityVisible"));
        assertTrue("operation detail must open for " + command,
                booleanField(activity, "capabilityDetailVisible"));
        assertEquals(abilityId, String.valueOf(field(activity, "hudOperationAbilityId")));
        assertSingleScreen(activity, "operationDetail");
        captureScreen(instrumentation, activity, screenshotName);
    }

    private static void returnHome(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        dispatchVoice(instrumentation, activity, "返回首页");
        waitUntil("global home command must restore standby", UI_TIMEOUT_MS,
                () -> "standby".equals(activeHudState(activity))
                        && "CHAT".equals(String.valueOf(field(activity, "screenMode"))));
        assertFalse(booleanField(activity, "hudVoiceGuideVisible"));
        assertFalse(booleanField(activity, "hudGlassesGuideVisible"));
        assertFalse(booleanField(activity, "hudCapabilityVisible"));
    }

    private static void forceStandby(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        runOnMain(instrumentation, () -> invoke(activity, "returnToHudHomeFromVoice"));
    }

    private static void grantRuntimePermissions(Instrumentation instrumentation) {
        String packageName = instrumentation.getTargetContext().getPackageName();
        instrumentation.getUiAutomation().grantRuntimePermission(packageName,
                Manifest.permission.CAMERA);
        instrumentation.getUiAutomation().grantRuntimePermission(packageName,
                Manifest.permission.RECORD_AUDIO);
    }

    private static void prepareDevice(Instrumentation instrumentation) throws Exception {
        executeShellCommand(instrumentation, "input keyevent KEYCODE_WAKEUP");
        executeShellCommand(instrumentation, "wm dismiss-keyguard");
        executeShellCommand(instrumentation, "cmd statusbar collapse");
        Thread.sleep(500L);
    }

    private static void executeShellCommand(Instrumentation instrumentation, String command)
            throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation()
                        .executeShellCommand(command);
                FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) >= 0) {
                // Drain the command output so the shell command completes before the test continues.
            }
        }
    }

    private static void dispatchVoice(Instrumentation instrumentation, MainActivity activity,
            String text) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<Boolean> handled = new AtomicReference<Boolean>(Boolean.FALSE);
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    invoke(activity, "cancelForegroundVoiceListening");
                    handled.set((Boolean) invoke(activity, "handleVoicePreviewInteraction",
                            new Class<?>[] { String.class }, new Object[] { text }));
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            throw new AssertionError("voice dispatch failed: " + text, failure.get());
        }
        assertTrue("voice command must be handled: " + text, handled.get().booleanValue());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(250L);
    }

    private static MainActivity activity(ActivityScenario<MainActivity> scenario) {
        AtomicReference<MainActivity> reference = new AtomicReference<MainActivity>();
        scenario.onActivity(reference::set);
        assertNotNull("MainActivity must launch", reference.get());
        return reference.get();
    }

    private static void waitForHudState(MainActivity activity, String expected) throws Exception {
        waitUntil("HUD state must become " + expected, UI_TIMEOUT_MS,
                () -> expected.equals(activeHudState(activity)));
    }

    private static void waitForCameraReady(MainActivity activity) throws Exception {
        waitUntil("camera preview must become ready", CAMERA_TIMEOUT_MS,
                () -> "CAMERA".equals(String.valueOf(field(activity, "screenMode")))
                        && field(activity, "cameraDevice") != null
                        && field(activity, "captureSession") != null
                        && field(activity, "imageReader") != null
                        && booleanField(activity, "cameraPreviewStable")
                        && previewHasFrame(activity));
    }

    private static void assertSingleScreen(MainActivity activity, String expectedState)
            throws Exception {
        String metrics = evaluate(activity,
                "(function(){var a=document.querySelector('.state.active');"
                        + "if(!a)return 'missing';var r=a.getBoundingClientRect();"
                        + "return [a.id,window.innerWidth,window.innerHeight,"
                        + "document.documentElement.scrollHeight,document.body.scrollHeight,"
                        + "Math.round(r.top),Math.round(r.bottom),a.scrollHeight,a.clientHeight].join('|')})()");
        String[] parts = metrics.split("\\|");
        assertTrue("invalid HUD metrics: " + metrics, parts.length == 9);
        assertEquals("unexpected active HUD page", expectedState, parts[0]);
        int viewportHeight = Integer.parseInt(parts[2]);
        int documentHeight = Integer.parseInt(parts[3]);
        int bodyHeight = Integer.parseInt(parts[4]);
        int activeBottom = Integer.parseInt(parts[6]);
        assertTrue("document exceeds one screen: " + metrics,
                documentHeight <= viewportHeight + 2);
        assertTrue("body exceeds one screen: " + metrics,
                bodyHeight <= viewportHeight + 2);
        assertTrue("active page extends below viewport: " + metrics,
                activeBottom <= viewportHeight + 2);
    }

    private static String activeHudState(MainActivity activity) throws Exception {
        return evaluate(activity,
                "(function(){var a=document.querySelector('.state.active');return a?a.id:''})()");
    }

    private static String evaluate(MainActivity activity, String script) throws Exception {
        HudWebPresentation presentation = (HudWebPresentation) field(activity, "hudPresentation");
        assertNotNull("HUD presentation must exist", presentation);
        WebView webView = presentation.view();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<String>("");
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(script, new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        try {
                            Object decoded = new JSONTokener(value).nextValue();
                            result.set(decoded == null ? "" : String.valueOf(decoded));
                        } catch (Exception error) {
                            result.set(value == null ? "" : value);
                        }
                        latch.countDown();
                    }
                });
            }
        });
        assertTrue("HUD JavaScript timed out", latch.await(5L, TimeUnit.SECONDS));
        return result.get();
    }

    private static void captureScreen(Instrumentation instrumentation, MainActivity activity,
            String name) throws Exception {
        Thread.sleep(800L);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("screenshot must be available: " + name, screenshot);
        File directory = new File(activity.getExternalFilesDir(null), "qa-screenshots");
        assertTrue("unable to create screenshot directory", directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            assertTrue("unable to encode screenshot: " + name,
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private static void writeQaArtifact(MainActivity activity, String name, byte[] bytes)
            throws Exception {
        File directory = new File(activity.getExternalFilesDir(null), "qa-artifacts");
        assertTrue("unable to create QA artifact directory",
                directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name), false)) {
            output.write(bytes);
            output.flush();
        }
    }

    private static boolean previewHasFrame(MainActivity activity) throws Exception {
        TextureView preview = (TextureView) field(activity, "previewView");
        if (preview == null || !preview.isAvailable()) return false;
        Bitmap bitmap = preview.getBitmap(96, 54);
        if (bitmap == null) return false;
        try {
            int min = 255;
            int max = 0;
            long total = 0L;
            int count = 0;
            for (int y = 0; y < bitmap.getHeight(); y += 3) {
                for (int x = 0; x < bitmap.getWidth(); x += 3) {
                    int color = bitmap.getPixel(x, y);
                    int luminance = (android.graphics.Color.red(color) * 3
                            + android.graphics.Color.green(color) * 6
                            + android.graphics.Color.blue(color)) / 10;
                    min = Math.min(min, luminance);
                    max = Math.max(max, luminance);
                    total += luminance;
                    count++;
                }
            }
            int average = count == 0 ? 255 : (int) (total / count);
            return max - min >= 6 || average < 245;
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean agentEnabled(MainActivity activity) throws Exception {
        OperationDetailFactory factory = (OperationDetailFactory) field(activity,
                "operationDetailFactory");
        return factory.isAgentPackageAuthorized("environment_ops");
    }

    private static int evidenceVideoCount(MainActivity activity) {
        File directory = new File(activity.getFilesDir(), "evidence");
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".mp4"));
        return files == null ? 0 : files.length;
    }

    private static File latestEvidenceVideo(MainActivity activity) {
        File directory = new File(activity.getFilesDir(), "evidence");
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File file : files) {
            if (file.lastModified() > latest.lastModified()) latest = file;
        }
        return latest;
    }

    private static void assertVideoLandscape16By9(File video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            int width = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation == 90 || rotation == 270) {
                int swap = width;
                width = height;
                height = swap;
            }
            assertLandscape16By9("recorded video", width, height);
        } finally {
            try {
                retriever.release();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private static void assertLandscape16By9(String label, int width, int height) {
        assertTrue(label + " dimensions must be valid: " + width + "x" + height,
                width > 0 && height > 0);
        float ratio = width / (float) height;
        assertTrue(label + " must be landscape 16:9, actual=" + width + "x" + height,
                width > height && ratio >= 1.72f && ratio <= 1.82f);
    }

    private static void assertImmersive(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        AtomicReference<Integer> visibility = new AtomicReference<Integer>(Integer.valueOf(0));
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                visibility.set(Integer.valueOf(
                        activity.getWindow().getDecorView().getSystemUiVisibility()));
            }
        });
        int required = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        assertEquals("HUD must restore fullscreen immersive flags after camera return",
                required, visibility.get().intValue() & required);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static byte[] byteArrayField(Object target, String name) throws Exception {
        byte[] value = (byte[]) field(target, name);
        return value == null ? new byte[0] : value;
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

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
            Object[] arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void runOnMain(Instrumentation instrumentation, ThrowingRunnable action)
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    action.run();
                } catch (Throwable error) {
                    failure.set(error);
                }
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
