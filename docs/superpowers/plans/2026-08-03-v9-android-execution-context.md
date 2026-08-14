# V9 Android Execution Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect every V9 Android AI request and governed project action to the real local project/task identity required by the strict server execution context, without changing the approved HUD or the previous stable model chain.

**Architecture:** Add small immutable Android request contracts under `sync/` and keep `MainActivity` as the owner of the active project/task lifecycle. The backend AI transport will receive explicit `localProjectId` and `localTaskId`; a separate short-session device client will expose the server Skill, project memory, task-end summary and project-instruction whitelist. Existing direct/legacy builds remain compatible, while V9 secure runtime continues to select `qwen3-vl-plus` and `fun-asr-realtime` only on the server and uses iFlytek `s1aa729d0` only for voiceprint.

**Tech Stack:** Java 8, Android 14/API 34, `HttpURLConnection`, `org.json`, JUnit 4, existing `DeviceSessionManager`, V9 Python gateway.

---

### Task 1: AI Request Identity Contract

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/AiExecutionContext.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/BackendDiagnosisRequest.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/BackendDiagnosisRequestTest.java`

- [ ] **Step 1: Write the failing request contract tests**

```java
@Test
public void diagnosisPayloadCarriesRealProjectAndTaskIdentity() {
    JSONObject payload = BackendDiagnosisRequest.create(
            "image-a", "检查控制器", new AiExecutionContext("project-a", "task-a"));
    assertEquals("project-a", payload.getString("localProjectId"));
    assertEquals("task-a", payload.getString("localTaskId"));
}

@Test(expected = IllegalArgumentException.class)
public void executionContextRejectsMissingTaskIdentity() {
    new AiExecutionContext("project-a", "");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.BackendDiagnosisRequestTest
```

Expected: compilation failure because both production classes are absent.

- [ ] **Step 3: Implement the immutable context and payload builder**

```java
public final class AiExecutionContext {
    private final String localProjectId;
    private final String localTaskId;

    public AiExecutionContext(String localProjectId, String localTaskId) {
        this.localProjectId = requireIdentifier(localProjectId);
        this.localTaskId = requireIdentifier(localTaskId);
    }
}
```

`BackendDiagnosisRequest.create(...)` must emit only `image_id`, `final_text`, `localProjectId`, `localTaskId` and the existing bounded `client_context.source`.

- [ ] **Step 4: Run the focused test until GREEN**

Run the command from Step 2. Expected: all request contract tests pass.

### Task 2: MainActivity And Backend AI Wiring

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/MainActivityProjectBindingTest.java`

- [ ] **Step 1: Write failing lifecycle tests**

Add source/lifecycle contract tests proving that a backend AI call receives the active `TaskSession.projectId()` and `TaskSession.id()`, home input creates a new task, application restore pauses old tasks, explicit history selection resumes the old task, and completed tasks cannot be reused.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.MainActivityProjectBindingTest
```

Expected: failure because `ChatAiClient.send(...)` and `BackendChatClient.sendDiagnosis(...)` do not accept an execution context.

- [ ] **Step 3: Wire context through the existing call chain**

Change the internal transport signature to:

```java
void send(String prompt, String imageId, byte[] jpegBytes,
        AiExecutionContext executionContext, StreamingCallback callback);
```

Build the context only from the real active `TaskSession`. Ordinary chat already calls `ensureMaintenanceTask(...)`; inspection AI must also create or reuse a real task before transport. Direct legacy builds may ignore the context, but V9 backend transport must reject a missing context locally before network I/O.

- [ ] **Step 4: Run focused and existing task tests until GREEN**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.MainActivityProjectBindingTest --tests com.codex.air3nativecamera.task.TaskSessionManagerTest
```

Expected: all tests pass.

### Task 3: Governed Skill And Project Memory Device Client

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/ExecutionContextDeviceClient.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/ExecutionContextDeviceClientTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Write failing HTTP whitelist tests**

Cover only these routes:

```text
GET  /device-sync/skills?localProjectId=...&localTaskId=...
POST /device-sync/tasks/:id/skill
GET  /device-sync/projects
GET  /device-sync/projects/:id
POST /device-sync/tasks/:id/end-summary
POST /device-sync/projects/:id/instructions
```

Assert short-session bearer authorization, `Cache-Control: no-store`, fixed confirmations, stable idempotency keys, bounded responses, strict identifiers and explicit server errors.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.ExecutionContextDeviceClientTest
```

Expected: compilation failure because the client is absent.

- [ ] **Step 3: Implement the minimal client and initialize it only in secure runtime**

Reuse `DeviceSyncConfiguration`, `DeviceAccessTokenProvider` and `HttpConnectionFactory`. Do not store the access token, provider password, MVS credential or iFlytek secret. The client must return parsed immutable DTOs and never create a local fake Skill activation on network or authorization failure.

- [ ] **Step 4: Run focused tests until GREEN**

Run the command from Step 2. Expected: all client contract tests pass.

### Task 4: Regression, Build And Deployment Gate

**Files:**
- Modify: `docs/verification/2026-08-03-v9-model-baseline-and-voiceprint-device.md`
- Create: `docs/verification/2026-08-03-v9-android-execution-context.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: Run Android unit and contract validation**

```powershell
./gradlew.bat :app:testDebugUnitTest
npm run validate:native-hud
npm run validate:native-build
```

Expected: zero failures; approved HUD files and layouts remain unchanged by this phase.

- [ ] **Step 2: Build the next isolated V9 audit APK**

Use `secureRuntime=1`, a new `applicationId`, incremented version code/name, empty client-side AI/ASR/iFlytek credentials and the existing debug-only private device provisioning path. Verify with `aapt2`, `apksigner` and SHA-256.

- [ ] **Step 3: Deploy strict gateway only after Android compatibility is proven**

Back up the live SQLite database, add root-only `V9_ORGANIZATION_ID` and `V9_USER_ID`, deploy the strict release, verify V9/expert health, then perform one real short-session AI request carrying the Android project/task IDs.

- [ ] **Step 4: Run Air3 regression**

Confirm V8 coexistence, V9 version identity, first input creates a new task, continued input reuses it, home does not restore it, explicit project history does, completed task stays closed, AI uses `qwen3-vl-plus`, ASR uses `fun-asr-realtime`, voiceprint uses iFlytek `s1aa729d0`, and no OpenClaw route appears. Capture UI tree, screenshot, focused logcat and crash/ANR evidence.

- [ ] **Step 5: Update verification and project memory**

Record exact test counts, APK hash, deployed release, server permissions, device evidence, remaining voiceprint enrollment dependency and release blockers.
