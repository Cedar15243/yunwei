import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
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

console.log("Native chat flow validation passed.");
