package com.codex.air3nativecamera.ui.hud;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Hosts the approved local HUD document. Native code retains camera, voice, AI and TRTC ownership.
 */
public final class HudWebPresentation {
    public interface Actions {
        void openCapabilities();
        void capturePhoto();
        void captureVideo();
        void startVoice();
        void openDiagnosis();
        void openExpert();
        void openAbility(String route);
        void startGuidance();
        void previousConversationPage();
        void nextConversationPage();
        void previousDiagnosisPage();
        void nextDiagnosisPage();
        void sendImageOnly();
        void retryAi();
        void openVoiceGuide();
        void openGlassesTutorial();
        void openVoiceSettings();
        void hangUp();
        void goBack();
        void goHome();
        void restartTask();
        void performOperation(String action);
    }

    private final WebView webView;
    private boolean pageReady;
    private String pendingState = "standby";
    private String pendingTranscript = "";
    private String pendingAnalysisInput = "";
    private String pendingImageStatus = "未上传";
    private String pendingVoiceStatus = "等待识别";
    private String pendingResponseTitle = "";
    private String pendingResponseDetail = "";
    private String pendingConversation = "";
    private int pendingConversationPage = 1;
    private int pendingConversationPageCount = 1;
    private String pendingTaskEvidenceText = "";
    private String pendingTaskImagePreview = "";
    private String pendingTaskVoiceState = "idle";
    private String pendingTaskVoiceLabel = "";
    private int pendingResponsePage = 1;
    private int pendingResponsePageCount = 1;
    private int pendingConfidence;
    private String pendingCollabStatus = "检测中";
    private int pendingGuidanceStep = 1;
    private int pendingGuidanceStepCount = 3;
    private String pendingGuidanceAction = "";
    private String pendingErrorMessage = "";
    private String pendingErrorAction = "retryAi";
    private String pendingErrorActionLabel = "语音重试";
    private String pendingAbilityTitle = "";
    private String pendingAbilityDescription = "";
    private String pendingAbilityCommand = "";
    private String pendingOperationTag = "";
    private String pendingOperationTitle = "";
    private String pendingOperationDescription = "";
    private String[] pendingOperationItems = new String[0];
    private String pendingOperationPrimaryAction = "";
    private String pendingOperationPrimaryLabel = "";
    private String pendingOperationSecondaryAction = "";
    private String pendingOperationSecondaryLabel = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public HudWebPresentation(WebView webView, Actions actions) {
        this.webView = webView;
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new Bridge(actions), "NativeHud");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                applyPendingState();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/voice-first-hud.html");
    }

    public WebView view() {
        return webView;
    }

    public void showState(String state) {
        pendingState = safeState(state);
        execute("window.HudPresentation.showState(" + quote(pendingState) + ");");
    }

    public void setTranscript(String transcript) {
        pendingTranscript = safeText(transcript);
        execute("window.HudPresentation.setTranscript(" + quote(pendingTranscript) + ");");
    }

    public void setAnalysisInput(String input) {
        pendingAnalysisInput = safeText(input);
        execute("window.HudPresentation.setAnalysisInput(" + quote(pendingAnalysisInput) + ");");
    }

    public void setInputStatus(String imageStatus, String voiceStatus) {
        pendingImageStatus = safeText(imageStatus);
        pendingVoiceStatus = safeText(voiceStatus);
        execute("window.HudPresentation.setInputStatus(" + quote(pendingImageStatus) + ","
                + quote(pendingVoiceStatus) + ");");
    }

    public void setResponse(String title, String detail) {
        pendingResponseTitle = safeText(title);
        pendingResponseDetail = safeText(detail);
        execute("window.HudPresentation.setResponse(" + quote(pendingResponseTitle) + ","
                + quote(pendingResponseDetail) + ");");
    }

    public void setConversation(String detail) {
        pendingConversation = safeText(detail);
        execute("window.HudPresentation.setConversation(" + quote(pendingConversation) + ");");
    }

    public void setConversationPage(String detail, int page, int pageCount) {
        pendingConversation = safeText(detail);
        pendingConversationPage = Math.max(1, page);
        pendingConversationPageCount = Math.max(1, pageCount);
        execute("window.HudPresentation.setConversationPage(" + quote(pendingConversation) + ","
                + pendingConversationPage + "," + pendingConversationPageCount + ");");
    }

    public void setTaskEvidence(String text, String imagePreviewBase64) {
        String safeValue = safeText(text);
        String safePreview = safeText(imagePreviewBase64);
        if (safeValue.equals(pendingTaskEvidenceText) && safePreview.equals(pendingTaskImagePreview)) {
            return;
        }
        pendingTaskEvidenceText = safeValue;
        pendingTaskImagePreview = safePreview;
        execute("window.HudPresentation.setTaskEvidence(" + quote(pendingTaskEvidenceText) + ","
                + quote(pendingTaskImagePreview) + ");");
    }

    public void setTaskVoiceState(String state, String label) {
        pendingTaskVoiceState = "listening".equals(state) || "thinking".equals(state) ? state : "idle";
        pendingTaskVoiceLabel = safeText(label);
        execute("window.HudPresentation.setTaskVoiceState(" + quote(pendingTaskVoiceState) + ","
                + quote(pendingTaskVoiceLabel) + ");");
    }

    public void setResponsePage(String title, String detail, int page, int pageCount, int confidence) {
        pendingResponseTitle = safeText(title);
        pendingResponseDetail = safeText(detail);
        pendingResponsePage = Math.max(1, page);
        pendingResponsePageCount = Math.max(1, pageCount);
        pendingConfidence = Math.max(0, Math.min(100, confidence));
        execute("window.HudPresentation.setResponsePage(" + quote(pendingResponseTitle) + ","
                + quote(pendingResponseDetail) + "," + pendingResponsePage + ","
                + pendingResponsePageCount + "," + pendingConfidence + ");");
    }

    public void setCollabStatus(String status) {
        pendingCollabStatus = safeText(status);
        execute("window.HudPresentation.setCollabStatus(" + quote(pendingCollabStatus) + ");");
    }

    public void setGuidanceStep(int step) {
        setGuidanceStep(step, 3, "请确认当前部件状态。");
    }

    public void setGuidanceStep(int step, int total, String action) {
        pendingGuidanceStepCount = Math.max(1, total);
        pendingGuidanceStep = Math.max(1, Math.min(pendingGuidanceStepCount, step));
        pendingGuidanceAction = safeText(action);
        execute("window.HudPresentation.setGuidanceStep(" + pendingGuidanceStep + ","
                + pendingGuidanceStepCount + "," + quote(pendingGuidanceAction) + ");");
    }

    public void setError(String message) {
        pendingErrorMessage = safeText(message);
        pendingErrorAction = "retryAi";
        pendingErrorActionLabel = "语音重试";
        execute("window.HudPresentation.setError(" + quote(pendingErrorMessage) + ");");
        execute("window.HudPresentation.setErrorAction(" + quote(pendingErrorAction) + ","
                + quote(pendingErrorActionLabel) + ");");
    }

    public void setVoicePermissionError(String message) {
        pendingErrorMessage = safeText(message);
        pendingErrorAction = "openVoiceSettings";
        pendingErrorActionLabel = "打开系统设置";
        execute("window.HudPresentation.setError(" + quote(pendingErrorMessage) + ");");
        execute("window.HudPresentation.setErrorAction(" + quote(pendingErrorAction) + ","
                + quote(pendingErrorActionLabel) + ");");
    }

    public void setAbilityDetail(String title, String description, String command) {
        pendingAbilityTitle = safeText(title);
        pendingAbilityDescription = safeText(description);
        pendingAbilityCommand = safeText(command);
        execute("window.HudPresentation.setAbilityDetail(" + quote(pendingAbilityTitle) + ","
                + quote(pendingAbilityDescription) + "," + quote(pendingAbilityCommand) + ");");
    }

    public void setOperationDetail(String tag, String title, String description, String[] items,
            String primaryAction, String primaryLabel, String secondaryAction, String secondaryLabel) {
        pendingOperationTag = safeText(tag);
        pendingOperationTitle = safeText(title);
        pendingOperationDescription = safeText(description);
        pendingOperationItems = items == null ? new String[0] : items.clone();
        pendingOperationPrimaryAction = safeText(primaryAction);
        pendingOperationPrimaryLabel = safeText(primaryLabel);
        pendingOperationSecondaryAction = safeText(secondaryAction);
        pendingOperationSecondaryLabel = safeText(secondaryLabel);
        execute("window.HudPresentation.setOperationDetail(" + quote(pendingOperationTag) + ","
                + quote(pendingOperationTitle) + "," + quote(pendingOperationDescription) + ","
                + quoteArray(pendingOperationItems) + "," + quote(pendingOperationPrimaryAction) + ","
                + quote(pendingOperationPrimaryLabel) + "," + quote(pendingOperationSecondaryAction) + ","
                + quote(pendingOperationSecondaryLabel) + ");");
    }

    public void destroy() {
        pageReady = false;
        webView.removeJavascriptInterface("NativeHud");
        webView.destroy();
    }

    private void applyPendingState() {
        showState(pendingState);
        setTranscript(pendingTranscript);
        setAnalysisInput(pendingAnalysisInput);
        setInputStatus(pendingImageStatus, pendingVoiceStatus);
        setResponse(pendingResponseTitle, pendingResponseDetail);
        setConversationPage(pendingConversation, pendingConversationPage, pendingConversationPageCount);
        setTaskEvidence(pendingTaskEvidenceText, pendingTaskImagePreview);
        setTaskVoiceState(pendingTaskVoiceState, pendingTaskVoiceLabel);
        setResponsePage(pendingResponseTitle, pendingResponseDetail, pendingResponsePage,
                pendingResponsePageCount, pendingConfidence);
        setCollabStatus(pendingCollabStatus);
        setGuidanceStep(pendingGuidanceStep, pendingGuidanceStepCount, pendingGuidanceAction);
        setError(pendingErrorMessage);
        execute("window.HudPresentation.setErrorAction(" + quote(pendingErrorAction) + ","
                + quote(pendingErrorActionLabel) + ");");
        setAbilityDetail(pendingAbilityTitle, pendingAbilityDescription, pendingAbilityCommand);
        setOperationDetail(pendingOperationTag, pendingOperationTitle, pendingOperationDescription,
                pendingOperationItems, pendingOperationPrimaryAction, pendingOperationPrimaryLabel,
                pendingOperationSecondaryAction, pendingOperationSecondaryLabel);
    }

    private void execute(String script) {
        if (!pageReady) {
            return;
        }
        webView.evaluateJavascript("(function(){if(window.HudPresentation){" + script + "}})();", null);
    }

    private static String safeState(String value) {
        if ("photoDraft".equals(value) || "listening".equals(value) || "recognizing".equals(value) || "analysis".equals(value) || "diagnosis".equals(value)
                || "conversation".equals(value) || "repair".equals(value) || "complete".equals(value) || "capabilities".equals(value)
                || "error".equals(value) || "voiceGuide".equals(value)
                || "glassesGuide".equals(value)
                || "abilityDetail".equals(value)
                || "operationDetail".equals(value)
                || "inspection".equals(value) || "capture".equals(value) || "tasks".equals(value)
                || "memory".equals(value) || "knowledge".equals(value) || "agent".equals(value)) {
            return value;
        }
        return "standby";
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String quote(String value) {
        String safe = safeText(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        return "\"" + safe + "\"";
    }

    private static String quoteArray(String[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(quote(values[index]));
        }
        return result.append(']').toString();
    }

    private static final class Bridge {
        private final Actions actions;

        Bridge(Actions actions) {
            this.actions = actions;
        }

        @JavascriptInterface public void openCapabilities(String ignored) { actions.openCapabilities(); }
        @JavascriptInterface public void capturePhoto(String ignored) { actions.capturePhoto(); }
        @JavascriptInterface public void captureVideo(String ignored) { actions.captureVideo(); }
        @JavascriptInterface public void startVoice(String ignored) { actions.startVoice(); }
        @JavascriptInterface public void openDiagnosis(String ignored) { actions.openDiagnosis(); }
        @JavascriptInterface public void openExpert(String ignored) { actions.openExpert(); }
        @JavascriptInterface public void openAbility(String route) { actions.openAbility(route); }
        @JavascriptInterface public void startGuidance(String ignored) { actions.startGuidance(); }
        @JavascriptInterface public void previousConversationPage(String ignored) { actions.previousConversationPage(); }
        @JavascriptInterface public void nextConversationPage(String ignored) { actions.nextConversationPage(); }
        @JavascriptInterface public void previousDiagnosisPage(String ignored) { actions.previousDiagnosisPage(); }
        @JavascriptInterface public void nextDiagnosisPage(String ignored) { actions.nextDiagnosisPage(); }
        @JavascriptInterface public void sendImageOnly(String ignored) { actions.sendImageOnly(); }
        @JavascriptInterface public void retryAi(String ignored) { actions.retryAi(); }
        @JavascriptInterface public void openVoiceGuide(String ignored) { actions.openVoiceGuide(); }
        @JavascriptInterface public void openGlassesTutorial(String ignored) { actions.openGlassesTutorial(); }
        @JavascriptInterface public void openVoiceSettings(String ignored) { actions.openVoiceSettings(); }
        @JavascriptInterface public void hangUp(String ignored) { actions.hangUp(); }
        @JavascriptInterface public void goBack(String ignored) { actions.goBack(); }
        @JavascriptInterface public void goHome(String ignored) { actions.goHome(); }
        @JavascriptInterface public void restartTask(String ignored) { actions.restartTask(); }
        @JavascriptInterface public void performOperation(String action) { actions.performOperation(action); }
    }
}
