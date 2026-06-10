# Air3 Local Streaming ASR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a parallel experimental APK named `叮当保AI-本地语音测试` that uses local streaming ASR with VAD, shows partial text in the HUD while the user is speaking, and sends final text plus the already-uploaded image id to the main AI immediately after speech ends.

**Architecture:** Keep the formal delivery APK untouched until the local streaming path passes on Air3. First validate the official sherpa-onnx streaming ASR APK on Air3, then build a separate experimental package with a distinct `applicationId`, label, version, and artifact name. The experimental path must process `AudioRecord` PCM frames incrementally; an implementation that records a complete WAV and only transcribes after stop is rejected.

**Tech Stack:** Android Java, `AudioRecord` 16 kHz mono PCM, VAD, sherpa-onnx streaming ASR, existing Camera2 image upload and HUD code, Supabase ops-glasses AI endpoint.

---

## Non-Negotiable Success Criteria

- The experimental package installs beside `com.codex.air3nativecamera`, `com.codex.air3nativecamera.fast`, and `com.codex.air3nativecamera.delivery`.
- Experimental identity: `com.codex.air3nativecamera.localvoice`, label `叮当保AI-本地语音测试`, first version `401 / 4.0.1-local-voice`.
- While the user speaks, the HUD updates from ASR `partial result` without waiting for the utterance to end.
- After VAD detects end-of-speech, the app freezes the ASR `final result` and immediately sends `finalText + imageId/session image reference` to the main AI.
- Image upload stays decoupled: photo capture/compression/upload can happen before or during speech, and the voice final request must not re-upload the same image bytes unless no image id exists.
- Air3 evidence must include partial text, final text, logcat timing, battery temperature before/after, and installed package versions.

## Rejected Implementation

- Recording an entire WAV and then running recognition only after the user stops speaking.
- Calling remote STT for the normal experimental path.
- Sending suspicious, empty, or language-mismatched transcripts to the main AI.
- Replacing or upgrading `com.codex.air3nativecamera.delivery` during this experiment.

## Current Official APK Evidence

- Downloaded official sherpa-onnx APK: `tmp/sherpa-onnx/sherpa-onnx-1.13.1-arm64-v8a-asr-zh-small_zipformer_14M_2023_02_23.apk`.
- SHA256: `6490310531BB235F47DACCEBD6BDC7D2C9CD522EA6C97554EBCA11F9C604983C`.
- APK badging: package `com.k2fsa.sherpa.onnx`, version `20260508 / 1.13.1`, label `ASR`, launch activity `com.k2fsa.sherpa.onnx.MainActivity`, ABI `arm64-v8a`.
- Air3 `YM00FCF3NW0031` installed it successfully, retained original/fast/delivery packages, and granted `RECORD_AUDIO`.
- Evidence files `tmp/sherpa-onnx/air3-sherpa-tts-00.xml` through `tmp/sherpa-onnx/air3-sherpa-tts-08.xml` show text changing over time while recording, proving incremental/partial UI updates exist in the official streaming APK.
- Accuracy is not accepted yet: the sample contained background/nearby Chinese speech and the resulting text was noisy. A clean human short-sentence test is still required before integration.

## DashScope Fun-ASR Realtime Backend Gate

- Endpoint: `wss://dashscope.aliyuncs.com/api-ws/v1/inference`
- Header: `Authorization: Bearer <DASHSCOPE_API_KEY>`
- Model ID: `fun-asr-realtime`
- The API key must stay on our backend or in local ignored test inputs. Do not put it in the Android APK, Java source, generated config, committed docs, or committed scripts.
- Backend flow: connect WebSocket -> send `run-task` -> stream 16 kHz mono PCM/WAV audio chunks -> receive `result-generated` -> send `finish-task` -> wait for `task-finished`.
- Local backend/interface smoke script: `scripts/test-dashscope-funasr-realtime.ps1`.

---

### Task 1: Official sherpa-onnx Air3 Acceptance

**Files:**
- Create: `scripts/install-and-verify-sherpa-official.ps1`
- Evidence: `tmp/sherpa-onnx/*`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [ ] **Step 1: Run the official APK install script**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-sherpa-official.ps1
```

Expected:

```text
Installed com.k2fsa.sherpa.onnx 20260508/1.13.1
Coexistence verified with com.codex.air3nativecamera, com.codex.air3nativecamera.fast, com.codex.air3nativecamera.delivery
RECORD_AUDIO granted=true
```

- [ ] **Step 2: Run a clean human short-sentence test**

Speak near the Air3 microphone, one phrase at a time:

```text
这个是什么
下一步怎么做
这里有问题吗
```

Expected:

```text
Partial text appears within about 1 second after speech starts.
Final text stabilizes within about 1 second after speech ends.
Temperature increase remains small during a short test.
```

- [ ] **Step 3: Decide the gate**

Pass condition:

```text
At least two of the three phrases produce recognizable Chinese partial text and a usable final text.
```

Stop condition:

```text
If partial text does not update while speaking, do not integrate sherpa-onnx into our APK.
If final text is consistently wrong in quiet conditions, test a different official streaming model before integration.
```

### Task 2: Experimental Package Skeleton

**Files:**
- Modify: `air3-native-camera-test/build-native-apk.ps1`
- Create: `scripts/build-air3-local-voice-apk.ps1`
- Create: `scripts/install-and-verify-air3-local-voice.ps1`
- Modify: `docs/air3-v2-version-log.md`

- [ ] **Step 1: Build with a separate identity**

Use these build overrides:

```powershell
$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.localvoice"
$env:AIR3_APK_APP_LABEL = "叮当保AI-本地语音测试"
$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraLocalVoice"
$env:AIR3_APK_VERSION_CODE = "401"
$env:AIR3_APK_VERSION_NAME = "4.0.1-local-voice"
powershell -ExecutionPolicy Bypass -File air3-native-camera-test\build-native-apk.ps1
```

Expected:

```text
VersionCode=401 VersionName=4.0.1-local-voice
Air3NativeCameraLocalVoice.apk
```

- [ ] **Step 2: Install without replacing formal versions**

Verify:

```powershell
tmp\tools\platform-tools\adb.exe -s YM00FCF3NW0031 shell pm list packages com.codex.air3nativecamera
tmp\tools\platform-tools\adb.exe -s YM00FCF3NW0031 shell dumpsys package com.codex.air3nativecamera.localvoice
```

Expected installed package list includes:

```text
package:com.codex.air3nativecamera
package:com.codex.air3nativecamera.fast
package:com.codex.air3nativecamera.delivery
package:com.codex.air3nativecamera.localvoice
```

### Task 2.5: Backend Fun-ASR Realtime Interface Smoke

**Files:**
- Create: `scripts/test-dashscope-funasr-realtime.ps1`
- Evidence: `tmp/dashscope-funasr-realtime-result.json`
- Secret input: environment variable `DASHSCOPE_API_KEY` or ignored file `tmp/dashscope_api_key.local`

- [ ] **Step 1: Run the backend interface smoke**

Run:

```powershell
$env:DASHSCOPE_API_KEY = "<local key, do not commit>"
powershell -ExecutionPolicy Bypass -File scripts\test-dashscope-funasr-realtime.ps1 -AudioPath tmp\tts-zhegeshishenme.wav
```

Expected:

```text
DashScope Fun-ASR realtime smoke passed
Model=fun-asr-realtime
ResultGeneratedCount=<number greater than 0>
FinalText=<recognized Chinese text>
```

- [ ] **Step 2: Verify the event sequence**

Inspect:

```powershell
Get-Content tmp\dashscope-funasr-realtime-result.json -Raw
```

Required event sequence:

```text
task-started
result-generated
task-finished
```

- [ ] **Step 3: Preserve the security boundary**

Required:

```text
No DashScope API key appears in git diff, generated APK, Java source, or committed docs.
Android must call our backend; only backend connects to DashScope.
```

### Task 3: Streaming ASR Adapter

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Add native/library assets only after confirming the sherpa-onnx Android integration package and model layout.

- [ ] **Step 1: Add a streaming contract inside the app**

Required runtime states:

```text
IDLE
LISTENING
PARTIAL_READY
FINAL_READY
AI_PENDING
AI_DONE
VOICE_UNCLEAR
```

Required callbacks:

```text
onAsrPartial(String partialText)
onAsrFinal(String finalText)
onVoiceUnclear(String diagnosticCode)
```

- [ ] **Step 2: Feed PCM frames incrementally**

The existing `AudioRecord` loop must send each 16 kHz mono PCM frame to the streaming recognizer as soon as it is read. It may still save WAV evidence, but WAV saving is not the recognition path.

Expected log shape:

```text
local_asr_start sampleRate=16000
local_asr_partial elapsedMs=<number> text=<non-empty>
local_asr_final elapsedMs=<number> text=<non-empty>
```

- [ ] **Step 3: HUD partial/final behavior**

Expected HUD behavior:

```text
While speaking: show partial text with a "正在听" status.
After VAD stop: show final text with a "已听清，正在分析" status.
If final text is empty or suspicious: show local voice unclear, do not call main AI.
```

### Task 4: Image-First AI Request

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `supabase/functions/ops-glasses/index.ts` only if the current API cannot accept final text with an existing image/session reference.
- Modify: `scripts/validate-ai-brain-flow.mjs`

- [ ] **Step 1: Preserve image upload before final text**

Expected local state:

```text
latestImageSessionId=<non-empty>
latestImageUploadedAt=<timestamp>
latestFinalVoiceText=<non-empty>
```

- [ ] **Step 2: Send final text plus image reference**

Required request behavior:

```text
If image session exists: send final text to the existing session voice/text endpoint.
If no image session exists: show "请先拍照" or trigger the existing image capture path before AI analysis.
```

- [ ] **Step 3: Validate no duplicate image upload**

Expected evidence:

```text
One image upload for a photo.
One final text AI request for the voice.
No second image byte upload during final text dispatch.
```

### Task 5: Verification Gates

**Files:**
- Modify: `scripts/validate-native-hud-flow.mjs`
- Modify: `scripts/validate-native-build-versioning.mjs`
- Create or modify: `scripts/install-and-verify-air3-local-voice.ps1`
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [ ] **Step 1: Local validation**

Run:

```powershell
npm run validate:native-hud
npm run validate:native-build
npm run validate:supabase
```

Expected:

```text
All validation commands pass.
```

- [ ] **Step 2: Air3 validation**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-air3-local-voice.ps1
```

Expected:

```text
Local voice package installed.
Partial text evidence captured.
Final text evidence captured.
AI response evidence captured.
Temperature before/after captured.
```

- [ ] **Step 3: Stop before formal delivery**

Stop condition:

```text
Do not merge the local streaming ASR path into com.codex.air3nativecamera.delivery until the experimental package passes on Air3 with clean human short phrases.
```
