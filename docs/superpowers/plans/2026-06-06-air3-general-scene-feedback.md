# Air3 General Scene Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Air3 V2 return useful AI feedback for any clear captured scene, while keeping server SSH recovery as one supported operations template instead of the only valid target.

**Architecture:** The native APK still captures images/audio and renders structured HUD responses. Supabase remains the AI brain gateway, but its prompt changes from a server-only classifier to a general scene-feedback assistant that uses image plus transcript and only enters SSH command guidance when the scene/task actually matches that flow.

**Tech Stack:** Native Android Camera2 Java APK, Supabase Edge Function in TypeScript/Deno, Node validation scripts, ADB real-device verification.

---

### Task 1: Lock the New Contract With Failing Tests

**Files:**
- Modify: `scripts/validate-ai-brain-flow.mjs`
- Modify: `scripts/validate-native-hud-flow.mjs`

- [x] **Step 1: Add Supabase prompt assertions**

Require the AI prompt to include general scene-feedback rules and forbid the old hard boundary string:

```js
mustInclude("只要照片清楚，就必须基于画面给出真实反馈");
mustInclude("不要因为画面不是服务器控制台就返回 wrong_target");
mustInclude("服务器 SSH 恢复只是默认运维模板之一");
mustNotInclude("如果画面不是服务器控制台，返回 resultType=recognition_problem, feedbackCode=wrong_target");
```

- [x] **Step 2: Add native HUD assertions**

Require operator-facing APK copy to be generic:

```js
mustInclude("AI 运维现场指导");
mustInclude("请对准需要判断的现场画面");
mustInclude("正在上传给 AI 分析现场画面");
mustNotInclude("服务器 SSH 恢复");
mustNotInclude("请对准服务器本地控制台或终端窗口");
mustNotInclude("正在上传给 AI 识别服务器控制台");
```

- [x] **Step 3: Run tests to verify RED**

Run:

```powershell
npm run validate:ai-brain
npm run validate:native-hud
```

Expected: both fail because production code still uses server-only copy and prompt rules.

### Task 2: Update Supabase AI Brain Rules

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] **Step 1: Change prompt version and task goal**

Use `air3-v2-ai-brain-v2-scene-feedback` and describe the goal as general field feedback plus operations guidance.

- [x] **Step 2: Replace server-only prompt rules**

The prompt must require image + transcript reasoning for clear photos, preserve allowed command safety, and only use recognition problems for unclear/insufficient/conflicting inputs.

- [x] **Step 3: Update no-photo and default fallback copy**

The fallback should ask the operator to photograph the field scene, not specifically a server console.

- [x] **Step 4: Run Supabase validation**

Run:

```powershell
npm run validate:ai-brain
npm run validate:supabase
```

Expected: both pass.

### Task 3: Update Native APK HUD Copy

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [x] **Step 1: Replace home and capture copy**

Use generic “AI 运维现场指导” and “现场画面” language for home, capture, upload, permission, and back-key retry states.

- [x] **Step 2: Keep structured HUD rendering unchanged**

Do not change `HudResponse` parsing or long-text pagination behavior.

- [x] **Step 3: Run native validation**

Run:

```powershell
npm run validate:native-hud
```

Expected: pass.

### Task 4: Update Documentation and Test Report

**Files:**
- Modify: `docs/air3-ops-glasses-architecture.md`
- Modify: `docs/air3-v2-response-contract.md`
- Modify: `docs/air3-v2-task9-test-report.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [x] **Step 1: Update the architecture rule**

Record that any clear photo must receive AI feedback and server SSH recovery is a default task template.

- [x] **Step 2: Update Task 9 report**

Mark the old `wrong_target` result as obsolete under the new test rule, not as a future expected result.

### Task 5: Build, Install, and Test on Air3

**Files:**
- Build output only, ignored by Git.

- [x] **Step 1: Run full local verification**

Run:

```powershell
npm run validate:supabase
npm run validate:native-hud
npm run validate:native-build
powershell -NoProfile -ExecutionPolicy Bypass -File air3-native-camera-test/build-native-apk.ps1
git diff --check
```

- [x] **Step 2: Install and launch on Air3**

Run:

```powershell
adb -s YM00FCF3NW0031 install -r air3-native-camera-test/build/Air3NativeCameraTest.apk
adb -s YM00FCF3NW0031 shell monkey -p com.codex.air3nativecamera 1
```

- [x] **Step 3: Capture evidence**

Use ADB screenshots and pulled response JSON to confirm the HUD no longer presents server-only startup text.

### Task 6: GitHub Checkpoint

**Files:**
- Commit tracked source/docs changes only.

- [x] **Step 1: Commit**

Run:

```powershell
git add docs scripts supabase air3-native-camera-test
git commit -m "feat: generalize Air3 scene feedback"
```

- [ ] **Step 2: Push**

Run:

```powershell
git push
```

Expected: push succeeds if GitHub network is reachable; otherwise record the local commit hash and retry later.
