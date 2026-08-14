package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.codex.air3nativecamera.runtime.ManagedRuntimeConfiguration;
import com.codex.air3nativecamera.sync.DeviceSessionManager;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Air3 end-to-end audit for a real managed AI response spanning multiple HUD pages. */
@RunWith(AndroidJUnit4.class)
public final class ManagedOnlineLongReplyDeviceTest {
    private static final long AI_TIMEOUT_MS = 120_000L;
    private static final long UI_TIMEOUT_MS = 15_000L;
    private static final int CONVERSATION_PAGE_SIZE = 112;
    private static final String HANDOVER_PROMPT =
            "请根据以下现场事实整理一份完整的值班交接记录，不要给维修动作，不要省略信息，"
                    + "正文不少于六百个中文字符。请按自然段和清单分别说明现场现象、已确认事实、"
                    + "安全风险、已经排除的方向、仍待确认的项目、现有证据、下一班需要关注的事项和交接结论。\n"
                    + "设备为 H3C UniServer R4900 G5，现象是按下电源键后无法正常启动。"
                    + "前面板电源灯间歇闪烁，管理口仍能连通，机房供电没有中断，机柜其他设备运行正常。"
                    + "已经重新插拔外部电源线但没有改变现象，没有执行清除配置、固件升级、部件更换或强制复位。"
                    + "现场照片包含服务器前面板、双电源模块指示灯、管理口链路灯和机柜配电单元。"
                    + "当前未确认双电源输入电压、系统事件日志、iLO 告警、主板诊断码、内存和扩展卡状态。"
                    + "设备承载业务尚未恢复，任何可能破坏数据或配置的动作都必须由现场负责人二次确认。";

    @Test
    public void managedOnlineReplySpansPagesWithoutMixingPageAndStepNavigation()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation().grantRuntimePermission(
                instrumentation.getTargetContext().getPackageName(),
                Manifest.permission.RECORD_AUDIO);
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity = activity(scenario);
        try {
            ManagedRuntimeConfiguration runtime = (ManagedRuntimeConfiguration) field(
                    activity, "runtimeConfiguration");
            assertNotNull(runtime);
            assertTrue("managed backend must be provisioned", runtime.isBackendProvisioned());
            assertTrue("managed backend must use HTTPS",
                    runtime.backendBaseUrl().startsWith("https://"));

            DeviceSessionManager sessionManager = (DeviceSessionManager) field(
                    activity, "deviceSessionManager");
            assertNotNull("secure runtime must use a short-lived device session", sessionManager);
            String accessToken = sessionManager.accessToken();
            assertFalse("short-lived device session must be issued", accessToken.isEmpty());

            runOnMain(instrumentation, () -> {
                invoke(activity, "cancelForegroundVoiceListening");
                invoke(activity, "setEnvironmentAgentEnabled",
                        new Class<?>[]{boolean.class}, new Object[]{false});
                invoke(activity, "createNewProjectChat",
                        new Class<?>[]{boolean.class}, new Object[]{false});
                setField(activity, "composerTranscript", HANDOVER_PROMPT);
                invoke(activity, "sendComposerToAi");
            });

            waitForAi(instrumentation, activity);
            String reply = latestAssistantReply(activity);
            assertTrue("real online reply must be long enough for pagination: " + reply.length(),
                    reply.length() >= 360);

            TaskSession taskSession = manager(activity).active();
            assertNotNull("managed task must remain active", taskSession);
            MaintenanceTask task = taskSession.maintenanceTask();
            assertNotNull(task);
            int pageCount = task.conversationPageCount(CONVERSATION_PAGE_SIZE);
            assertTrue("real online reply must span at least three HUD pages: " + pageCount,
                    pageCount >= 3);

            String expectedPage = visibleText(task.conversationPage(
                    0, CONVERSATION_PAGE_SIZE));
            assertFalse("first task page must contain the online reply", expectedPage.isEmpty());
            waitForConversationPage(activity, instrumentation, "1 / ", expectedPage);
            int initialStep = task.currentRepairStepNumber();
            StringBuilder displayed = new StringBuilder();
            StringBuilder expected = new StringBuilder();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                if (pageIndex > 0) {
                    expectedPage = visibleText(task.conversationPage(
                            pageIndex, CONVERSATION_PAGE_SIZE));
                    runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                            "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                            new Object[]{"下一页"})));
                    waitForConversationPage(activity, instrumentation,
                            (pageIndex + 1) + " / ", expectedPage);
                }
                assertEquals(pageIndex,
                        task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
                assertEquals(initialStep, task.currentRepairStepNumber());
                JSONObject page = assertConversationLayout(activity, instrumentation);
                assertEquals(expectedPage, visibleText(page.getString("text")));
                displayed.append(visibleText(page.getString("text")));
                expected.append(expectedPage);
                captureScreen(instrumentation, activity,
                        String.format("online-long-reply-page-%02d", pageIndex + 1));
            }
            assertEquals(expected.toString(), displayed.toString());

            runOnMain(instrumentation, () -> {
                task.replaceRepairSteps(new String[]{
                        "读取双电源模块输入状态",
                        "读取管理控制器系统事件日志",
                        "记录主板诊断码"
                });
                invoke(activity, "startHudGuidance");
            });
            int pageBeforeGuidance = task.conversationPageIndex(CONVERSATION_PAGE_SIZE);
            int stepBeforeGuidance = task.currentRepairStepNumber();
            runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                    "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                    new Object[]{"下一页"})));
            assertEquals(pageBeforeGuidance,
                    task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
            assertEquals(stepBeforeGuidance, task.currentRepairStepNumber());

            runOnMain(instrumentation, () -> assertTrue((Boolean) invoke(activity,
                    "handleVoicePreviewInteraction", new Class<?>[]{String.class},
                    new Object[]{"下一步"})));
            assertEquals(pageBeforeGuidance,
                    task.conversationPageIndex(CONVERSATION_PAGE_SIZE));
            assertEquals(stepBeforeGuidance + 1, task.currentRepairStepNumber());
            JSONObject guidance = assertConversationLayout(activity, instrumentation);
            assertTrue(guidance.getString("text").contains("系统事件日志"));
            captureScreen(instrumentation, activity, "online-guidance-step-02");

            writeResult(activity,
                    "replyChars=" + reply.length() + "\n"
                            + "pages=" + pageCount + "\n"
                            + "shortSession=true\n"
                            + "taskId=" + taskSession.id() + "\n"
                            + "repairStep=" + task.currentRepairStepNumber() + "\n"
                            + "modelBaseline=qwen3-vl-plus/fun-asr-realtime\n"
                            + "voiceprint=s1aa729d0\n");
        } finally {
            runOnMain(instrumentation, () -> manager(activity).pauseActive());
            scenario.close();
        }
    }

    private static void waitForAi(Instrumentation instrumentation, MainActivity activity)
            throws Exception {
        long deadline = System.currentTimeMillis() + AI_TIMEOUT_MS;
        String state = "AI_PENDING";
        int streamingIndex = 0;
        do {
            instrumentation.waitForIdleSync();
            state = String.valueOf(field(activity, "voiceStreamState"));
            streamingIndex = ((Integer) field(activity, "streamingAssistantIndex")).intValue();
            if (!"AI_PENDING".equals(state) && streamingIndex < 0) break;
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < deadline);
        assertFalse("AI request timed out", "AI_PENDING".equals(state) || streamingIndex >= 0);
        assertTrue("AI transport error: " + field(activity, "recoverableAiError"),
                String.valueOf(field(activity, "recoverableAiError")).isEmpty());
    }

    private static String latestAssistantReply(MainActivity activity) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) field(activity, "chatMessages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            Object message = messages.get(index);
            if ("assistant".equals(String.valueOf(field(message, "role")))
                    && "text".equals(String.valueOf(field(message, "kind")))) {
                return String.valueOf(field(message, "text"));
            }
        }
        return "";
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
                        + "text:answer.textContent,page:pager.querySelector('.conversation-page').textContent,"
                        + "clientHeight:answer.clientHeight,scrollHeight:answer.scrollHeight,"
                        + "answerBottom:ar.bottom,pagerTop:pr.top,pagerBottom:pr.bottom,"
                        + "actionsTop:cr.top,actionsBottom:cr.bottom,viewport:window.innerHeight"
                        + "});})()");
        String diagnostics = value.toString();
        assertTrue("conversation must be active: " + diagnostics,
                value.getBoolean("active"));
        assertTrue("answer must fit its viewport: " + diagnostics,
                value.getInt("scrollHeight") <= value.getInt("clientHeight") + 1);
        assertTrue("answer must not overlap the pager: " + diagnostics,
                value.getDouble("answerBottom") <= value.getDouble("pagerTop") + 1.0d);
        assertTrue("pager must not overlap the actions: " + diagnostics,
                value.getDouble("pagerBottom") <= value.getDouble("actionsTop") + 1.0d);
        assertTrue("actions must remain inside the viewport: " + diagnostics,
                value.getDouble("actionsBottom") <= value.getDouble("viewport") + 1.0d);
        return value;
    }

    private static void waitForConversationPage(
            MainActivity activity,
            Instrumentation instrumentation,
            String expectedPageLabel,
            String expectedText
    ) throws Exception {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        JSONObject last = null;
        while (System.currentTimeMillis() < deadline) {
            last = evaluateJson(activity, instrumentation,
                    "(function(){var answer=document.querySelector('#conversation .answer');"
                            + "var page=document.querySelector('.conversation-page');"
                            + "var active=document.querySelector('.state.active');"
                            + "var error=document.querySelector('#errorMessage');"
                            + "return JSON.stringify({active:active?active.id:'',"
                            + "value:page?page.textContent:'',text:answer?answer.textContent:'',"
                            + "error:error?error.textContent:'',"
                            + "ready:document.readyState});})()");
            last.put("recoverableAiError", String.valueOf(field(activity, "recoverableAiError")));
            last.put("voiceStreamState", String.valueOf(field(activity, "voiceStreamState")));
            last.put("streamingAssistantIndex", field(activity, "streamingAssistantIndex"));
            if ("conversation".equals(last.optString("active", ""))
                    && last.optString("value", "").startsWith(expectedPageLabel)
                    && visibleText(last.optString("text", "")).equals(expectedText)) {
                return;
            }
            Thread.sleep(150L);
        }
        throw new AssertionError("conversation page must render " + expectedPageLabel
                + " with expected text: " + last);
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
        assertTrue("HUD JavaScript evaluation timed out", latch.await(5, TimeUnit.SECONDS));
        String encoded = result.get();
        assertNotNull(encoded);
        String decoded = new JSONArray("[" + encoded + "]").getString(0);
        return new JSONObject(decoded);
    }

    private static void captureScreen(
            Instrumentation instrumentation,
            MainActivity activity,
            String name
    ) throws Exception {
        waitForWebViewVisualState(activity, instrumentation);
        Thread.sleep(800L);
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
        WebView webView = (WebView) field(presentation, "webView");
        CountDownLatch latch = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> webView.postVisualStateCallback(
                System.nanoTime(), new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        webView.postOnAnimation(() -> webView.postOnAnimation(latch::countDown));
                    }
                }));
        assertTrue("HUD visual-state callback timed out", latch.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
    }

    private static MainActivity activity(ActivityScenario<MainActivity> scenario) {
        AtomicReference<MainActivity> result = new AtomicReference<MainActivity>();
        scenario.onActivity(result::set);
        assertNotNull(result.get());
        return result.get();
    }

    private static TaskSessionManager manager(MainActivity activity) {
        try {
            return (TaskSessionManager) field(activity, "taskSessionManager");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String visibleText(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").trim();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
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

    private static void writeResult(MainActivity activity, String content) throws Exception {
        File file = new File(activity.getExternalFilesDir(null), "online-long-reply-result.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
