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
  "final String diagnosticCode;",
  "final String displayTitle;",
  "final String displayText;",
  "final String displayHint;",
  "final String[] displayPages;",
  "final int totalPages;",
  "final boolean humanEscalationSuggestion;",
  "HudResponse(JSONObject response, String fallbackSessionId, String fallbackStep)",
  "resultType = response.optString(\"resultType\", \"instruction\")",
  "feedbackCode = response.optString(\"feedbackCode\", \"\")",
  "diagnosticCode = response.optString(\"diagnosticCode\", \"\")",
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
  "diagnosticHint(hud)",
  "statusForHud(HudResponse hud)",
  "HudResponse hud = new HudResponse(response, sessionId, currentStep)",
  "applyHudResponse(hud)",
  "return \"等待 AI 指导\";",
  "AI 运维现场指导",
  "请对准需要判断的现场画面",
  "正在上传给 AI 分析现场画面",
  "AudioRecord",
  "VOICE_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION",
  "VOICE_SAMPLE_RATE_HZ = 16000",
  "VOICE_WAV_CHANNEL_COUNT = 1",
  "VOICE_WAV_BITS_PER_SAMPLE = 16",
  "writeWavHeader(",
  "voicePcmAmplitude(",
  "audio/wav",
  "data:audio/wav;base64,",
  "air3-audio-record-wav",
  "VOICE_RECORDING_MS",
  "VOICE_SILENCE_AFTER_SPEECH_MS",
  "VOICE_NO_SPEECH_TIMEOUT_MS",
  "VOICE_UPLOAD_READ_TIMEOUT_MS",
  "VOICE_AMPLITUDE_POLL_MS",
  "VOICE_RELATIVE_SILENCE_RATIO",
  "voicePeakAmplitude",
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
  "persistVoiceDiagnostics(finishedFile.length(), durationMs, \"VOICE_RECOGNITION_WAV\", stopReason)",
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
  "custom_stt_timeout",
  "official_stt_invalid_key",
  "main_provider_stt_unsupported",
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

function readJavaNumberConstant(name) {
  const match = code.match(new RegExp(`private static final (?:int|float) ${name} = ([0-9.]+)f?;`));
  if (!match) {
    throw new Error(`native HUD flow missing numeric constant: ${name}`);
  }
  return Number(match[1]);
}

function simulateVoiceVad(amplitudes) {
  const pollMs = 180;
  const minRecordingMs = 900;
  const silenceAfterSpeechMs = 1100;
  const noSpeechTimeoutMs = 3200;
  const speechThreshold = readJavaNumberConstant("VOICE_SPEECH_AMPLITUDE_THRESHOLD");
  const silenceRatio = readJavaNumberConstant("VOICE_RELATIVE_SILENCE_RATIO");
  let peakAmplitude = 0;
  let speechDetected = false;
  let lastSpeechAt = 0;
  for (let index = 0; index < amplitudes.length; index += 1) {
    const now = (index + 1) * pollMs;
    const amplitude = amplitudes[index];
    peakAmplitude = Math.max(peakAmplitude, amplitude);
    if (amplitude >= speechThreshold) {
      speechDetected = true;
      const dynamicThreshold = Math.max(speechThreshold, Math.round(peakAmplitude * silenceRatio));
      if (amplitude >= dynamicThreshold) {
        lastSpeechAt = now;
      }
    }
    const elapsedMs = now;
    const silentMs = now - lastSpeechAt;
    if (speechDetected && elapsedMs >= minRecordingMs && silentMs >= silenceAfterSpeechMs) {
      return { stopReason: "silence_detected", elapsedMs };
    }
    if (!speechDetected && elapsedMs >= noSpeechTimeoutMs) {
      return { stopReason: "no_speech_timeout", elapsedMs };
    }
  }
  return { stopReason: "max_duration", elapsedMs: 10000 };
}

const realAir3RoomNoiseAfterSpeech = [
  128, 261, 2284, 1799, 1521, 2023, 1028, 1017, 1241, 932, 945, 1608,
  1674, 1490, 1303, 819, 1146, 1096, 957, 1163, 1048, 1043, 653, 583,
  1942, 1849, 1040, 777, 861, 1346, 794, 967, 721, 1063, 1021, 1187,
  1537, 1293, 1072, 941, 569, 814, 989, 1409, 2063, 1105, 1057, 661,
  1063, 600, 920,
];
const vadReplay = simulateVoiceVad(realAir3RoomNoiseAfterSpeech);
if (vadReplay.stopReason !== "silence_detected" || vadReplay.elapsedMs > 4500) {
  throw new Error(
    `voice VAD should stop after speech before max duration, got ${vadReplay.stopReason} at ${vadReplay.elapsedMs}ms`,
  );
}

const voiceUploadReadTimeoutMs = readJavaNumberConstant("VOICE_UPLOAD_READ_TIMEOUT_MS");
if (voiceUploadReadTimeoutMs !== 65000) {
  throw new Error(`voice upload read timeout should be 65000ms, got ${voiceUploadReadTimeoutMs}ms`);
}

mustNotInclude("attachCaptureGestures(root, previewView, scrim, guideOverlay, topPanel, titleText, stepText,\n                centerPanel, resultText, hintText, statusText)", "full-screen taps must not trigger capture when HUD paging exists");
mustNotInclude("setResultText(voiceIntentLabel(intent))", "voiceIntent must not drive the V2 primary HUD result");
mustNotInclude("setHintForStep(nextStep, text)", "image responses must render structured HUD fields");
mustNotInclude("setStatus(statusForStep(nextStep))", "image responses must render status by resultType/feedbackCode");
mustNotInclude("服务器 SSH 恢复", "native HUD must not present the app as server-only");
mustNotInclude("请对准服务器本地控制台或终端窗口", "home HUD must accept any field scene");
mustNotInclude("正在上传给 AI 识别服务器控制台", "upload HUD must describe general scene analysis");
mustNotInclude("new MediaRecorder()", "Air3 STT service reliably recognizes WAV; native voice capture must not upload m4a/AAC");
mustNotInclude("MediaRecorder.OutputFormat.MPEG_4", "voice capture must not use m4a/AAC for STT");
mustNotInclude("MediaRecorder.AudioEncoder.AAC", "voice capture must not use AAC for STT");
mustNotInclude("data:audio/mp4;base64,", "voice upload must send WAV content type");

console.log("Native HUD flow validation passed.");
