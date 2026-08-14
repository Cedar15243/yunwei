package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSessionManager;
import com.codex.air3nativecamera.ui.hud.HudWebPresentation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Air3 audit for native content paging and repair-step navigation separation. */
@RunWith(AndroidJUnit4.class)
public final class ManagedLongReplyNavigationDeviceTest {
    private static final long UI_TIMEOUT_MS = 15_000L;
    private static final int CONVERSATION_PAGE_SIZE = 112;
    private static final String LONG_REPLY =
            "当前判断：控制柜告警与风机停机同时出现，先不要直接复位控制器。\n\n"
                    + "1. 核对控制柜主电源、控制电源和急停回路，记录三项指示灯状态。\n"
                    + "2. 拍摄变频器面板完整故障码，照片中同时保留设备铭牌和运行频率。\n"
                    + "3. 使用万用表确认控制端子电压，测量前先确认量程并做好绝缘防护。\n"
                    + "4. 如果故障码指向过载，检查风机叶轮是否卡滞、皮带是否过紧以及电机温升。\n"
                    + "5. 如果控制电源正常但接触器不吸合，检查联锁输入、热继保护和控制输出。\n\n"
                    + "完成上述检查后，把故障码、三项电压和现场照片发回当前任务，我再根据证据给出下一步。";

    @Test
    public void longReplyFitsEveryPageAndKeepsPageAndStepCommandsSeparate() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        AtomicReference<MaintenanceTask> taskReference = new AtomicReference<MaintenanceTask>();
        try {
            runOnMain(instrumentation, () -> {
                invoke(activity, "cancelForegroundVoiceListening");
                invoke(activity, "createNewProjectChat",
                        new Class<?>[]{boolean.class}, new Object[]{false});
                invoke(activity, "clearHudTaskProgress");
                invoke(activity, "appendUserTranscriptMessage",
                        new Class<?>[]{String.class},
                        new Object[]{"控制柜告警，风机停止运行，请给出排查步骤"});
                MaintenanceTask task = (MaintenanceTask) invoke(activity, "ensureMaintenanceTask",
                        new Class<?>[]{String.class},
                        new Object[]{"控制柜告警，风机停止运行"});
                assertNotNull(task);
                invoke(activity, "activateHudTaskWorkspace");
                task.addTurn("现场人员", "控制柜告警，风机停止运行，请给出排查步骤");
                task.addTurn("AI", LONG_REPLY);
                task.setDiagnosis("控制回路或负载保护异常", LONG_REPLY, 78);
                invoke(activity, "appendAssistantMessage",
                        new Class<?>[]{String.class}, new Object[]{LONG_REPLY});
                invoke(activity, "syncHudPresentation");
                taskReference.set(task);
            });

            MaintenanceTask task = taskReference.get();
            assertNotNull(task);
            assertTrue(task.conversationPageCount(CONVERSATION_PAGE_SIZE) >= 4);
            captureScreen(instrumentation, activity, "00-after-fixture");
            waitForConversationPage(activity, instrumentation, "1 / ");

            JSONObject firstPage = assertConversationLayout(activity, instrumentation);
            assertTrue(firstPage.getString("text").contains("当前判断"));
            assertEquals(visibleText(task.conversationPage(0, CONVERSATION_PAGE_SIZE)),
                    visibleText(firstPage.getString("text")));
            captureScreen(instrumentation, activity, "01-long-reply-page-1");

            int initialStep = task.currentRepairStepNumber();
            StringBuilder displayedReply = new StringBuilder(
                    visibleText(firstPage.getString("text")));
            StringBuilder expectedReply = new StringBuilder(
                    visibleText(task.conversationPage(0, CONVERSATION_PAGE_SIZE)));
            int pageCount = task.conversationPageCount(CONVERSATION_PAGE_SIZE);
            for (int pageIndex = 1; pageIndex < pageCount; pageIndex++) {
                runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                        "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                        new Object[]{"下一页"})));
                assertEquals(pageIndex, task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
                assertEquals(initialStep, task.currentRepairStepNumber());
                waitForConversationPage(activity, instrumentation, (pageIndex + 1) + " / ");
                JSONObject page = assertConversationLayout(activity, instrumentation);
                String expectedPage = task.conversationPage(
                        pageIndex, CONVERSATION_PAGE_SIZE);
                assertEquals(visibleText(expectedPage),
                        visibleText(page.getString("text")));
                displayedReply.append(visibleText(page.getString("text")));
                expectedReply.append(visibleText(expectedPage));
                captureScreen(instrumentation, activity,
                        String.format("02-long-reply-page-%d", pageIndex + 1));
            }
            assertEquals(expectedReply.toString(), displayedReply.toString());

            runOnMain(instrumentation, () -> {
                task.replaceRepairSteps(new String[]{
                        "核对主电源、控制电源和急停回路",
                        "拍摄并记录变频器完整故障码",
                        "测量控制端子电压并检查联锁输入"
                });
                invoke(activity, "startHudGuidance");
            });
            int pageBeforeGuidanceCommand = task.conversationPageIndex(CONVERSATION_PAGE_SIZE);
            int stepBeforeGuidanceCommand = task.currentRepairStepNumber();
            runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                    "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                    new Object[]{"下一页"})));
            assertEquals(pageBeforeGuidanceCommand,
                    task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
            assertEquals(stepBeforeGuidanceCommand, task.currentRepairStepNumber());

            runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                    "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                    new Object[]{"下一步"})));
            assertEquals(pageBeforeGuidanceCommand,
                    task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
            assertEquals(stepBeforeGuidanceCommand + 1, task.currentRepairStepNumber());
            JSONObject guidance = assertConversationLayout(activity, instrumentation);
            assertTrue(guidance.getString("text").contains("拍摄并记录变频器完整故障码"));
            captureScreen(instrumentation, activity, "03-repair-step-2");

            writeResult(activity,
                    "pages=" + task.conversationPageCount(CONVERSATION_PAGE_SIZE) + "\n"
                            + "pageCommandIndex=" + pageBeforeGuidanceCommand + "\n"
                            + "repairStep=" + task.currentRepairStepNumber() + "\n"
                            + "modelBaseline=qwen3-vl-plus/fun-asr-realtime\n"
                            + "voiceprint=s1aa729d0\n");
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static JSONObject assertConversationLayout(
            MainActivity activity,
            Instrumentation instrumentation
    ) throws Exception {
        JSONObject value = evaluateJson(activity, instrumentation,
                "(function(){"
                        + "var answer=document.querySelector('#conversation .answer');"
                        + "var pager=document.querySelector('#conversation .conversation-pager');"
                        + "var actions=document.querySelector('#conversation .conversation-actions');"
                        + "var ar=answer.getBoundingClientRect();"
                        + "var pr=pager.getBoundingClientRect();"
                        + "var cr=actions.getBoundingClientRect();"
                        + "return JSON.stringify({"
                        + "active:document.querySelector('#conversation').classList.contains('active'),"
                        + "text:answer.textContent,"
                        + "page:pager.querySelector('.conversation-page').textContent,"
                        + "clientHeight:answer.clientHeight,scrollHeight:answer.scrollHeight,"
                        + "answerBottom:ar.bottom,pagerTop:pr.top,pagerBottom:pr.bottom,"
                        + "actionsTop:cr.top,actionsBottom:cr.bottom,viewport:window.innerHeight"
                        + "});})()");
        assertTrue(value.getBoolean("active"));
        assertTrue(value.getInt("scrollHeight") <= value.getInt("clientHeight") + 1);
        assertTrue(value.getDouble("answerBottom") <= value.getDouble("pagerTop") + 1.0d);
        assertTrue(value.getDouble("pagerBottom") <= value.getDouble("actionsTop") + 1.0d);
        assertTrue(value.getDouble("actionsBottom") <= value.getDouble("viewport") + 1.0d);
        return value;
    }

    private static String visibleText(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").trim();
    }

    private static void waitForConversationPage(
            MainActivity activity,
            Instrumentation instrumentation,
            String expected
    ) throws Exception {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        JSONObject last = null;
        while (System.currentTimeMillis() < deadline) {
            last = evaluateJson(activity, instrumentation,
                    "(function(){var page=document.querySelector('.conversation-page');"
                            + "var active=document.querySelector('.state.active');"
                            + "return JSON.stringify({ready:document.readyState,url:location.href,"
                            + "active:active?active.id:'',value:page?page.textContent:'',"
                            + "body:(document.body?document.body.innerText:'').slice(0,240)});})()");
            if (last.optString("value", "").startsWith(expected)) return;
            Thread.sleep(150L);
        }
        throw new AssertionError("conversation page must render " + expected + ": " + last);
    }

    private static JSONObject evaluateJson(
            MainActivity activity,
            Instrumentation instrumentation,
            String script
    ) throws Exception {
        HudWebPresentation presentation = (HudWebPresentation) field(activity, "hudPresentation");
        assertNotNull(presentation);
        WebView webView = (WebView) field(presentation, "webView");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<String>();
        instrumentation.runOnMainSync(() -> webView.evaluateJavascript(script, value -> {
            result.set(value);
            latch.countDown();
        }));
        assertTrue("HUD JavaScript evaluation timed out",
                latch.await(5, TimeUnit.SECONDS));
        String encoded = result.get();
        assertNotNull(encoded);
        String decoded = new JSONArray("[" + encoded + "]").getString(0);
        return new JSONObject(decoded);
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
            Thread.sleep(150L);
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
        waitForWebViewVisualState(activity, instrumentation);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File file = new File(activity.getExternalFilesDir(null), name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private static void waitForWebViewVisualState(
            MainActivity activity,
            Instrumentation instrumentation
    ) throws Exception {
        HudWebPresentation presentation = (HudWebPresentation) field(activity, "hudPresentation");
        assertNotNull(presentation);
        WebView webView = (WebView) field(presentation, "webView");
        CountDownLatch latch = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> webView.postVisualStateCallback(
                System.nanoTime(), new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        webView.postOnAnimation(() ->
                                webView.postOnAnimation(latch::countDown));
                    }
                }));
        assertTrue("HUD visual-state callback timed out",
                latch.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
    }

    private static void writeResult(MainActivity activity, String content) throws Exception {
        File file = new File(activity.getExternalFilesDir(null), "long-reply-navigation-result.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
