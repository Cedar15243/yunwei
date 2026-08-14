import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const integratedLauncherIconPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app/app/src/main/res/drawable/ic_launcher.png",
);
const integratedHudBrandIconPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app/app/src/main/assets/brand-icon.png",
);
const activityPath = path.join(
  root,
  "air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
);
const manifestPath = path.join(
  root,
  "air3-native-camera-test/app/src/main/AndroidManifest.xml",
);
const code = fs.readFileSync(activityPath, "utf8");
const manifest = fs.readFileSync(manifestPath, "utf8");

function mustInclude(marker, message = marker) {
  if (!code.includes(marker)) {
    throw new Error(`native chat flow missing marker: ${message}`);
  }
}

function mustNotInclude(marker, message = marker) {
  if (code.includes(marker)) {
    throw new Error(`native chat flow keeps forbidden marker: ${message}`);
  }
}

function mustIncludeManifest(marker, message = marker) {
  if (!manifest.includes(marker)) {
    throw new Error(`native manifest missing marker: ${message}`);
  }
}

function methodBody(name) {
  const start = code.indexOf(`private void ${name}`);
  if (start < 0) {
    throw new Error(`native chat flow missing method: ${name}`);
  }
  const next = code.indexOf("\n    private void ", start + 1);
  return code.slice(start, next < 0 ? code.length : next);
}

function methodBlock(signature) {
  const start = code.indexOf(signature);
  if (start < 0) {
    throw new Error(`native chat flow missing block: ${signature}`);
  }
  const next = code.indexOf("\n    private ", start + 1);
  return code.slice(start, next < 0 ? code.length : next);
}

for (const marker of [
  "private enum ScreenMode { CHAT, CAMERA }",
  "OPEN_CAMERA",
  "NEW_PROJECT",
  "SHOW_RECORDS",
  "private interface ChatAiClient",
  "private static final class DirectGptClient implements ChatAiClient",
  "private static final class BackendGptClient implements ChatAiClient",
  "private static final class BackendChatClient implements ChatAiClient, RealtimeAsrClient",
  "private static final class DirectAsrClient",
  "private interface RealtimeAsrClient",
  "private enum VoiceStreamState { IDLE, LISTENING, PARTIAL_READY, FINAL_READY, AI_PENDING, AI_DONE, VOICE_UNCLEAR }",
  "private static final class ChatMessage",
  "private final ArrayList<ChatMessage> chatMessages = new ArrayList<>();",
  "private static final class ChatProject",
  "private final ArrayList<ChatProject> chatProjects = new ArrayList<>();",
  "private LinearLayout projectListColumn;",
  "createNewProjectChat()",
  "switchProjectChat(",
  "persistChatProjects()",
  "restoreChatProjects()",
  "renderProjectList()",
  "private LinearLayout chatMessagesColumn;",
  "private LinearLayout composerPanel;",
  "private TextView attachmentPreviewText;",
  "private ImageView attachmentPreviewImage;",
  "private Bitmap composerImagePreviewBitmap;",
  "private TextView transcriptDraftText;",
  "private TextView voiceButton;",
  "private AudioWaveView voiceWaveView;",
  "private static final class AudioWaveView extends View",
  "renderChatScreen()",
  "renderCameraScreen()",
  "showComposerAttachment(",
  "uploadImageForChat(",
  "onBackendImageUploaded(",
  "appendUserImageMessage(",
  "latestImageMessage()",
  "appendUserTranscriptMessage(",
  "appendAssistantStreamingMessage(",
  "updateAssistantStreamingMessage(",
  "finalizeAssistantStreamingMessage(",
  "enterCameraScreen(",
  "confirmCapturedPhoto(",
  "createImagePreviewBase64(",
  "startToggleVoiceRecording()",
  "scheduleForegroundVoiceListening(",
  "shouldStartForegroundVoiceListening()",
  "foregroundAutoVoiceStartRunnable",
  "finishToggleVoiceRecording(",
  "startRealtimeAsr(",
  "feedRealtimeAsrPcm(",
  "finishRealtimeAsr(",
  "cleanupVoiceRecordThreadAsync()",
  "DingdangVoiceRecorderCleanup",
  "VOICE_RECORD_THREAD_JOIN_MS",
  "stopVoiceCaptureAfterAsrFinal()",
  "onAsrPartial(",
  "onAsrFinal(",
  "classifyVoiceCommand(",
  "handleVoiceCommand(",
  "requestVoicePhotoCapture()",
  "capturePendingVoicePhotoIfReady()",
  "pendingVoicePhotoCapture",
  "VOICE_COMMAND_PHOTO_WORDS",
  "VOICE_COMMAND_RETAKE_WORDS",
  "VOICE_COMMAND_SEND_WORDS",
  "VOICE_COMMAND_BACK_WORDS",
  "VOICE_COMMAND_SPEAK_WORDS",
  "onVoiceUnclear(",
  "voiceStreamState = VoiceStreamState.AI_PENDING",
  "sendComposerToAi();",
  "wake_prefix_required",
  "Realtime ASR partial",
  "Realtime ASR final",
  "gptStreamStartedAtMs",
  "GPT stream start",
  "GPT stream first delta latencyMs=",
  "sendComposerToAi()",
  "GeneratedConfig.APP_LABEL",
  "private static final String APP_LABEL",
  "private static final String AI_IDENTITY_RESPONSE",
  "AI_IDENTITY_RESPONSE =",
  "isIdentityQuestion(",
  "appendAssistantMessage(AI_IDENTITY_RESPONSE)",
  "backendImagesUrl()",
  "backendAsrUrl()",
  "backendDiagnoseStreamUrl()",
  "/images",
  "/asr",
  "/diagnose/stream",
  "image_id",
  "final_text",
  "text/event-stream",
  "parseSseDelta(",
  "parseBackendAsrEvent(",
  "new BackendChatClient(",
  "GeneratedConfig.DINGDANG_BACKEND_BASE_URL",
  "GeneratedConfig.DINGDANG_BACKEND_API_KEY",
  "public boolean dispatchKeyEvent(KeyEvent event)",
  "KEY_LOG_TAG = \"DingdangKey\"",
  "isConfirmKey(",
  "isCameraShortcutKey(",
  "isChatScrollKey(",
  "scrollChatByKey(",
  "isBackShortcutKey(",
  "isSendShortcutKey(",
  "isVolumeKey(",
  "isHandledHardwareKey(",
  "handleHardwareShortcut(",
  "isSystemReservedCameraKey(",
  "event.getAction() == KeyEvent.ACTION_DOWN && isHandledHardwareKey(event.getKeyCode())",
  "event.getRepeatCount() == 0",
  "handleHardwareShortcut(event.getKeyCode())",
  "event.getAction() == KeyEvent.ACTION_UP && isHandledHardwareKey(event.getKeyCode())",
  "KeyEvent.KEYCODE_CAMERA",
  "KeyEvent.KEYCODE_FOCUS",
  "KEYCODE_DVR",
  "KeyEvent.KEYCODE_F9",
  "KeyEvent.KEYCODE_F10",
  "KeyEvent.KEYCODE_F12",
  "KeyEvent.KEYCODE_DPAD_UP",
  "KeyEvent.KEYCODE_DPAD_DOWN",
  "smoothScrollTo(0, target)",
  "postChatScrollToBottomAfterLayout(requestId)",
  "ViewTreeObserver.OnGlobalLayoutListener",
  "postChatScrollToBottom(900L, requestId)",
  "KeyEvent.KEYCODE_VOLUME_UP",
  "KeyEvent.KEYCODE_VOLUME_DOWN",
  "enterCameraScreen(\"hardware-key\")",
  "createChatAiClient()",
  "new DirectGptClient(",
  "new BackendGptClient(",
  "new BackendChatClient(",
  "DIRECT_GPT_API_KEY",
  "DIRECT_GPT_BASE_URL",
  "DIRECT_GPT_MODEL",
  "DIRECT_GPT_REASONING_EFFORT",
  "reasoning_effort",
  "DIRECT_ASR_ENDPOINT",
  "import android.widget.ImageView;",
  "attachmentPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER)",
  "image_preview_base64",
  "message.imagePreviewBitmap",
  "voiceStatusForDiagnostic(",
  "voiceStatusForDiagnostic(",
  "asr_endpoint_missing",
  "composerImageUploadFailed",
  "ExifInterface",
  "scaleBitmapToMaxEdge(",
  "root.setBackgroundColor(Color.WHITE)",
  "private LinearLayout projectRail;",
  "private LinearLayout.LayoutParams projectRailParams;",
  "setProjectRailVisible(",
  "isProjectRailVisible()",
  "AudioWaveView",
  "activeProject().title",
  "cameraButton = assistantHomeAction(",
  "TextView speak = assistantHomeAction(recordingVoice ?",
  "voiceButton.setContentDescription(",
  "cameraButton.setContentDescription(",
  "shouldShowHomeActions()",
  "shouldShowComposerPanel()",
  "if (shouldShowHomeActions())",
  "HOME_WELCOME_MESSAGE",
  "migrateHomeWelcomeMessages()",
  "isLegacyHomeWelcomeMessage(",
  "composerPanel.setVisibility(showComposerPanel ? View.VISIBLE : View.GONE)",
  "transcriptDraftText.setVisibility(View.GONE)",
  "composerTranscript.trim().length() > 0",
  "chatScrollView.setDefaultFocusHighlightEnabled(false)",
  "button.setDefaultFocusHighlightEnabled(false)",
  "action.setDefaultFocusHighlightEnabled(false)",
  "root.requestFocus()",
  "private int liveTranscriptMessageIndex = -1;",
  "updateLiveTranscriptDraft(partial)",
  "updateLiveTranscriptMessage(finalText, true)",
  "hasLiveTranscriptMessage()",
  "clearLiveTranscriptMessageIfStreaming()",
  "sanitizeTranscriptForDisplay(",
  "looksLikeAsrProtocolJson(",
  "hasAsrProtocolKeys(",
  "extractTranscriptFromJson(",
  "migrateProtocolAsrMessages()",
  "json.has(\"sentence_id\")",
  "json.has(\"channel_id\")",
  "json.has(\"sentence_end\")",
  "json.has(\"words\")",
  "VOICE_AUTO_STOP_SILENCE_MS",
  "VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS",
  "VOICE_SILENCE_RMS_THRESHOLD",
  "updateVoiceSilenceAutoStop(buffer, read)",
  "finishToggleVoiceRecording(\"silence_auto_stop\")",
  "scheduleTranscriptStableAutoStop(partial)",
  "finishToggleVoiceRecording(\"transcript_stable_auto_stop\")",
  "pcm16Rms(",
  "Camera preview transform view=",
  "bufferRatio",
  "composerImagePreviewBase64",
  "voiceButton.setText(",
  "VOICE_AUTO_STOP_MIN_RECORDING_MS = 1800L",
  "VOICE_AUTO_STOP_SILENCE_MS = 1500L",
  "VOICE_AUTO_STOP_TRANSCRIPT_STABLE_MS = 1800L",
  "postInvalidateDelayed(160L)",
  "android.content.Intent",
  "protected void onNewIntent(Intent intent)",
  "CHAT_STREAM_RENDER_INTERVAL_MS = 260L",
  "renderChatStreamMessagesOnly()",
  "scheduleChatStreamRender()",
  "flushPendingChatStreamRender()",
  "cancelPendingChatStreamRender()",
  "mainHandler.postDelayed(chatStreamRenderRunnable, delayMs)",
  "index + 24",
  "WEBSITE_RECOVERY_DEMO_AI_GUARD",
  "WEBSITE_RECOVERY_VOICE_KEYWORDS",
  "WEBSITE_RECOVERY_OCR_KEYWORDS",
  "WEBSITE_RECOVERY_DEMO_URL",
  "http://bb.chinacedar.top:18081/ai-ops-glasses/hf-ai-ops-glasses.html#specs",
  "HTTP ERROR 502",
  "bb.chinacedar.top",
  "systemctl is-active nginx",
  "systemctl restart nginx",
  "client_context",
  ".put(\"skill\", WEBSITE_RECOVERY_DEMO_AI_GUARD)",
  "chatAiClient.send(prompt, effectiveImageId, image",
  "新建项目",
  "会话记录",
  "当前项目",
]) {
  mustInclude(marker);
}

mustIncludeManifest('android:launchMode="singleTask"', "main activity must be singleTask to avoid duplicate Air3 launcher stacks");
mustNotInclude("sudo systemctl restart nginx", "website recovery Skill nginx restart must not use sudo");

for (const marker of [
  "private TextView evidenceText;",
  "evidencePanel",
  "evidenceForHud(",
  "renderConversationHudPage()",
  "activeHudPageIndex",
  "private static final class HudResponse",
  "applyHudResponse(",
  "sendVoiceMultipart(",
  "sendVoiceLegacyJson(",
  "directAsrClient.transcribe(voiceFile",
  "OPS_GLASSES_API_KEY",
  "DASHSCOPE_API_KEY",
  "EVENTS_ENDPOINT",
  "现场证据",
  "AI 运维现场指导",
  "assistantHomeAction(\"拍照识别\"",
  "assistantHomeAction(recordingVoice ? \"结束提问\" : \"语音提问\"",
  "cameraButton = iconButton(\"+\")",
  "voiceButton = iconButton(\"▷\")",
  "voiceButton.setText(\"▷\")",
  "voiceButton.setText(\"■\")",
  "cameraButton.setContentDescription(\"拍照上传\")",
  "照片会留在输入框",
  "已添加到输入框",
  "点一下结束录音",
  "最后点发送",
  "语音转成文字后会自动发送给 GPT",
  "山东华方",
]) {
  mustNotInclude(marker);
}

mustInclude("HOME_WELCOME_MESSAGE");

const startVoiceIndex = code.indexOf("startToggleVoiceRecording()");
const finishVoiceIndex = code.indexOf("finishToggleVoiceRecording(");
if (startVoiceIndex < 0 || finishVoiceIndex < 0 || startVoiceIndex > finishVoiceIndex) {
  throw new Error("voice interaction must be click-to-start and click-to-stop");
}

const recordThreadIndex = code.indexOf("private void startVoiceRecordThread(");
const stopVoiceIndex = code.indexOf("private void stopVoiceRecording(", recordThreadIndex);
const recordThreadBody = code.slice(recordThreadIndex, stopVoiceIndex < 0 ? code.length : stopVoiceIndex);
if (!recordThreadBody.includes("feedRealtimeAsrPcm(buffer, read)") ||
    !code.includes("onAsrPartial(")) {
  throw new Error("voice recording must feed PCM chunks into realtime ASR and show partial text while speaking");
}
const startVoiceBody = methodBlock("private void startToggleVoiceRecording(");
if (!startVoiceBody.includes("renderComposer();") || startVoiceBody.includes("renderChatScreen();")) {
  throw new Error("starting voice recording must update only the composer instead of rebuilding the full chat screen");
}

const onResumeBody = methodBlock("protected void onResume(");
if (!onResumeBody.includes("isForegroundWakeListeningEnabled()") ||
    !onResumeBody.includes("scheduleForegroundVoiceListening(\"resume\")")) {
  throw new Error("foreground wake listening must be gated by package-specific runtime policy");
}
const foregroundVoiceBody = methodBlock("private void scheduleForegroundVoiceListening(");
if (!foregroundVoiceBody.includes("shouldStartForegroundVoiceListening()") ||
    !foregroundVoiceBody.includes("startToggleVoiceRecording();") ||
    !foregroundVoiceBody.includes("postDelayed(foregroundAutoVoiceStartRunnable") ||
    !foregroundVoiceBody.includes("if (!isForegroundWakeListeningEnabled())") ||
    !foregroundVoiceBody.includes("voiceAutoListenArmed = true;") ||
    !foregroundVoiceBody.includes("voiceAutoListenArmed = false;") ||
    !code.includes("autoWindowFinal && !hasDingdangWakePrefix(finalText)")) {
  throw new Error("foreground voice listening must be delayed, gated, strict-wake-prefixed, and start the existing recorder path");
}
const foregroundWakePolicyBody = methodBlock("private static boolean isForegroundWakeListeningEnabled(");
if (!foregroundWakePolicyBody.includes('"com.codex.air3nativecamera.dingdangmanager.butler".equals(APP_ID)') ||
    foregroundWakePolicyBody.includes('"com.codex.air3nativecamera.dingdangmanager".equals(APP_ID)')) {
  throw new Error("foreground wake listening must be enabled only for the butler package, never for the manager package");
}
const finalizeAssistantBody = methodBody("finalizeAssistantStreamingMessage(");
if (!finalizeAssistantBody.includes("cancelForegroundVoiceListening();") ||
    finalizeAssistantBody.includes("scheduleForegroundVoiceListening(\"ai_complete\")")) {
  throw new Error("AI completion must not automatically restart listening and capture bystanders");
}

const stopVoiceBody = methodBody("stopVoiceRecording(");
if (!stopVoiceBody.includes("cleanupVoiceRecordThreadAsync();") || stopVoiceBody.includes(".join(")) {
  throw new Error("stopping voice recording must not join the recorder thread on the UI thread");
}

const stopVoiceAfterFinalBody = methodBody("stopVoiceCaptureAfterAsrFinal(");
if (!stopVoiceAfterFinalBody.includes("cleanupVoiceRecordThreadAsync();") || stopVoiceAfterFinalBody.includes(".join(")) {
  throw new Error("ASR final capture cleanup must not join the recorder thread on the UI thread");
}

const asrFinalBody = methodBody("onAsrFinal(");
if (!asrFinalBody.includes("voiceStreamState = VoiceStreamState.AI_PENDING") ||
    !asrFinalBody.includes("stopVoiceCaptureAfterAsrFinal()") ||
    !asrFinalBody.includes("handleVoiceCommand(effectiveFinalText)") ||
    !asrFinalBody.includes("sendComposerToAi();")) {
  throw new Error("final ASR text must route app voice commands before auto-sending to GPT");
}

const classifyVoiceCommandBody = methodBlock("private VoiceCommand classifyVoiceCommand(");
for (const marker of [
  "VOICE_COMMAND_PHOTO_WORDS",
  "VOICE_COMMAND_RETAKE_WORDS",
  "VOICE_COMMAND_SEND_WORDS",
  "VOICE_COMMAND_BACK_WORDS",
  "VOICE_COMMAND_SPEAK_WORDS",
]) {
  if (!classifyVoiceCommandBody.includes(marker)) {
    throw new Error(`voice command classifier missing marker: ${marker}`);
  }
}

const handleVoiceCommandBody = methodBlock("private boolean handleVoiceCommand(");
for (const marker of [
  "VoiceCommand.TAKE_PHOTO",
  "VoiceCommand.RETAKE_PHOTO",
  "VoiceCommand.SEND",
  "VoiceCommand.BACK_TO_CHAT",
  "VoiceCommand.START_VOICE",
  "requestVoicePhotoCapture();",
  "sendComposerToAi();",
  "renderChatScreen();",
  "startToggleVoiceRecording();",
]) {
  if (!handleVoiceCommandBody.includes(marker)) {
    throw new Error(`voice command handler missing marker: ${marker}`);
  }
}

const voicePhotoBody = methodBlock("private void requestVoicePhotoCapture(");
if (!voicePhotoBody.includes("pendingVoicePhotoCapture = true") ||
    !voicePhotoBody.includes("enterCameraScreen(\"voice-command\")") ||
    !voicePhotoBody.includes("capturePendingVoicePhotoIfReady();")) {
  throw new Error("voice photo command must enter the in-app camera and automatically capture when ready");
}
const pendingVoicePhotoBody = methodBlock("private void capturePendingVoicePhotoIfReady(");
if (!pendingVoicePhotoBody.includes("pendingVoicePhotoCapture = false") ||
    !pendingVoicePhotoBody.includes("captureStillImage();") ||
    !pendingVoicePhotoBody.includes("postDelayed(new Runnable()")) {
  throw new Error("pending voice photo capture must retry briefly until the camera session is ready");
}

const createNewProjectBody = methodBody("createNewProjectChat(");
if (!createNewProjectBody.includes("currentProjectIndex = 0;") ||
    !createNewProjectBody.includes("loadCurrentProjectMessages();") ||
    !createNewProjectBody.includes("renderChatScreen();") ||
    createNewProjectBody.includes("flushPendingChatStreamRender();")) {
  throw new Error("creating a new project must rerender the full chat screen so the top title switches to the new project");
}

const asrPartialBody = methodBody("onAsrPartial(");
if (!asrPartialBody.includes("updateLiveTranscriptDraft(partial)") ||
    asrPartialBody.includes("updateLiveTranscriptMessage(partial, false)") ||
    asrPartialBody.includes("renderChatScreen();")) {
  throw new Error("ASR partial text must update only the lightweight composer draft");
}

const voiceUnclearBody = methodBody("onVoiceUnclear(");
if (!voiceUnclearBody.includes("composerTranscript = voiceStatusForDiagnostic(code)") ||
    !voiceUnclearBody.includes("voiceStatusForDiagnostic(code)") ||
    !voiceUnclearBody.includes("stopVoiceCaptureAfterAsrFinal()") ||
    !voiceUnclearBody.includes("shouldSendDraftOnAsrFinished(code)") ||
    !voiceUnclearBody.includes("sendComposerToAi();")) {
  throw new Error("voice unclear state must replace listening placeholders with a short retry diagnosis");
}
const shouldSendDraftBody = methodBlock("private boolean shouldSendDraftOnAsrFinished(");
if (!shouldSendDraftBody.includes('"asr_task_finished".equals(safeCode)') ||
    !shouldSendDraftBody.includes("draft.length() > 0")) {
  throw new Error("manual voice drafts must be sent when ASR finishes without a final text");
}

const confirmPhotoIndex = code.indexOf("private void confirmCapturedPhoto(byte[] jpegBytes)");
const nextMethodIndex = code.indexOf("\n    private void ", confirmPhotoIndex + 1);
const confirmPhotoBody = code.slice(confirmPhotoIndex, nextMethodIndex < 0 ? code.length : nextMethodIndex);
if (!confirmPhotoBody.includes("composerImageBytes = jpegBytes") ||
    !confirmPhotoBody.includes("composerImagePreviewBase64 = createImagePreviewBase64(jpegBytes)") ||
    !confirmPhotoBody.includes("showComposerAttachment(") ||
    confirmPhotoBody.includes("sendComposerToAi()")) {
  throw new Error("camera photo must stay in the composer with a real thumbnail before sending");
}

const imageBubbleBody = methodBlock("private View imageMessageBubble(");
if (!imageBubbleBody.includes("ImageView imageView = new ImageView(this)") ||
    !imageBubbleBody.includes("message.imagePreviewBase64") ||
    !imageBubbleBody.includes("message.imagePreviewBitmap") ||
    !imageBubbleBody.includes("ImageView.ScaleType.FIT_CENTER")) {
  throw new Error("sent photo messages must render a non-stretched image thumbnail in chat");
}

const chatMessageToJsonBody = methodBlock("JSONObject toJson()");
if (!chatMessageToJsonBody.includes('json.put("image_preview_base64", "")') ||
    chatMessageToJsonBody.includes('json.put("image_preview_base64", imagePreviewBase64)')) {
  throw new Error("chat persistence must not write base64 image previews into SharedPreferences");
}

const uploadImageBody = methodBody("uploadImageForChat(");
if (!uploadImageBody.includes("DIRECT_GPT_ENABLED") ||
    !uploadImageBody.includes("onBackendImageUploaded(\"local-photo\", jpegBytes)")) {
  throw new Error("direct GPT mode must keep local photos usable without backend image upload");
}

const sendComposerBody = methodBody("sendComposerToAi(");
if (!sendComposerBody.includes("effectiveImageId") ||
    !sendComposerBody.includes("composerImageUploadFailed") ||
    !sendComposerBody.includes("isIdentityQuestion(prompt)") ||
    !sendComposerBody.includes("appendAssistantMessage(AI_IDENTITY_RESPONSE)") ||
    !sendComposerBody.includes("appendUserImageMessage(effectiveImageId, imagePreviewBase64)")) {
  throw new Error("sending must distinguish upload failure from upload-in-progress and preserve the chat thumbnail");
}
if (sendComposerBody.includes("if (image == null)")) {
  throw new Error("voice-only conversations must not be blocked by a missing photo");
}
if (!sendComposerBody.includes("DIRECT_GPT_ENABLED")) {
  throw new Error("direct GPT mode must explicitly control image id context");
}
if (!sendComposerBody.includes("ChatMessage contextImage = (!DIRECT_GPT_ENABLED && image == null) ? latestImageMessage() : null") ||
    !sendComposerBody.includes('resolvedImageId = image != null ? "local-photo" : ""')) {
  throw new Error("direct GPT no-image sends must not inherit a stale historical local-photo image id");
}

const watchdogBody = methodBody("scheduleGptRequestWatchdog(");
if (!code.includes("GPT_REQUEST_WATCHDOG_MS = 240000L")) {
  throw new Error("direct GPT watchdog must allow slow first deltas observed on Air3");
}
if (!watchdogBody.includes("GPT stream watchdog still waiting") ||
    watchdogBody.includes("finalizeAssistantStreamingMessage();")) {
  throw new Error("GPT watchdog must warn and keep the request alive instead of finalizing a late-but-valid stream");
}

if (!code.includes("streamChatCompletions(stream, callback);") ||
    code.includes("String text = parseChatText(body);\n                        streamText(text, callback);")) {
  throw new Error("direct GPT mode must consume real SSE deltas instead of waiting for the full response then simulating streaming");
}
const directGptPayloadBody = methodBlock("private JSONObject buildChatPayload(");
if (!directGptPayloadBody.includes('payload.put("stream", true)')) {
  throw new Error("direct GPT payload must request stream=true for demo responsiveness");
}
if (!directGptPayloadBody.includes('payload.put("max_tokens", 600)')) {
  throw new Error("direct GPT demo payload must cap max_tokens for faster, bounded answers");
}
const streamChatBody = methodBlock("private static void streamChatCompletions(");
if (!streamChatBody.includes("parseStreamingChatDelta(") ||
    !streamChatBody.includes("[DONE]") ||
    !streamChatBody.includes("callback.onDelta(delta)")) {
  throw new Error("direct GPT SSE parser must emit deltas as they arrive and handle [DONE]");
}
if (streamChatBody.includes("pendingText") || streamChatBody.includes("parseChatText(pendingText.toString())")) {
  throw new Error("direct GPT SSE parser must ignore empty/usage chunks instead of treating them as a fallback full response");
}
if (!code.includes("UPLOAD_MAX_IMAGE_EDGE = GeneratedConfig.FAST_UPLOAD ? 1280 : 1600")) {
  throw new Error("fast upload builds must cap image upload edge at 1280 for demo responsiveness");
}

const appendStreamingBody = methodBody("appendAssistantStreamingMessage(");
if (!appendStreamingBody.includes("renderChatStreamMessagesOnly();") ||
    appendStreamingBody.includes("renderChatScreen();")) {
  throw new Error("starting GPT streaming must not rebuild the whole chat screen");
}

const renderStreamOnlyBody = methodBody("renderChatStreamMessagesOnly(");
if (!renderStreamOnlyBody.includes("renderMessages();") ||
    !renderStreamOnlyBody.includes("scheduleChatScrollToBottom();")) {
  throw new Error("streaming chat refresh must keep the newest middle text pinned to the bottom");
}

const scheduleStreamBody = methodBody("scheduleChatStreamRender(");
if (!scheduleStreamBody.includes("renderChatStreamMessagesOnly();") ||
    scheduleStreamBody.includes("renderChatScreen();")) {
  throw new Error("GPT streaming deltas must refresh only messages to avoid ANR");
}

const finalizeStreamBody = methodBody("finalizeAssistantStreamingMessage(");
if (!finalizeStreamBody.includes("renderChatStreamMessagesOnly();")) {
  throw new Error("GPT streaming completion must refresh messages after cancelling pending stream renders");
}

const hardwareShortcutBody = methodBlock("private boolean handleHardwareShortcut(");
if (!hardwareShortcutBody.includes("screenMode == ScreenMode.CAMERA") ||
    !hardwareShortcutBody.includes("captureStillImage();") ||
    !hardwareShortcutBody.includes("startToggleVoiceRecording();")) {
  throw new Error("hardware shortcuts must execute from dispatch ACTION_DOWN even when a child view has focus");
}
const dispatchKeyBody = methodBlock("public boolean dispatchKeyEvent(");
if (!dispatchKeyBody.includes("event.getRepeatCount() == 0") ||
    !dispatchKeyBody.includes("handleHardwareShortcut(event.getKeyCode())") ||
    !dispatchKeyBody.includes("event.getAction() == KeyEvent.ACTION_UP && isHandledHardwareKey(event.getKeyCode())")) {
  throw new Error("hardware shortcuts must fire on initial key down and consume key up without duplicate actions");
}
const cameraShortcutBody = methodBlock("private boolean isCameraShortcutKey(");
if (!cameraShortcutBody.includes("isSystemReservedCameraKey(keyCode)")) {
  throw new Error("camera/DVR key events that reach the app must map to the in-app camera shortcut");
}

const integratedRoot = path.join(root, "air3-dingdang-expert-integrated-app/app/src/main");
const integratedActivity = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/MainActivity.java",
), "utf8");
const integratedHud = fs.readFileSync(path.join(integratedRoot, "assets/voice-first-hud.html"), "utf8");
const integratedHudPresentation = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/ui/hud/HudWebPresentation.java",
), "utf8");
const integratedMaintenanceTask = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/task/MaintenanceTask.java",
), "utf8");
const integratedVoiceRouter = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/voice/VoiceCommandRouter.java",
), "utf8");
const integratedWakeEngine = fs.readFileSync(path.join(
  root,
  "air3-dingdang-expert-integrated-app/app/src/offlineWake/java/com/codex/air3nativecamera/voice/IflytekWakeWordEngine.java",
), "utf8");
const captureStillStart = integratedActivity.indexOf("private void captureStillImage()");
const captureStillEnd = integratedActivity.indexOf("private void startSceneVideoCapture()", captureStillStart);
const captureStillBlock = integratedActivity.slice(captureStillStart, captureStillEnd);
if (captureStillStart < 0 || captureStillEnd < 0 ||
    !captureStillBlock.includes("mainHandler.post(new Runnable()")) {
  throw new Error("camera capture completion must marshal UI updates to the main thread");
}
if (!integratedActivity.includes("relativeCameraRotationDegrees(") ||
    !integratedActivity.includes("displayAspectRatioForBuffer(") ||
    !integratedActivity.includes("chooseBestPreviewSize(sizes, fallback, cameraDimensionsSwapped())")) {
  throw new Error("Camera2 preview, capture, and evidence framing must share display-oriented dimensions");
}
if (integratedHud.includes("backdrop-filter:")) {
  throw new Error("Air3 HUD must not use backdrop-filter because its WebView continuously rerasterizes blurred layers");
}
const integratedExpertCoordinator = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/expertcollab/ExpertCollabCoordinator.java",
), "utf8");
if (integratedActivity.includes("项开发中")) {
  throw new Error("capability center must not expose stale development labels");
}
for (const staleLabel of ["技术资料预览", "维修经验预览", "Skill 编排预留"]) {
  if (integratedHud.includes(staleLabel)) {
    throw new Error(`capability center must describe implemented local workflows: ${staleLabel}`);
  }
}

for (const marker of [
  "shouldKeepEstablishedTaskSurface(hudTaskWorkspaceActive, completedResponseCount",
  "HUD_CONVERSATION_PAGE_SIZE = 112",
  "buildCurrentQuestionInstruction(prompt)",
  'register(Command.HOME, "返回首页", "回首页", "回到首页", "退回首页", "返回主页", "首页")',
]) {
  const source = marker.startsWith("register(") ? integratedVoiceRouter : integratedActivity;
  if (!source.includes(marker)) {
    throw new Error(`integrated HUD flow missing marker: ${marker}`);
  }
}

if (integratedHud.includes("localPages") || integratedHud.includes("localPageIndex")) {
  throw new Error("integrated HUD must use MaintenanceTask as its only conversation pagination source");
}
if (!integratedHud.includes("#conversation .answer{flex:1 1 0;height:auto") ||
    !integratedHud.includes("max-height:none;overflow:hidden")) {
  throw new Error("integrated HUD conversation answer must stay inside one non-scrolling viewport");
}
if (!integratedHud.includes("query.textContent=transcript")) {
  throw new Error("conversation must restore the latest operator transcript after guidance ends");
}
if (!integratedHud.includes("#capabilities.active{display:flex") ||
    !integratedHud.includes("grid-template-columns:repeat(3,minmax(0,1fr))") ||
    !integratedHud.includes("grid-template-rows:repeat(3,minmax(0,1fr))") ||
    !integratedHud.includes("#capabilities .box{overflow:hidden")) {
  throw new Error("capability center must fit all abilities in one 1080p HUD viewport");
}
if (!integratedHud.includes(".app:has(#operationDetail.active) .view{padding:42px 28px 48px}")) {
  throw new Error("local operation detail must fit one Air3 viewport without document scrolling");
}
if (!integratedHud.includes("#operationItems:has(.operation-item:nth-child(3):last-child){grid-template-columns:repeat(3,minmax(0,1fr))}")) {
  throw new Error("three-item operation details must use one row to fit the Air3 viewport");
}
if (!integratedHud.includes(".app:has(#operationDetail.active) .operation-items{grid-template-columns:repeat(2,minmax(0,1fr));grid-auto-rows:minmax(0,1fr);overflow:hidden}")) {
  throw new Error("local task details must size rows from the actual item count instead of clipping sparse results");
}
if (!integratedHud.includes('className="agent-switch"') ||
    !integratedHud.includes("switchButton.setAttribute('role','switch')") ||
    !integratedHud.includes("switchButton.setAttribute('aria-checked',String(isEnabled))") ||
    !integratedHud.includes("event.stopPropagation()")) {
  throw new Error("agent authorization must use an independent accessible switch without card click propagation");
}
if (!integratedHud.includes("#listening.active{min-height:0;height:100%;overflow:hidden;display:flex")) {
  throw new Error("first-turn listening must stay inside one Air3 viewport");
}
if (!integratedHud.includes("没有听清|说话时间太短|语音服务未连接")) {
  throw new Error("voice recovery prompts must not replace the operator's task question");
}
const capabilitySection = integratedHud.slice(
  integratedHud.indexOf('<section id="capabilities"'),
  integratedHud.indexOf("</section>", integratedHud.indexOf('<section id="capabilities"')),
);
if (!integratedHud.includes('class="menu" data-a="toggleMenu" aria-label="菜单"') ||
    !integratedHud.includes("setManagedMenuEnabled")) {
  throw new Error("hamburger menu must use a dedicated first-open second-close bridge");
}
if (!capabilitySection.includes('class="capability-shortcuts" hidden') ||
    !capabilitySection.includes('data-r="project_memory"') ||
    !capabilitySection.includes('<span>项目记忆</span>') ||
    !capabilitySection.includes('data-r="settings"') ||
    !capabilitySection.includes('<span>设置</span>')) {
  throw new Error("managed V9 menu must expose project memory and settings without replacing capability tiles");
}
const capabilityOrder = [
  "AI 故障诊断", "专家协同", "现场拍照", "短视频取证", "巡检任务",
  "维修任务", "华方知识库", "设备记忆", "AI运维技能",
];
let previousCapability = -1;
for (const title of capabilityOrder) {
  const index = capabilitySection.indexOf(`<strong>${title}</strong>`);
  if (index <= previousCapability) {
    throw new Error(`capability center order is incorrect at: ${title}`);
  }
  previousCapability = index;
}
if ((capabilitySection.match(/class="cap"/g) || []).length !== 9 ||
    integratedHud.includes("add('skill_center'") ||
    capabilitySection.includes("语音提问</strong>")) {
  throw new Error("capability center must contain the requested nine single-screen entries only");
}
if (!fs.existsSync(integratedLauncherIconPath) || !fs.existsSync(integratedHudBrandIconPath) ||
    fs.statSync(integratedLauncherIconPath).size < 10000 ||
    !integratedHud.includes('<img src="brand-icon.png" alt="">') ||
    integratedHud.includes("AR 现场副驾驶")) {
  throw new Error("standby header and Android launcher must use the supplied Dingdang brand icon");
}
if (!integratedHud.includes("#standby .core:after{") ||
    !integratedHud.includes("#standby .core:before{") ||
    !integratedHud.includes("background:#fafffd") ||
    !integratedHud.includes('<svg class="mic-icon" viewBox="0 0 24 24"') ||
    !integratedHud.includes('d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"') ||
    !integratedHud.includes("#standby .core .mic-icon{") ||
    integratedHud.includes("#standby .core .mic:before{")) {
  throw new Error("standby microphone must use one symmetric vector icon inside the approved breathing core");
}
if (!integratedHud.includes("var LOW_POWER_HUD_TICK_MS=250") ||
    !integratedHud.includes("var LOW_POWER_HUD_ACTIVE_STATES={listening:true,analysis:true}") ||
    !integratedHud.includes("setInterval(updateLowPowerHudMotion,LOW_POWER_HUD_TICK_MS)") ||
    !integratedHud.includes("document.visibilityState!=='visible'") ||
    !integratedHud.includes("if(!active||!LOW_POWER_HUD_ACTIVE_STATES[active.id])") ||
    !integratedHud.includes("new MutationObserver(syncLowPowerHudMotion)") ||
    !integratedHud.includes("animation:none!important") ||
    integratedHud.includes("root.style.setProperty('--hud-breathe-scale'") ||
    integratedHud.includes("root.style.setProperty('--hud-task-scale'") ||
    integratedHud.includes("root.style.setProperty('--hud-pulse-opacity'")) {
  throw new Error("HUD motion must stay off while idle and only update active listening or analysis feedback");
}
for (const continuousAnimation of [
  "animation:standby-core-breathe",
  "animation:lowPowerBreathe",
  "animation:lowPowerTaskBreathe",
  "animation:lowPowerPresence",
]) {
  if (integratedHud.includes(continuousAnimation)) {
    throw new Error(`Air3 HUD must not continuously rasterize: ${continuousAnimation}`);
  }
}
if (!integratedHud.includes('<section id="photoDraft"') ||
    !integratedHud.includes("照片待发送") ||
    !integratedHud.includes("照片将等待 30 秒") ||
    !integratedHud.includes("超时无输入将自动仅发送图片") ||
    !integratedHud.includes(".css-mic{") ||
    !integratedHud.includes("button.textContent=''") ||
    !integratedActivity.includes('hudPresentation.showState("photoDraft")')) {
  throw new Error("captured photos need a single-screen draft, automatic fallback, and centered CSS microphone");
}
if (!integratedHud.includes('<section id="voiceGuide"') ||
    !integratedHud.includes("先说“小叮当”") ||
    !integratedHud.includes("听到提示音后再说命令") ||
    !integratedHud.includes("返回首页") ||
    !integratedHud.includes("眼镜使用教学") ||
    !integratedHud.includes('class="learning-links"') ||
    !integratedHud.includes('<b>语音帮助</b><small>唤醒后说“帮助”</small>') ||
    !integratedHud.includes('<b>眼镜使用教学</b><small>唤醒后说“眼镜教学”</small>') ||
    !integratedHud.includes("开始实训室设备巡检") ||
    !integratedHud.includes("进入第一个选项") ||
    !integratedHud.includes("结束当前任务 · 关闭当前任务并返回首页") ||
    !integratedHud.includes("以后在这个项目遇到……先……") ||
    !integratedHud.includes("确认执行 · 取消") ||
    !integratedHud.includes("启用环境诊断技能 · 停用环境诊断技能") ||
    !integratedHud.includes("['巡检与技能','巡检任务 · 进入第一个选项','开始实训室设备巡检 · AI运维技能','启用环境诊断技能 · 停用环境诊断技能'") ||
    integratedHud.includes("霍尼韦尔工单") ||
    integratedHud.includes('class="help-actions"') ||
    !integratedHud.includes("#standby.active{min-height:min(680px,calc(100vh - 188px))}") ||
    !integratedHud.includes("#standby .quick-actions{gap:42px;margin-top:27px}") ||
    !integratedHud.includes("#standby .learning-links{display:flex;width:max-content;max-width:100%;align-items:center;justify-content:center;gap:58px;margin:20px auto 0}") ||
    !integratedHud.includes('class="ability" data-a="openCapabilities"') ||
    !integratedHud.includes('class="camera" data-a="capturePhoto"') ||
    !integratedHud.includes('class="expert" data-a="openExpert"') ||
    !integratedHud.includes('data-a="openVoiceGuide"') ||
    !integratedHud.includes(".guide-grid{") ||
    !integratedHudPresentation.includes("void openVoiceGuide();") ||
    !integratedHudPresentation.includes("actions.openVoiceGuide()") ||
    !integratedHudPresentation.includes('"voiceGuide".equals(value)') ||
    !integratedVoiceRouter.includes('"打开语音帮助"') ||
    !integratedVoiceRouter.includes('"打开眼镜使用教学"') ||
    !integratedVoiceRouter.includes('"开始实训室设备巡检"') ||
    !integratedVoiceRouter.includes('"启用环境诊断"') ||
    !integratedVoiceRouter.includes('"停用环境诊断"') ||
    integratedVoiceRouter.includes('"霍尼韦尔工单"') ||
    !integratedActivity.includes("private void openHudVoiceGuide()") ||
    !integratedActivity.includes('hudPresentation.showState("voiceGuide")')) {
  throw new Error("HUD must provide a complete, discoverable voice command guide and return hints");
}
if (!integratedHud.includes('<section id="glassesGuide"') ||
    !integratedHud.includes('data-a="openGlassesTutorial"') ||
    !integratedHudPresentation.includes("void openGlassesTutorial();") ||
    !integratedHudPresentation.includes("actions.openGlassesTutorial()") ||
    !integratedHudPresentation.includes('"glassesGuide".equals(value)') ||
    !integratedVoiceRouter.includes("GLASSES_TUTORIAL") ||
    !integratedActivity.includes("private void openHudGlassesTutorial()") ||
    !integratedActivity.includes('hudPresentation.showState("glassesGuide")')) {
  throw new Error("voice help and glasses tutorial must be independent single-screen HUD states");
}
if (!integratedWakeEngine.includes("finally {") ||
    !integratedWakeEngine.includes("releaseRecorderFromAudioThread(currentRecorder)") ||
    !integratedWakeEngine.includes("completeAudioSession()") ||
    !integratedWakeEngine.includes("audioThread != null") ||
    !integratedActivity.includes("shouldWaitForWakeAudioRelease")) {
  throw new Error("offline wake audio must be fully released before ASR acquires the microphone");
}
if (!integratedWakeEngine.includes('WAKE_THRESHOLD_PARAMETER = "0 0:850"') ||
    !integratedWakeEngine.includes('wdec_param_nCmThreshold", wakeThresholdParameter()') ||
    !integratedWakeEngine.includes("Wake result handle=")) {
  throw new Error("offline wake must use the field threshold and log every AIKit wake payload");
}
const startToggleVoiceBody = integratedMethod("private void startToggleVoiceRecording(");
if (!integratedActivity.includes("OFFLINE_WAKE_COMMAND") ||
    !integratedActivity.includes("shouldRejectOfflineWakeAtStandby") ||
    !startToggleVoiceBody.includes(
      "boolean workflowVoiceInputStart = voiceSessionPurpose == VoiceSessionPurpose.WORKFLOW_INPUT",
    ) ||
    !startToggleVoiceBody.includes(
      "if (!offlineWakeCommandStart && !workflowVoiceInputStart)",
    ) ||
    !startToggleVoiceBody.includes("activateHudTaskWorkspace();")) {
  throw new Error(
    "offline wake commands and workflow voice input must not activate the ordinary task workspace",
  );
}
const wakeWriteAudio = integratedWakeEngine.slice(
  integratedWakeEngine.indexOf("private void writeAudio("),
  integratedWakeEngine.indexOf("private void notifyUnavailable(")
);
if (!integratedWakeEngine.includes("AiRequest.Builder audioRequestBuilder") ||
    wakeWriteAudio.includes("AiRequest.builder()") ||
    wakeWriteAudio.includes("AiAudio.get(")) {
  throw new Error("offline wake audio frames must reuse one AIKit builder without per-frame AiAudio holders");
}
if (!integratedExpertCoordinator.includes("waitingSurface") ||
    !integratedExpertCoordinator.includes("setWaitingSurfaceVisible") ||
    !integratedExpertCoordinator.includes("state == CollabStateMachine.State.IN_CALL")) {
  throw new Error("expert collaboration must use a light waiting surface until real video is connected");
}
if (!integratedActivity.includes("WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON")) {
  throw new Error("Air3 foreground HUD must keep the display awake during field operation");
}

function integratedMethod(signature) {
  const start = integratedActivity.indexOf(signature);
  if (start < 0) {
    throw new Error(`integrated HUD missing method: ${signature}`);
  }
  const next = integratedActivity.indexOf("\n    private ", start + 1);
  return integratedActivity.slice(start, next < 0 ? integratedActivity.length : next);
}

const integratedSync = integratedMethod("private void syncHudPresentation(");
if (!integratedActivity.includes("HudTextNormalizer.normalize(")) {
  throw new Error("integrated AI surfaces must normalize Markdown before HUD display");
}
if (!integratedMaintenanceTask.includes("HudTextNormalizer.normalize(")) {
  throw new Error("maintenance response pagination must normalize Markdown before HUD display");
}
if (integratedSync.indexOf("shouldKeepEstablishedTaskSurface(") >
    integratedSync.indexOf('showState("listening")')) {
  throw new Error("established task workspace must take priority over full-screen listening");
}
if (!integratedSync.includes("hudRestoredTaskAwaitingInput")) {
  throw new Error("an explicitly restored empty task must stay visible instead of falling back to standby");
}
if (!integratedSync.includes("任务已恢复，请继续描述现场情况。")) {
  throw new Error("a restored empty task must show a truthful continuation prompt");
}
if (!integratedHud.includes("setStandbyNotice") ||
    !integratedActivity.includes("hudPresentation.setStandbyNotice(")) {
  throw new Error("task completion must leave a visible receipt on the existing standby page");
}
const integratedManagedOperation = integratedMethod("private void performManagedExecutionOperation(");
for (const marker of [
  'managed_governance_switch_end:',
  'managed_governance_review_memory',
  'managed_governance_call_expert',
]) {
  if (!integratedManagedOperation.includes(marker)) {
    throw new Error(`task end review action is not routed: ${marker}`);
  }
}
const integratedHome = integratedMethod("private void returnToHudStandby(");
if (!integratedHome.includes("hudTaskWorkspaceActive = false") ||
    !integratedHome.includes("hudTaskMessageStartIndex = chatMessages.size()")) {
  throw new Error("global home navigation must leave the foreground task surface");
}
if (!integratedHome.includes('leaveVoiceprintSettingsIfNeeded("home")')) {
  throw new Error("integrated HUD home must stop voiceprint enrollment and reject late settings callbacks");
}
if (!integratedHome.includes("if (hudPresentation != null && screenMode == ScreenMode.CHAT)") ||
    !integratedHome.includes('hudPresentation.showState("standby")') ||
    integratedHome.indexOf('hudPresentation.showState("standby")') >
      integratedHome.indexOf("renderChatScreen();")) {
  throw new Error("HUD home navigation must show standby first without rebuilding the hidden native chat tree");
}
for (const marker of [
  "VoiceprintAsyncRequestGate",
  "voiceprintSettingsRequestGate.begin()",
  "voiceprintSettingsRequestGate.isCurrent(",
  "voiceprintSettingsRequestGate.invalidate()",
  "voiceprintSettingsRequestGate.close()",
]) {
  if (!integratedActivity.includes(marker)) {
    throw new Error(`integrated voiceprint lifecycle missing marker: ${marker}`);
  }
}
for (const marker of [
  "private void showManagedProjectCatalog()",
  "private void showManagedProjectDetail(",
  "private void performManagedProjectInstructionOperation(",
  "private void showManagedProjectInstruction(",
  "private void requestManagedProjectInstructionRevision(",
  "private void showManagedSkillCatalog()",
  "private void performManagedSkillOperation(",
  "executionContextDeviceClient.listProjects()",
  "executionContextDeviceClient.getProject(projectId)",
  "executionContextDeviceClient.listSkills(session.projectId(), session.id())",
  "requestManagedTaskRestore(projectId, taskId)",
  "private void confirmPendingManagedTaskRestore()",
  "executionContextDeviceClient.getProject(draft.localProjectId())",
  "draft.matchesFreshState(",
  "resumeTaskWorkspace(draft.localTaskId())",
  "usesLegacyLocalSkillRuntime(SECURE_RUNTIME)",
]) {
  if (!integratedActivity.includes(marker)) {
    throw new Error(`managed execution-context HUD missing marker: ${marker}`);
  }
}
for (const marker of [
  "managed_project_instruction_open:",
  "managed_project_instruction_edit:",
  "managed_project_instruction_disable:",
  "managed_project_instruction_enable:",
  "managed_project_instruction_delete:",
  "managed_project_instruction_refresh:",
]) {
  if (!integratedManagedOperation.includes(marker) &&
      !integratedActivity.includes(marker)) {
    throw new Error(`project-instruction lifecycle action is not routed: ${marker}`);
  }
}
const integratedGovernanceSubmit = integratedMethod(
  "private void executePendingProjectGovernance(",
);
if (!integratedGovernanceSubmit.includes("draft.instructionStatus()") ||
    !integratedGovernanceSubmit.includes("draft.instructionExceptions()") ||
    integratedGovernanceSubmit.includes('draft.expectedInstructionVersion(),\n                                "active"')) {
  throw new Error("project-instruction writes must submit the reviewed status and exceptions");
}
const integratedGovernanceFailure = integratedMethod(
  "private void showProjectGovernanceFailure(",
);
if (!integratedGovernanceFailure.includes("project_instruction_version_conflict") &&
    !integratedGovernanceFailure.includes("ProjectInstructionLifecyclePolicy.isVersionConflict")) {
  throw new Error("project-instruction conflicts must be recognized explicitly");
}
if (!integratedGovernanceFailure.includes("managed_project_instruction_refresh:") ||
    !integratedGovernanceFailure.includes("旧草稿已丢弃") ||
    integratedGovernanceFailure.includes("requestManagedProjectInstructionRevision(")) {
  throw new Error("project-instruction conflicts must refresh authority without replaying the stale draft");
}
const integratedGovernanceVoice = integratedMethod(
  "private boolean handleProjectGovernanceVoice(",
);
if (integratedGovernanceVoice.indexOf("managedProjectInstructionEditPending") < 0 ||
    integratedGovernanceVoice.indexOf("managedProjectInstructionEditPending") >
      integratedGovernanceVoice.indexOf("pendingManagedTaskRestoreDraft") ||
    !integratedGovernanceVoice.includes("ProjectInstructionLifecyclePolicy.classifyEditVoice") ||
    !integratedGovernanceVoice.includes("project-instruction-edit-invalid")) {
  throw new Error("project-instruction edit voice must be consumed before task or AI routing");
}
const integratedHudOperation = integratedMethod("private void performHudOperation(");
if (!integratedHudOperation.includes('value.startsWith("managed_task_restore_")')) {
  throw new Error("managed task restore confirmation actions must reach the execution-context router");
}
const integratedTaskResume = integratedMethod("private void resumeTaskWorkspace(");
if (!integratedTaskResume.includes("hudRestoredTaskAwaitingInput = true")) {
  throw new Error("explicit task restore must mark the empty task workspace as user-visible");
}
const integratedVoiceInteraction = integratedMethod("private boolean handleVoicePreviewInteraction(");
const integratedVoiceHomeIndex = integratedVoiceInteraction.indexOf("Command.HOME");
const voiceSurfaceHandlerIndexes = [
  "if (screenMode == ScreenMode.EXPERT && shouldKeepExpertSurface(command))",
  "if (command == VoiceCommandRouter.Command.CAPABILITY_CENTER)",
  "if (activeInspectionRun != null && isCapabilityCenterVisible())",
  "if (hudVoiceGuideVisible || hudGlassesGuideVisible)",
  "if (isCommandOverlayVisible())",
  "if (isCapabilityCenterVisible() && capabilityDetailVisible",
].map((marker) => integratedVoiceInteraction.indexOf(marker))
  .filter((index) => index >= 0);
if (integratedVoiceHomeIndex < 0 ||
    voiceSurfaceHandlerIndexes.some((index) => index < integratedVoiceHomeIndex)) {
  throw new Error("global voice home must take priority over capability and command overlays");
}
const integratedVoiceHome = integratedMethod("private void returnToHudHomeFromVoice(");
for (const marker of [
  "hideCommandOverlay()", "hideCapabilityCenter()", "exitExpertMode()",
  'returnToHudStandby("语音待命")', 'scheduleForegroundVoiceListening("voice-command-home")',
]) {
  if (!integratedVoiceHome.includes(marker)) {
    throw new Error(`global voice home cleanup missing marker: ${marker}`);
  }
}
const integratedGuidanceVoice = integratedMethod("private boolean handleHudGuidanceVoiceCommand(");
if (!integratedGuidanceVoice.includes("VoiceCommandRouter.Command.ABNORMAL") ||
    !integratedGuidanceVoice.includes('setChatStatus("请描述当前步骤的异常情况")') ||
    !integratedGuidanceVoice.includes("startToggleVoiceRecording();")) {
  throw new Error("repair abnormal command must stay in guidance and open a recoverable description turn");
}
const integratedCameraBack = integratedMethod("private void returnToChatFromCameraFlow(");
if (integratedCameraBack.includes("hudTaskWorkspaceActive = false") ||
    integratedCameraBack.includes("hudTaskMessageStartIndex = chatMessages.size()")) {
  throw new Error("returning from task photo capture must preserve the active task surface");
}
if (!integratedActivity.includes("finishHandledVoiceCommand(\"preview-command\")") ||
    !integratedActivity.includes("finishHandledVoiceCommand(\"legacy-command\")") ||
    !integratedActivity.includes("voiceStreamState = VoiceStreamState.IDLE;\n        scheduleForegroundVoiceListening(reason);")) {
  throw new Error("handled local voice commands must return FINAL_READY to IDLE before rearming offline wake");
}
const integratedLocalAnswer = integratedMethod("private void completeLocalAssistantTurn(");
if (!integratedLocalAnswer.includes("task.addTurn(\"AI\", response)") ||
    !integratedLocalAnswer.includes("voiceStreamState = VoiceStreamState.IDLE;") ||
    !integratedLocalAnswer.includes('scheduleForegroundVoiceListening("local-answer-complete")')) {
  throw new Error("local identity/date answers must become visible task turns and rearm offline wake");
}
const beginDiagnosisBody = integratedMethod("private void beginVoiceDiagnosisConversation(");
if (beginDiagnosisBody.indexOf("voiceSessionPurpose = VoiceSessionPurpose.COMMAND;") < 0 ||
    beginDiagnosisBody.indexOf("voiceSessionPurpose = VoiceSessionPurpose.COMMAND;") >
      beginDiagnosisBody.indexOf("mainHandler.postDelayed(")) {
  throw new Error("diagnosis capture must reserve microphone ownership before command completion can rearm offline wake");
}
const beginWakeCommandBody = integratedMethod("private void beginVoiceCommandAfterWake(");
if (beginWakeCommandBody.includes("playWakeFeedbackTone();")) {
  throw new Error("wake feedback tone must not play before offline wake has released the microphone");
}
const integratedStartVoiceBody = integratedMethod("private void startToggleVoiceRecording(");
if (integratedStartVoiceBody.indexOf("recorder.startRecording();") >
      integratedStartVoiceBody.indexOf("playWakeFeedbackTone();") ||
    !integratedStartVoiceBody.includes("if (offlineWakeCommandStart)")) {
  throw new Error("wake feedback must play only after ASR AudioRecord has started");
}
for (const latencyMarker of [
  "Voice latency stage=wake_detected",
  "Voice latency stage=wake_audio_released",
  "Voice latency stage=audio_record_started",
  "Voice latency stage=asr_started",
  "Voice latency stage=asr_first_partial",
  "Voice latency stage=asr_final",
  "voiceFlowElapsedMs=",
]) {
  if (!integratedActivity.includes(latencyMarker)) {
    throw new Error(`voice performance audit missing marker: ${latencyMarker}`);
  }
}

const integratedSceneBridge = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/skills/SceneSkillAiBridge.java",
), "utf8");
const integratedSceneSkill = fs.readFileSync(path.join(
  integratedRoot,
  "java/com/codex/air3nativecamera/skills/HoneywellTempHumiditySkill.java",
), "utf8");
for (const marker of [
  "STEP_SYSTEM_CONTEXT",
  "isCandidateTurn",
  "return true;",
  "wiring-anomaly",
  "现场系统情况",
]) {
  if (!integratedSceneSkill.includes(marker)) {
    throw new Error(`environment workflow is missing semantic-context marker: ${marker}`);
  }
}
for (const marker of [
  "scene-marker:",
  "class DetectionMarker",
  "markersJson",
  "system-context",
  "架构图、现场描述或设备照片",
]) {
  if (!integratedSceneBridge.includes(marker)) {
    throw new Error(`scene evidence protocol is missing detection-marker support: ${marker}`);
  }
}
if (!integratedActivity.includes("setTaskDetectionMarkers(latestTaskDetectionMarkers())") ||
    !integratedHudPresentation.includes("void setTaskDetectionMarkers(String markersJson)") ||
    !integratedHud.includes("task-detection-marker") ||
    !integratedHud.includes("setTaskDetectionMarkers=function")) {
  throw new Error("task HUD must render structured AI detection markers over the evidence image");
}

console.log("Native chat flow validation passed.");
