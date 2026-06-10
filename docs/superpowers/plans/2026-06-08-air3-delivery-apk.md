# Air3 Delivery APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify a delivery-grade Air3 APK that installs beside all previous APKs, uses the app label `叮当保AI`, centers the app icon, and hardens voice/input delivery paths.

**Architecture:** Keep the existing native Camera2 APK and Supabase Edge Function. Add delivery-specific build/install scripts, move native voice upload to multipart form data with backend JSON compatibility, and add explicit key mappings for documented Air3 hardware controls.

**Tech Stack:** Android Java, PowerShell build scripts, Supabase Edge Function TypeScript/Deno, Node validation scripts, ADB.

---

### Task 1: Delivery Build Identity And Icon

**Files:**
- Modify: `air3-native-camera-test/app/src/main/res/drawable/ic_launcher.xml`
- Create: `scripts/build-air3-delivery-apk.ps1`
- Create: `scripts/install-and-verify-air3-delivery.ps1`
- Modify: `scripts/validate-native-build-versioning.mjs`

- [ ] **Step 1: Add failing validation markers**

Update `scripts/validate-native-build-versioning.mjs` to require delivery build/install scripts, the package `com.codex.air3nativecamera.delivery`, version `300 / 3.0.0-delivery`, label `叮当保AI`, output `Air3NativeCameraDelivery.apk`, and original/fast/delivery package coexistence checks.

- [ ] **Step 2: Run build validation and confirm RED**

Run: `npm run validate:native-build`

Expected: failure because delivery scripts and markers are missing.

- [ ] **Step 3: Add delivery scripts and center icon**

Create `scripts/build-air3-delivery-apk.ps1` as a wrapper around `air3-native-camera-test/build-native-apk.ps1` using delivery env vars.

Create `scripts/install-and-verify-air3-delivery.ps1` that waits for Air3, installs the delivery APK, checks original/fast/delivery packages, verifies version and Activity resolution, launches the app, and pulls UI/response evidence.

Replace the launcher icon foreground paths with centered geometry inside the 108 x 108 viewport.

- [ ] **Step 4: Run build validation and confirm GREEN**

Run: `npm run validate:native-build`

Expected: pass.

### Task 2: Multipart Voice Upload

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `scripts/validate-native-hud-flow.mjs`
- Modify: `scripts/validate-ai-brain-flow.mjs`

- [ ] **Step 1: Add failing validation markers**

Update native validation to require multipart upload helpers and forbid `audioBase64` in the APK voice upload path.

Update backend validation to require `voicePayloadFromRequest`, `request.formData()`, multipart `audio` parsing, `hasAudio`, and legacy JSON compatibility.

- [ ] **Step 2: Run validations and confirm RED**

Run: `npm run validate:native-hud`

Expected: failure because APK still builds JSON base64 payloads.

Run: `npm run validate:ai-brain`

Expected: failure because backend still reads `/voice` with `request.json()`.

- [ ] **Step 3: Implement APK multipart upload**

Replace the voice JSON payload body with `multipart/form-data` containing metadata fields and an `audio` file part named `voice.wav`.

Keep existing response handling, diagnostics, timeout behavior, and HUD fallback behavior.

- [ ] **Step 4: Implement backend multipart parser**

Add `voicePayloadFromRequest(request)` that accepts multipart or JSON. Normalize multipart fields to the same payload contract and pass `Uint8Array` audio bytes to `handleVoice`.

Update `handleVoice` to use `hasAudio`, direct multipart bytes, legacy base64 bytes, and a metadata-only payload for context storage.

- [ ] **Step 5: Run validations and confirm GREEN**

Run: `npm run validate:native-hud`

Run: `npm run validate:ai-brain`

Expected: both pass.

### Task 3: Air3 Hardware Key Coverage

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `scripts/validate-native-hud-flow.mjs`

- [ ] **Step 1: Add failing key mapping validation**

Require explicit handling for `KEYCODE_MENU`, `KEYCODE_F9`, `KEYCODE_F10`, `KEYCODE_F12`, `KEYCODE_DVR`, `KEYCODE_DPAD_LEFT`, `KEYCODE_DPAD_RIGHT`, `KEYCODE_HOME`, `KEYCODE_VOLUME_UP`, and `KEYCODE_VOLUME_DOWN`.

- [ ] **Step 2: Run validation and confirm RED**

Run: `npm run validate:native-hud`

Expected: failure because only center/back/camera paths are currently explicit.

- [ ] **Step 3: Implement key handling**

Map center/enter to focused button dispatch or capture, menu/F12 to voice, DVR/camera/F9 to capture, F10/home/back to retake/back, d-pad left/right to page navigation, and volume keys to page navigation without exiting the app.

- [ ] **Step 4: Run validation and confirm GREEN**

Run: `npm run validate:native-hud`

Expected: pass.

### Task 4: Delivery Version Log And Memory

**Files:**
- Modify: `docs/air3-v2-version-log.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: Add delivery version log entry**

Record the delivery package identity, version, purpose, verification gates, and artifact naming rule.

- [ ] **Step 2: Update agent memory**

Update current context, progress, and risks with the delivery package and remaining verification state.

### Task 5: Build And Real Device Verification

**Files:**
- No source changes expected.

- [ ] **Step 1: Run local validation**

Run:

```powershell
npm run validate:native-hud
npm run validate:native-build
npm run validate:supabase
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
git diff --check
```

Expected: all pass.

- [ ] **Step 2: Build signed delivery APK**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-air3-delivery-apk.ps1
```

Expected: signed APK verification succeeds and `Air3NativeCameraDelivery.apk` is produced.

- [ ] **Step 3: Install and verify on Air3**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-air3-delivery.ps1
```

Expected: original, fast, and delivery packages coexist; delivery version is `300 / 3.0.0-delivery`; the app launches and evidence files are pulled under `tmp/air3-delivery-300-*`.

- [ ] **Step 4: Optional smoke actions if device remains connected**

Use ADB key events or UI clicks to capture a photo and start voice once, then pull `last_ops_response.json`, `last_voice_response.json`, and `last_voice_diagnostics.json`.
