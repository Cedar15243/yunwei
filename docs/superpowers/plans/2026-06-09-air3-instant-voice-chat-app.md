# Air3 Instant Voice Chat App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Air3 app experience around instant voice conversation with GPT-5.5, using Alibaba FunASR for speech-to-text, while keeping photo capture as fast context evidence instead of the main interaction.

**Architecture:** The hot path is `voice/text -> FunASR transcript -> GPT-5.5 -> HUD response`. Database writes, context bundle persistence, event logs, and audit records must run after the HUD response is produced, using best-effort asynchronous persistence. The first implementation ships as a parallel test APK so the current delivery package remains available.

**Tech Stack:** Android Java Camera2/AudioRecord HUD, Supabase Edge Function as the low-latency API gateway, GPT-5.5-compatible OpenAI-style chat/responses endpoint, Alibaba FunASR-compatible transcription endpoint, existing PowerShell APK build/install scripts.

---

## Success Criteria

- Test package installs beside existing packages and does not overwrite `com.codex.air3nativecamera.delivery`.
- HUD first screen is conversation-first: recent user/AI turns, latest AI answer, small photo evidence area, and fixed bottom controls.
- Voice path prioritizes immediate response: no database insert/update is awaited between transcript availability and GPT-5.5 request.
- AI response path prioritizes immediate HUD: `ai_decisions`, `ops_events`, `ops_sessions`, and context/audit writes are not awaited before returning the `GlassesResponse`.
- GPT-5.5 model and Alibaba FunASR endpoint are configurable through secrets/env only.
- Validation scripts prove the fast path contract and existing native build/HUD constraints.

## Required Interface Inputs

The user will provide two API interfaces. Treat all values as secrets/config:

- GPT-5.5 solving interface: base URL, API key, model name, and whether it supports `/responses`, `/chat/completions`, or both.
- Alibaba FunASR STT interface: base URL, API key if needed, model name if needed, multipart field name, and response JSON field for transcript.

Do not hard-code keys. Use `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_VISION_MODEL` or their new aliases for GPT-5.5; use `OPENAI_TRANSCRIBE_BASE_URL`, `OPENAI_TRANSCRIBE_API_KEY`, `OPENAI_TRANSCRIBE_MODEL` for FunASR unless the provided interface requires new names.

## Task 1: Fast-Path Backend Contract

**Files:**
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `supabase/.env.example`
- Modify: `supabase/README.md`

- [ ] **Step 1: Write failing validation for instant response**

Add checks that fail while `handleVoice` and `handleSessionEvent` await audit/context writes before AI response.

Expected required markers:

```text
scheduleBestEffortAudit(
requestAiBrainDecisionFast(
const next = transcriptUnavailable ?? aiResult?.decision
return responseFromDecision(
```

Expected forbidden hot-path markers inside `handleVoice` before response:

```text
await createContextBundle(
await storeAiDecision(
await insertEvent(
await updateSession(
```

- [ ] **Step 2: Run validation and confirm RED**

Run:

```powershell
npm run validate:ai-brain
```

Expected: fails with a message saying the instant response markers are missing.

- [ ] **Step 3: Implement fast AI request**

Refactor backend so GPT request is built from an in-memory context object first. Store database context after the response:

```ts
const aiResult = await requestAiBrainDecisionFast({
  env,
  session,
  imageId,
  imageBase64,
  transcript,
  payload,
  commands,
});
const next = transcriptUnavailable ?? aiResult?.decision ?? fallbackDecision;
const response = responseFromDecision(session.id, next, extras);
scheduleBestEffortAudit(() => persistInteractionAudit(...));
return response;
```

- [ ] **Step 4: Run validation and confirm GREEN**

Run:

```powershell
npm run validate:ai-brain
```

Expected: passes.

## Task 2: GPT-5.5 and FunASR Configuration

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `supabase/.env.example`
- Modify: `supabase/README.md`

- [ ] **Step 1: Add config validation**

Require defaults/documentation for:

```text
OPENAI_BASE_URL=<gpt-5.5-compatible-base-url>
OPENAI_API_KEY=<gpt-5.5-api-key>
OPENAI_VISION_MODEL=gpt-5.5
OPENAI_TRANSCRIBE_BASE_URL=<alibaba-funasr-base-url>
OPENAI_TRANSCRIBE_API_KEY=<funasr-api-key-if-needed>
OPENAI_TRANSCRIBE_MODEL=<funasr-model-if-needed>
```

- [ ] **Step 2: Keep provider adapters generic**

Do not hard-code vendor-specific response assumptions beyond configurable transcript extraction. If the provided FunASR JSON field differs from current `text`, add a configurable parser fallback.

## Task 3: Conversation-First Native HUD

**Files:**
- Modify: `scripts/validate-native-hud-flow.mjs`
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `docs/air3-v2-version-log.md`

- [ ] **Step 1: Write failing native HUD validation**

Require these native markers:

```text
private TextView conversationText;
private TextView evidenceText;
renderConversationHudPage()
appendConversationTurn(
1  拍照更新画面
2  语音提问
3  返回 / 重拍
```

- [ ] **Step 2: Implement minimal conversation HUD**

Replace the single centered instruction panel with:

```text
left/main: recent conversation and latest AI answer
right/small: current photo evidence status
bottom: three fixed hardware actions
```

Keep current Camera2 preview and green frame. Do not remove existing capture, voice, paging, or hardware key handling.

## Task 4: Parallel Test APK

**Files:**
- Create: `scripts/build-air3-instant-chat-apk.ps1`
- Create: `scripts/install-and-verify-air3-instant-chat.ps1`
- Modify: `scripts/validate-native-build-versioning.mjs`

- [ ] **Step 1: Build identity**

Use:

```powershell
$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.instantchat"
$env:AIR3_APK_APP_LABEL = "叮当保AI-即时对话测试"
$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraInstantChat"
$env:AIR3_APK_VERSION_CODE = "501"
$env:AIR3_APK_VERSION_NAME = "5.0.1-instant-chat"
```

- [ ] **Step 2: Verify coexistence**

Expected packages:

```text
com.codex.air3nativecamera
com.codex.air3nativecamera.fast
com.codex.air3nativecamera.delivery
com.codex.air3nativecamera.instantchat
```

## Task 5: End-to-End Speed Test

**Files:**
- Modify: `scripts/install-and-verify-air3-instant-chat.ps1`
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [ ] **Step 1: Local validation**

Run:

```powershell
npm run validate:ai-brain
npm run validate:native-hud
npm run validate:native-build
npm run validate:supabase
```

- [ ] **Step 2: Air3 validation**

Run the test APK on Air3 and capture:

```text
installed package/version
first HUD screenshot/UI tree
voice request start timestamp
FunASR transcript timestamp
GPT-5.5 response timestamp
HUD response timestamp
latest photo evidence state
```

Pass condition: the app feels like direct AI conversation. Any database/audit failure must not block the HUD response.
