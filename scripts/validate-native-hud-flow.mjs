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
    throw new Error(`native chat flow missing marker: ${message}`);
  }
}

function mustNotInclude(marker, message = marker) {
  if (code.includes(marker)) {
    throw new Error(`native chat flow keeps forbidden marker: ${message}`);
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
  "finishToggleVoiceRecording(",
  "startRealtimeAsr(",
  "feedRealtimeAsrPcm(",
  "finishRealtimeAsr(",
  "stopVoiceCaptureAfterAsrFinal()",
  "onAsrPartial(",
  "onAsrFinal(",
  "onVoiceUnclear(",
  "voiceStreamState = VoiceStreamState.AI_PENDING",
  "sendComposerToAi();",
  "请再说一次",
  "Realtime ASR partial",
  "Realtime ASR final",
  "gptStreamStartedAtMs",
  "GPT stream start",
  "GPT stream first delta latencyMs=",
  "sendComposerToAi()",
  "GeneratedConfig.APP_LABEL",
  "private static final String APP_LABEL",
  "private static final String AI_IDENTITY_RESPONSE",
  "华方智联研发的\" + APP_LABEL",
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
  "system-reserved camera key observed; not used as an app shortcut",
  "event.getAction() == KeyEvent.ACTION_DOWN && isHandledHardwareKey(event.getKeyCode())",
  "event.getAction() == KeyEvent.ACTION_UP && handleHardwareShortcut(event.getKeyCode())",
  "KeyEvent.KEYCODE_CAMERA",
  "KeyEvent.KEYCODE_FOCUS",
  "KEYCODE_DVR",
  "KeyEvent.KEYCODE_F9",
  "KeyEvent.KEYCODE_F10",
  "KeyEvent.KEYCODE_F12",
  "KeyEvent.KEYCODE_DPAD_UP",
  "KeyEvent.KEYCODE_DPAD_DOWN",
  "smoothScrollTo(0, target)",
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
  "DIRECT_ASR_ENDPOINT",
  "import android.widget.ImageView;",
  "attachmentPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER)",
  "image_preview_base64",
  "message.imagePreviewBitmap",
  "voiceStatusForDiagnostic(",
  "语音服务未连接",
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
  "APP_LABEL + \" · 当前项目",
  "assistantHomeAction(\"点我拍照\", false)",
  "assistantHomeAction(recordingVoice ? \"结束提问\" : \"点我说话\", true)",
  "voiceButton.setContentDescription(\"点我说话\")",
  "cameraButton.setContentDescription(\"点我拍照\")",
  "shouldShowHomeActions()",
  "shouldShowComposerPanel()",
  "if (shouldShowHomeActions())",
  "composerPanel.setVisibility(showComposerPanel ? View.VISIBLE : View.GONE)",
  "transcriptDraftText.setVisibility(View.GONE)",
  "!\"点我说话\".equals(composerTranscript.trim())",
  "chatScrollView.setDefaultFocusHighlightEnabled(false)",
  "button.setDefaultFocusHighlightEnabled(false)",
  "action.setDefaultFocusHighlightEnabled(false)",
  "root.requestFocus()",
  "private int liveTranscriptMessageIndex = -1;",
  "updateLiveTranscriptMessage(partial, false)",
  "updateLiveTranscriptMessage(finalText, true)",
  "hasLiveTranscriptMessage()",
  "clearLiveTranscriptMessageIfStreaming()",
  "VOICE_AUTO_STOP_SILENCE_MS",
  "VOICE_SILENCE_RMS_THRESHOLD",
  "updateVoiceSilenceAutoStop(buffer, read)",
  "finishToggleVoiceRecording(\"silence_auto_stop\")",
  "pcm16Rms(",
  "Camera preview transform view=",
  "bufferRatio",
  "照片已添加",
  "点我说话",
  "postInvalidateDelayed(48L)",
  "新建项目",
  "会话记录",
  "当前项目",
]) {
  mustInclude(marker);
}

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
  "山东华方",
]) {
  mustNotInclude(marker);
}

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

const asrFinalBody = methodBody("onAsrFinal(");
if (!asrFinalBody.includes("voiceStreamState = VoiceStreamState.AI_PENDING") ||
    !asrFinalBody.includes("stopVoiceCaptureAfterAsrFinal()") ||
    !asrFinalBody.includes("sendComposerToAi();")) {
  throw new Error("final ASR text must auto-send to GPT to reduce glasses-side operations");
}

const voiceUnclearBody = methodBody("onVoiceUnclear(");
if (!voiceUnclearBody.includes("composerTranscript = voiceStatusForDiagnostic(code)") ||
    !voiceUnclearBody.includes("voiceStatusForDiagnostic(code)")) {
  throw new Error("voice unclear state must replace listening placeholders with a short retry diagnosis");
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

const uploadImageBody = methodBody("uploadImageForChat(");
if (!uploadImageBody.includes("DIRECT_GPT_ENABLED") ||
    !uploadImageBody.includes("onBackendImageUploaded(\"local-photo\", jpegBytes)")) {
  throw new Error("direct GPT mode must keep local photos usable without backend image upload");
}

const sendComposerBody = methodBody("sendComposerToAi(");
if (!sendComposerBody.includes("effectiveImageId") ||
    !sendComposerBody.includes("latestImageMessage()") ||
    !sendComposerBody.includes("contextImage.imageId") ||
    !sendComposerBody.includes("composerImageUploadFailed") ||
    !sendComposerBody.includes("isIdentityQuestion(prompt)") ||
    !sendComposerBody.includes("appendAssistantMessage(AI_IDENTITY_RESPONSE)") ||
    !sendComposerBody.includes("appendUserImageMessage(effectiveImageId, imagePreviewBase64)")) {
  throw new Error("sending must distinguish upload failure from upload-in-progress and preserve the chat thumbnail");
}
if (sendComposerBody.includes("if (image == null)")) {
  throw new Error("voice-only conversations must not be blocked by a missing photo");
}

const hardwareShortcutBody = methodBlock("private boolean handleHardwareShortcut(");
if (!hardwareShortcutBody.includes("screenMode == ScreenMode.CAMERA") ||
    !hardwareShortcutBody.includes("captureStillImage();") ||
    !hardwareShortcutBody.includes("startToggleVoiceRecording();")) {
  throw new Error("hardware shortcuts must execute from dispatch ACTION_UP even when a child view has focus");
}

console.log("Native chat flow validation passed.");
