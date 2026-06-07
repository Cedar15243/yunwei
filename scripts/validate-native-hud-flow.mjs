import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const activityPath = path.join(
  root,
  "air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
);
const code = fs.readFileSync(activityPath, "utf8");

function mustInclude(marker, message = marker) {
  if (!code.includes(marker)) {
    throw new Error(`native HUD flow missing marker: ${message}`);
  }
}

function mustNotInclude(marker, message = marker) {
  if (code.includes(marker)) {
    throw new Error(`native HUD flow keeps forbidden marker: ${message}`);
  }
}

for (const marker of [
  "private static final class HudResponse",
  "final String rawResponse;",
  "final String sessionId;",
  "final String step;",
  "final String resultType;",
  "final String feedbackCode;",
  "final String displayTitle;",
  "final String displayText;",
  "final String displayHint;",
  "final String[] displayPages;",
  "final int totalPages;",
  "final boolean humanEscalationSuggestion;",
  "HudResponse(JSONObject response, String fallbackSessionId, String fallbackStep)",
  "resultType = response.optString(\"resultType\", \"instruction\")",
  "feedbackCode = response.optString(\"feedbackCode\", \"\")",
  "displayTitle = response.optString(\"displayTitle\", firstInstructionLine(legacyText))",
  "displayText = response.optString(\"displayText\", legacyText)",
  "displayHint = response.optString(\"displayHint\", \"\")",
  "String fullText = response.optString(\"fullText\", displayText)",
  "displayPages = parseDisplayPages(response, fullText)",
  "totalPages = displayPages.length",
  "humanEscalationSuggestion = response.optBoolean(\"humanEscalationSuggestion\", false)",
  "private HudResponse activeHud",
  "private int activeHudPageIndex",
  "advanceHudPageOrCapture(",
  "backHudPageOrRetake()",
  "hasNextHudPage() ? \"下一页\" : \"拍照中\"",
  "hasPreviousHudPage() ? \"上一页\" : \"已重拍\"",
  "private boolean hasNextHudPage()",
  "private boolean hasPreviousHudPage()",
  "renderHudPage()",
  "第 \" + (pageIndex + 1) + \"/\" + totalPages + \" 页",
  "private void applyHudResponse(final HudResponse hud)",
  "persistSession(hud.sessionId, hud.step)",
  "persistLastResponse(hud.rawResponse)",
  "private boolean restoreLastHudResponse()",
  "restoreLastHudResponse();",
  "new HudResponse(new JSONObject(lastResponse), sessionId, currentStep)",
  "private static final int HUD_PAGE_CHAR_LIMIT",
  "private static String[] paginateLocalHudText(String text)",
  "for (int index = 0; index < normalized.length(); index += HUD_PAGE_CHAR_LIMIT)",
  "stepText.setText(stepLabel(hud.step))",
  "fallbackHint(HudResponse hud)",
  "statusForHud(HudResponse hud)",
  "HudResponse hud = new HudResponse(response, sessionId, currentStep)",
  "applyHudResponse(hud)",
  "return \"等待 AI 指导\";",
  "AI 运维现场指导",
  "请对准需要判断的现场画面",
  "正在上传给 AI 分析现场画面",
  "MediaRecorder.AudioSource.VOICE_RECOGNITION",
  "VOICE_RECORDING_MS",
  "VOICE_SILENCE_AFTER_SPEECH_MS",
  "VOICE_NO_SPEECH_TIMEOUT_MS",
  "VOICE_UPLOAD_READ_TIMEOUT_MS",
  "VOICE_AMPLITUDE_POLL_MS",
  "VOICE_RELATIVE_SILENCE_RATIO",
  "voicePeakAmplitude",
  "voiceRecorder.getMaxAmplitude()",
  "voiceDynamicSilenceThreshold()",
  "stopVoiceRecording(true, \"silence_detected\")",
  "stopVoiceRecording(false, \"no_speech_timeout\")",
  "stopVoiceRecording(true, \"manual_finish\")",
  "onKeyLongPress(int keyCode, KeyEvent event)",
  "onKeyUp(int keyCode, KeyEvent event)",
  "event.startTracking()",
  "import android.view.MotionEvent;",
  "dispatchTouchEvent(MotionEvent event)",
  "isTouchInside(actionVoiceButton, event)",
  "isTouchInside(View view, MotionEvent event)",
  "setOnTouchListener(new View.OnTouchListener()",
  "MotionEvent.ACTION_DOWN",
  "handleVoiceButtonPress()",
  "Voice button manual finish",
  "Voice button dispatch touch",
  "Voice button touch action=",
  "persistVoiceDiagnostics(finishedFile.length(), durationMs, \"VOICE_RECOGNITION\", stopReason)",
  "stopReason = \"too_short\"",
  "没有听到声音",
  "录音太短",
  "请至少说满一句完整问题",
  "没有检测到有效语音",
  "语音同步失败",
  "setHintText(\"语音没有同步到 AI",
  "private void setHintText(String value)",
  "centerKeyLongPressed",
  "beginInteraction()",
  "isCurrentInteraction(generation)",
  "applyHudResponseIfCurrent(hud, generation)",
  "if (status < 200 || status >= 300)",
  "throw new IOException(\"image_upload_http_\" + status + \": \" + responseText)",
  "throw new IOException(\"voice_upload_http_\" + status + \": \" + responseText)",
  "网络连接失败",
  "暂时连接不到 AI 运维服务",
  "Skip stale captured image before UI generation=",
  "Skip stale capture completion generation=",
  "Skip stale image upload after prepare generation=",
  "payload.put(\"stopReason\"",
  "payload.put(\"sttPrompt\"",
  "connection.setReadTimeout(VOICE_UPLOAD_READ_TIMEOUT_MS)",
  "persistVoiceDiagnostics",
]) {
  mustInclude(marker);
}

mustNotInclude("attachCaptureGestures(root, previewView, scrim, guideOverlay, topPanel, titleText, stepText,\n                centerPanel, resultText, hintText, statusText)", "full-screen taps must not trigger capture when HUD paging exists");
mustNotInclude("setResultText(voiceIntentLabel(intent))", "voiceIntent must not drive the V2 primary HUD result");
mustNotInclude("setHintForStep(nextStep, text)", "image responses must render structured HUD fields");
mustNotInclude("setStatus(statusForStep(nextStep))", "image responses must render status by resultType/feedbackCode");
mustNotInclude("服务器 SSH 恢复", "native HUD must not present the app as server-only");
mustNotInclude("请对准服务器本地控制台或终端窗口", "home HUD must accept any field scene");
mustNotInclude("正在上传给 AI 识别服务器控制台", "upload HUD must describe general scene analysis");

console.log("Native HUD flow validation passed.");
