# 叮当运维AI Chat App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Android/Air3 app into “叮当运维AI”, a GPT-style field diagnosis chat app with independent camera capture, realtime Fun-ASR transcription, and GPT streaming answers.

**Architecture:** The Android app owns UI state, camera capture, audio capture, and streaming display. Supabase Edge Function acts as the low-latency gateway: image upload returns `image_id`, ASR WebSocket proxies Alibaba Fun-ASR, and diagnosis streaming calls GPT with `final_text + image_id`. Persistence and audit run after user-visible response work and must not block the hot path.

**Tech Stack:** Native Android Java Camera2/AudioRecord, Supabase Edge Functions on Deno, Supabase Storage/Postgres, Alibaba DashScope Fun-ASR realtime WebSocket, GPT-5.5-compatible OpenAI-style API, Node validation scripts, PowerShell APK build/install scripts.

---

## File Structure

- Modify: `scripts/validate-native-hud-flow.mjs`
  Owns static UI and native flow constraints. It must fail until the native app is pure chat, has an independent camera page state, and has no separate evidence panel.
- Modify: `scripts/validate-ai-brain-flow.mjs`
  Owns backend contract constraints for `/images`, `/asr`, `/diagnose/stream`, best-effort persistence, and provider separation.
- Modify: `scripts/validate-native-build-versioning.mjs`
  Owns package identity/version checks for the new parallel test APK.
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
  Owns Android UI, camera page state, audio capture, ASR streaming display, and GPT streaming display.
- Modify: `air3-native-camera-test/build-native-apk.ps1`
  Reuse existing env-driven build identity; only adjust if new resource/logo injection needs a build-time path.
- Create: `scripts/build-dingdang-ops-ai-apk.ps1`
  Builds the parallel test package.
- Create: `scripts/install-and-verify-dingdang-ops-ai.ps1`
  Installs the test APK beside existing packages and captures UI/device evidence.
- Modify: `supabase/functions/ops-glasses/index.ts`
  Adds image upload, ASR WebSocket proxy, and GPT stream endpoints while preserving existing delivery routes until explicitly removed.
- Modify: `supabase/.env.example` and `supabase/README.md`
  Documents GPT and Fun-ASR env/secrets without real keys.
- Modify: `docs/air3-v2-version-log.md`
  Records test package identity and validation status.
- Modify: `agent_memory/context.md`, `agent_memory/progress.md`, `agent_memory/bugs.md`
  Keeps current product direction, progress, and risk boundaries.

## Task 1: Static Contract Tests

**Files:**
- Modify: `scripts/validate-native-hud-flow.mjs`
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `scripts/validate-native-build-versioning.mjs`

- [ ] **Step 1: Make native validation require pure chat UI**

Add required markers:

```js
for (const marker of [
  "private enum ScreenMode { CHAT, CAMERA }",
  "private static final class ChatMessage",
  "private final ArrayList<ChatMessage> chatMessages = new ArrayList<>();",
  "renderChatScreen()",
  "renderCameraScreen()",
  "appendUserImageMessage(",
  "appendUserTranscriptMessage(",
  "appendAssistantStreamingMessage(",
  "updateAssistantStreamingMessage(",
  "finalizeAssistantStreamingMessage(",
  "enterCameraScreen(",
  "confirmCapturedPhoto(",
  "uploadImageForChat(",
  "startRealtimeAsr(",
  "sendFinalTextToGptStream(",
]) {
  mustInclude(marker);
}
```

Add forbidden markers:

```js
for (const marker of [
  "private TextView evidenceText;",
  "evidencePanel",
  "evidenceForHud(",
  "现场证据",
  "renderConversationHudPage()",
  "activeHudPageIndex",
]) {
  mustNotInclude(marker);
}
```

- [ ] **Step 2: Make backend validation require new routes**

Add route markers:

```js
for (const marker of [
  'path.match(/^\\/sessions\\/[^/]+\\/images$/)',
  'path.match(/^\\/sessions\\/[^/]+\\/asr$/)',
  'path.match(/^\\/sessions\\/[^/]+\\/diagnose\\/stream$/)',
  "handleChatImageUpload(",
  "handleRealtimeAsrSocket(",
  "handleDiagnoseStream(",
  "Deno.upgradeWebSocket(request",
  "EdgeRuntime.waitUntil(",
  "connectDashScopeFunAsr(",
  "streamGptDiagnosis(",
  "scheduleBestEffortAudit(",
]) {
  mustInclude(code, marker);
}
```

Add forbidden hot-path markers by checking the body of `handleDiagnoseStream`:

```js
for (const marker of [
  "await storeAiDecision(",
  "await insertEvent(",
  "await updateSession(",
  "await createContextBundle(",
]) {
  mustNotInclude(handleDiagnoseStreamBody, marker);
}
```

- [ ] **Step 3: Make build validation require new package identity**

Add markers:

```js
for (const marker of [
  '$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangops"',
  '$env:AIR3_APK_OUTPUT_NAME = "DingdangOpsAi"',
  '$env:AIR3_APK_VERSION_CODE = "601"',
  '$env:AIR3_APK_VERSION_NAME = "6.0.1-chat"',
  "scripts/install-and-verify-dingdang-ops-ai.ps1",
]) {
  mustInclude(dingdangBuildScript + dingdangInstallScript + versionLog, marker);
}
```

- [ ] **Step 4: Run tests and confirm RED**

Run:

```powershell
npm run validate:native-hud
npm run validate:ai-brain
npm run validate:native-build
```

Expected: all three fail because implementation markers are not complete.

## Task 2: Backend Image Upload Fast Path

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `supabase/.env.example`
- Modify: `supabase/README.md`

- [ ] **Step 1: Add route and response type**

Add route:

```ts
if (request.method === "POST" && path.match(/^\/sessions\/[^/]+\/images$/)) {
  const sessionId = path.split("/")[2];
  const payload = await request.json().catch(() => ({}));
  return json(await handleChatImageUpload({ supabase, env, sessionId, payload }));
}
```

Add response contract:

```ts
type ChatImageUploadResponse = {
  ok: true;
  session_id: string;
  image_id: string;
  image_bytes: number;
};
```

- [ ] **Step 2: Implement image upload without GPT**

Add function:

```ts
async function handleChatImageUpload(
  { supabase, env, sessionId, payload }: {
    supabase: Supabase;
    env: Env;
    sessionId: string;
    payload: Record<string, unknown>;
  },
): Promise<ChatImageUploadResponse> {
  const session = sessionId ? await mustGetSession(supabase, sessionId) : await loadOrCreateSession(supabase, env, "");
  const imageBase64 = stringOrEmpty(payload.image_base64 || payload.imageBase64);
  if (!imageBase64) {
    throw new Error("image_base64_required");
  }
  const imageBytes = estimateBase64Bytes(imageBase64);
  const image = await storeImage(supabase, {
    sessionId: String(session.id),
    imageBase64,
    imageKind: stringOr(payload.image_kind || payload.imageKind, "field_photo"),
  });
  scheduleBestEffortAudit(() =>
    insertEvent(supabase, {
      session_id: String(session.id),
      event_type: "chat_image_uploaded",
      payload: { image_id: image.id, image_bytes: imageBytes },
    })
  );
  return {
    ok: true,
    session_id: String(session.id),
    image_id: image.id,
    image_bytes: imageBytes,
  };
}
```

Implementation note: if the current `storeImage` helper has a different signature, add a small wrapper rather than rewriting storage logic.

- [ ] **Step 3: Document env behavior**

In `supabase/.env.example`, add comments:

```dotenv
# Dingdang Ops AI image upload uses existing Supabase Storage settings.
# GPT is not called from POST /sessions/:session_id/images.
```

- [ ] **Step 4: Run backend validation**

Run:

```powershell
npm run validate:ai-brain
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
```

Expected: backend route markers pass for image upload; remaining ASR/stream markers may still fail until later tasks.

## Task 3: Backend Fun-ASR Realtime Proxy

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `supabase/.env.example`
- Modify: `supabase/README.md`

- [ ] **Step 1: Add ASR env fields**

Extend `Env`:

```ts
type Env = {
  DASHSCOPE_API_KEY?: string;
  DASHSCOPE_FUNASR_URL?: string;
  DASHSCOPE_FUNASR_MODEL?: string;
};
```

Effective defaults:

```ts
const funAsrUrl = env.DASHSCOPE_FUNASR_URL || "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
const funAsrModel = env.DASHSCOPE_FUNASR_MODEL || "fun-asr-realtime";
```

- [ ] **Step 2: Add WebSocket upgrade route**

Add route before JSON routes that read the body:

```ts
if (request.method === "GET" && path.match(/^\/sessions\/[^/]+\/asr$/)) {
  const sessionId = path.split("/")[2];
  return handleRealtimeAsrSocket({ request, env, sessionId });
}
```

- [ ] **Step 3: Implement proxy skeleton**

Add function:

```ts
function handleRealtimeAsrSocket(
  { request, env, sessionId }: { request: Request; env: Env; sessionId: string },
): Response {
  if (!env.DASHSCOPE_API_KEY) {
    return json({ ok: false, error: "dashscope_api_key_missing" }, 500);
  }
  const { socket, response } = Deno.upgradeWebSocket(request);
  const socketClosed = new Promise<void>((resolve) => {
    socket.onclose = () => resolve();
  });
  EdgeRuntime.waitUntil(socketClosed);
  socket.onopen = () => {
    proxyFunAsrConversation({ client: socket, env, sessionId }).catch((error) => {
      safeSocketSend(socket, { type: "error", code: "asr_proxy_failed", message: String(error?.message ?? error) });
      socket.close();
    });
  };
  return response;
}
```

- [ ] **Step 4: Implement DashScope bridge**

Add bridge function:

```ts
async function proxyFunAsrConversation(
  { client, env, sessionId }: { client: WebSocket; env: Env; sessionId: string },
): Promise<void> {
  const upstream = new WebSocket(env.DASHSCOPE_FUNASR_URL || "wss://dashscope.aliyuncs.com/api-ws/v1/inference", [
    "token",
    env.DASHSCOPE_API_KEY || "",
  ]);
  upstream.binaryType = "arraybuffer";
  upstream.onmessage = (event) => {
    const parsed = parseDashScopeFunAsrEvent(event.data);
    if (parsed) {
      safeSocketSend(client, parsed);
    }
  };
  client.onmessage = (event) => {
    forwardClientAsrFrame({ upstream, event, sessionId, model: env.DASHSCOPE_FUNASR_MODEL || "fun-asr-realtime" });
  };
  client.onclose = () => upstream.close();
}
```

Implementation note: if DashScope authentication cannot be expressed through `new WebSocket(url, protocols)` in Deno, use a small relay endpoint or Deno-supported headers. Keep API key on the server side only.

Implementation note: keep a lifecycle promise registered with `EdgeRuntime.waitUntil(...)` until the client socket closes. Supabase considers the HTTP request acknowledged after `Deno.upgradeWebSocket(...)` returns, so an apparently idle worker can otherwise retire while the voice socket is still open.

- [ ] **Step 5: Validate**

Run:

```powershell
npm run validate:ai-brain
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
```

Expected: ASR route and WebSocket markers pass. Manual smoke uses `scripts/test-dashscope-funasr-realtime.ps1` after `DASHSCOPE_API_KEY` is supplied by env or ignored local file.

## Task 4: Backend GPT Streaming Diagnosis

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `scripts/validate-ai-brain-flow.mjs`

- [ ] **Step 1: Add stream route**

Add route:

```ts
if (request.method === "POST" && path.match(/^\/sessions\/[^/]+\/diagnose\/stream$/)) {
  const sessionId = path.split("/")[2];
  const payload = await request.json().catch(() => ({}));
  return handleDiagnoseStream({ supabase, env, sessionId, payload });
}
```

- [ ] **Step 2: Implement stream response**

Add function:

```ts
async function handleDiagnoseStream(
  { supabase, env, sessionId, payload }: {
    supabase: Supabase;
    env: Env;
    sessionId: string;
    payload: Record<string, unknown>;
  },
): Promise<Response> {
  const session = await mustGetSession(supabase, sessionId);
  const imageId = stringOrEmpty(payload.image_id || payload.imageId);
  const finalText = stringOrEmpty(payload.final_text || payload.finalText);
  if (!imageId || !finalText) {
    return sseError("missing_image_or_text", "需要现场图片和语音文字后才能诊断");
  }
  const image = await getImageById(supabase, imageId);
  const imageBase64 = await downloadImageBase64(supabase, image.file_path);
  const stream = await streamGptDiagnosis({ env, session, imageId, imageBase64, finalText });
  EdgeRuntime.waitUntil(streamCompletionPromise(stream));
  scheduleBestEffortAudit(() =>
    persistStreamAudit({ supabase, session, imageId, finalText })
  );
  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive",
    },
  });
}
```

- [ ] **Step 3: Implement provider stream adapter**

Add adapter:

```ts
async function streamGptDiagnosis(
  input: { env: Env; session: Record<string, unknown>; imageId: string; imageBase64: string; finalText: string },
): Promise<ReadableStream<Uint8Array>> {
  const prompt = aiBrainPrompt(
    fastContextFromSession(input.session, input.imageId, input.finalText, { source: "voice" }),
    {},
  );
  return callGptStreamingApi({
    env: input.env,
    prompt,
    imageUrl: ensureDataUrl(input.imageBase64, "image/jpeg"),
  });
}
```

The adapter may use `/responses` streaming or `/chat/completions` streaming depending on the provided GPT-5.5 interface. Keep the selected endpoint configurable through env, not hard-coded into Android.

- [ ] **Step 4: Validate**

Run:

```powershell
npm run validate:ai-brain
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
```

Expected: backend validation passes for image upload, ASR proxy, and GPT stream contract.

## Task 5: Native Chat UI State Model

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `scripts/validate-native-hud-flow.mjs`

- [ ] **Step 1: Add screen and message models**

Add near fields:

```java
private enum ScreenMode { CHAT, CAMERA }

private static final class ChatMessage {
    final String role;
    final String kind;
    String text;
    final String imageId;
    boolean streaming;

    ChatMessage(String role, String kind, String text, String imageId, boolean streaming) {
        this.role = role;
        this.kind = kind;
        this.text = text == null ? "" : text;
        this.imageId = imageId == null ? "" : imageId;
        this.streaming = streaming;
    }
}

private ScreenMode screenMode = ScreenMode.CHAT;
private final ArrayList<ChatMessage> chatMessages = new ArrayList<>();
private String currentImageId = "";
private TextView chatListText;
private TextView liveTranscriptText;
private TextView connectionStateText;
```

- [ ] **Step 2: Remove separate evidence panel**

Remove fields and render paths:

```java
private TextView evidenceText;
evidencePanel
evidenceForHud(...)
updateEvidenceText(...)
renderConversationHudPage()
```

All image context appears through:

```java
private void appendUserImageMessage(String imageId) {
    chatMessages.add(new ChatMessage("user", "image", "现场图片已上传", imageId, false));
    currentImageId = imageId;
    renderChatScreen();
}
```

- [ ] **Step 3: Implement chat renderer**

Add renderer:

```java
private void renderChatScreen() {
    screenMode = ScreenMode.CHAT;
    StringBuilder builder = new StringBuilder();
    if (chatMessages.isEmpty()) {
        builder.append("叮当运维AI：先拍一张现场图，我会结合画面回答你的问题。");
    }
    for (ChatMessage message : chatMessages) {
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        String prefix = "assistant".equals(message.role) ? "叮当运维AI：" : "我：";
        if ("image".equals(message.kind)) {
            builder.append("我：[现场图片] ").append(message.text);
        } else {
            builder.append(prefix).append(message.text);
        }
        if (message.streaming) {
            builder.append("▌");
        }
    }
    chatListText.setText(builder.toString());
    connectionStateText.setText(currentImageId.length() > 0 ? "已关联现场图" : "等待现场图");
}
```

- [ ] **Step 4: Run native validation**

Run:

```powershell
npm run validate:native-hud
```

Expected: still fails until camera page and streaming functions are present.

## Task 6: Native Independent Camera Page

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Add camera page transitions**

Add:

```java
private void enterCameraScreen(String source) {
    screenMode = ScreenMode.CAMERA;
    liveTranscriptText.setText("");
    connectionStateText.setText("拍照中");
    renderCameraScreen();
}

private void renderCameraScreen() {
    resultText.setText("对准现场后拍照");
    hintText.setText("拍照只上传图片，不会直接诊断。确认后回到聊天页继续语音提问。");
    statusText.setText("1 拍照    2 确认使用    3 返回聊天");
}
```

- [ ] **Step 2: Route button behavior by screen**

Update button 1:

```java
if (screenMode == ScreenMode.CHAT) {
    enterCameraScreen("button-camera");
} else {
    captureStillImage("camera-page");
}
```

Update button 3:

```java
if (screenMode == ScreenMode.CAMERA) {
    renderChatScreen();
} else {
    backChatOrExitBlocked();
}
```

- [ ] **Step 3: Upload confirmed image**

Add:

```java
private void confirmCapturedPhoto(byte[] jpegBytes) {
    connectionStateText.setText("图片上传中");
    uploadImageForChat(jpegBytes);
}
```

`uploadImageForChat` calls `/sessions/:id/images`, parses `image_id`, then calls `appendUserImageMessage(imageId)`.

- [ ] **Step 4: Validate**

Run:

```powershell
npm run validate:native-hud
```

Expected: camera markers pass; ASR/GPT streaming markers may still fail.

## Task 7: Native Realtime ASR and GPT Stream Display

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Add ASR streaming entry points**

Add:

```java
private void startRealtimeAsr() {
    if (currentImageId.length() == 0) {
        appendAssistantMessage("请先拍一张现场图，我需要结合画面判断。");
        return;
    }
    connectionStateText.setText("语音识别中");
    liveTranscriptText.setText("");
    openAsrWebSocket();
    startVoiceRecordThreadForAsr();
}
```

- [ ] **Step 2: Update partial and final transcript**

Add:

```java
private void onAsrPartial(String text) {
    liveTranscriptText.setText(text);
}

private void onAsrFinal(String text) {
    liveTranscriptText.setText("");
    appendUserTranscriptMessage(text);
    sendFinalTextToGptStream(text, currentImageId);
}
```

- [ ] **Step 3: Add GPT streaming message updates**

Add:

```java
private int streamingAssistantIndex = -1;

private void appendAssistantStreamingMessage() {
    chatMessages.add(new ChatMessage("assistant", "text", "", "", true));
    streamingAssistantIndex = chatMessages.size() - 1;
    renderChatScreen();
}

private void updateAssistantStreamingMessage(String delta) {
    if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
        ChatMessage message = chatMessages.get(streamingAssistantIndex);
        message.text = message.text + delta;
        renderChatScreen();
    }
}

private void finalizeAssistantStreamingMessage() {
    if (streamingAssistantIndex >= 0 && streamingAssistantIndex < chatMessages.size()) {
        chatMessages.get(streamingAssistantIndex).streaming = false;
    }
    streamingAssistantIndex = -1;
    connectionStateText.setText("在线");
    renderChatScreen();
}
```

- [ ] **Step 4: Validate**

Run:

```powershell
npm run validate:native-hud
```

Expected: native chat flow validation passes.

## Task 8: Parallel Test APK

**Files:**
- Create: `scripts/build-dingdang-ops-ai-apk.ps1`
- Create: `scripts/install-and-verify-dingdang-ops-ai.ps1`
- Modify: `scripts/validate-native-build-versioning.mjs`
- Modify: `docs/air3-v2-version-log.md`

- [ ] **Step 1: Add build script**

Create `scripts/build-dingdang-ops-ai-apk.ps1`:

```powershell
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangops"
$env:AIR3_APK_APP_LABEL = "叮当运维AI"
$env:AIR3_APK_OUTPUT_NAME = "DingdangOpsAi"
$env:AIR3_APK_VERSION_CODE = "601"
$env:AIR3_APK_VERSION_NAME = "6.0.1-chat"
$env:AIR3_APK_FAST_UPLOAD = "1"
& (Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1")
```

- [ ] **Step 2: Add install verification script**

Create `scripts/install-and-verify-dingdang-ops-ai.ps1` with checks for:

```powershell
[string]$Package = "com.codex.air3nativecamera.dingdangops"
[int]$ExpectedVersionCode = 601
[string]$ExpectedVersionName = "6.0.1-chat"
```

It must verify these packages can coexist:

```text
com.codex.air3nativecamera
com.codex.air3nativecamera.fast
com.codex.air3nativecamera.delivery
com.codex.air3nativecamera.dingdangops
```

- [ ] **Step 3: Validate build identity**

Run:

```powershell
npm run validate:native-build
powershell -ExecutionPolicy Bypass -File scripts\build-dingdang-ops-ai-apk.ps1
```

Expected: validation passes and `air3-native-camera-test/build/DingdangOpsAi.apk` exists.

## Task 9: End-to-End Verification

**Files:**
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: Local validation**

Run:

```powershell
npm run validate:native-hud
npm run validate:native-build
npm run validate:ai-brain
npm run validate:supabase
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
```

Expected: all pass.

- [ ] **Step 2: Install on Air3**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-dingdang-ops-ai.ps1
```

Expected:

```text
versionCode=601
versionName=6.0.1-chat
label=叮当运维AI
```

- [ ] **Step 3: Manual fast-path smoke**

Capture evidence:

```text
1. App opens to chat page.
2. Camera button enters independent camera page.
3. Confirm photo returns to chat page with image message.
4. Long press voice shows partial transcript.
5. Release voice shows final transcript.
6. GPT answer streams into AI bubble.
7. No separate evidence panel appears.
```

- [ ] **Step 4: Record results**

Update memory:

```text
agent_memory/context.md: package identity, endpoint contract, current validated architecture.
agent_memory/progress.md: commands run, device evidence paths, pass/fail state.
agent_memory/bugs.md: remaining API, WebSocket, latency, ASR accuracy, and device risks.
```

## Plan Self-Review

- Spec coverage: product name, chat UI, camera page, image_id, ASR realtime, GPT streaming, errors, brand/logo, and database deferral are covered by tasks.
- Placeholder scan: no unfinished placeholder markers are used.
- Type consistency: route names, package identity, version identity, and message method names are consistent across validation and implementation tasks.
- Risk boundary: external GPT-5.5 and DashScope credentials are config-only and are never written into APK/source.
