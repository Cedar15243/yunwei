# 626 Integrated Expert Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an independently installable 627 preview APK that preserves all 626 AI guidance behavior, integrates the verified expert collaboration mode, and reserves stable interfaces for the remaining operations features.

**Architecture:** Create a new Gradle Android project by copying the verified 626 host source without changing the baseline. Keep the legacy `MainActivity` as the AI guidance host, add a pure-Java mode controller and feature registry, and expose the migrated TRTC implementation through one `ExpertCollabCoordinator` facade. The host owns camera and microphone transitions so Camera2/ASR and TRTC are never active together.

**Tech Stack:** Java 8, Android SDK 34, Gradle 7.6, Android Gradle Plugin 7.4.2, Tencent TRTC 13.4.0.20477, OkHttp 4.12.0, JUnit 4, Node.js validation scripts, ADB.

---

### Task 1: Scaffold the isolated integrated Android project

**Files:**
- Create: `air3-dingdang-expert-integrated-app/settings.gradle`
- Create: `air3-dingdang-expert-integrated-app/build.gradle`
- Create: `air3-dingdang-expert-integrated-app/gradle.properties`
- Create: `air3-dingdang-expert-integrated-app/app/build.gradle`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/AndroidManifest.xml`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/res/values/strings.xml`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/res/values/styles.xml`
- Copy: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Copy: `air3-native-camera-test/app/src/main/res/drawable/ic_launcher.xml`
- Test: `scripts/validate-dingdang-integrated-isolation.mjs`

- [ ] **Step 1: Write the failing isolation test**

Create a validator that asserts the new defaults and hashes the 626 baseline files before any build:

```js
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const build = readFileSync("air3-dingdang-expert-integrated-app/app/build.gradle", "utf8");
assert.match(build, /applicationId "com\.codex\.air3nativecamera\.dingdangexpert\.follow\.preview"/);
assert.match(build, /versionCode 627/);
assert.match(build, /versionName "6\.2\.7-expert-preview"/);
assert.doesNotMatch(build, /TRTC_SDK_SECRET/);
console.log("Integrated preview isolation validation passed.");
```

- [ ] **Step 2: Run the validator and verify red**

Run: `node scripts/validate-dingdang-integrated-isolation.mjs`

Expected: FAIL because `air3-dingdang-expert-integrated-app/app/build.gradle` does not exist.

- [ ] **Step 3: Create the Gradle project**

Use these fixed preview defaults in `app/build.gradle`:

```groovy
plugins { id "com.android.application" }

def collabServerUrl = project.findProperty("collabServerUrl") ?: "http://127.0.0.1:8787"

android {
    namespace "com.codex.air3nativecamera"
    compileSdk 34
    buildToolsVersion "34.0.0"
    defaultConfig {
        applicationId "com.codex.air3nativecamera.dingdangexpert.follow.preview"
        minSdk 34
        targetSdk 34
        versionCode 627
        versionName "6.2.7-expert-preview"
        buildConfigField "String", "COLLAB_SERVER_URL", "\"${collabServerUrl}\""
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { minifyEnabled false } }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation "com.tencent.liteav:LiteAVSDK_TRTC:13.4.0.20477"
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
    testImplementation "junit:junit:4.13.2"
}
```

Copy the 626 Java source and resources mechanically; do not edit the baseline source path.

- [ ] **Step 4: Add generated legacy configuration without committing secrets**

Add a Gradle source-generation task that reads `OPS_GLASSES_API_KEY`, `DIRECT_GPT_API_KEY`, and `DIRECT_ASR_API_KEY` from environment variables or ignored files under repository `tmp/`, writes `GeneratedConfig.java` under `app/build/generated/source/dingdang/`, and logs only each key source, never its value.

The generated class must preserve every field currently emitted by `air3-native-camera-test/build-native-apk.ps1`:

```java
final class GeneratedConfig {
    static final String APP_ID = "com.codex.air3nativecamera.dingdangexpert.follow.preview";
    static final String APP_LABEL = "叮当AI运维专家·协同测试";
    static final String EVENTS_ENDPOINT = "${eventsEndpoint}";
    static final String OPS_GLASSES_API_KEY = "${opsGlassesApiKey}";
    static final String DINGDANG_BACKEND_BASE_URL = "${dingdangBackendBaseUrl}";
    static final String DINGDANG_BACKEND_API_KEY = "${opsGlassesApiKey}";
    static final boolean FAST_UPLOAD = false;
    static final boolean DIRECT_GPT_ENABLED = false;
    static final String DIRECT_GPT_BASE_URL = "${directGptBaseUrl}";
    static final String DIRECT_GPT_MODEL = "${directGptModel}";
    static final String DIRECT_GPT_REASONING_EFFORT = "${directGptReasoningEffort}";
    static final String DIRECT_GPT_API_KEY = "${directGptApiKey}";
    static final String DIRECT_ASR_ENDPOINT = "${directAsrEndpoint}";
    static final String DIRECT_ASR_API_KEY = "${directAsrApiKey}";
}
```

- [ ] **Step 5: Build and validate green**

Run:

```powershell
node scripts/validate-dingdang-integrated-isolation.mjs
air3-dingdang-expert-integrated-app\gradlew.bat test assembleDebug
```

Expected: isolation validator passes, JUnit task succeeds, and preview APK builds without modifying `air3-native-camera-test/`.

- [ ] **Step 6: Commit**

```powershell
git add air3-dingdang-expert-integrated-app scripts/validate-dingdang-integrated-isolation.mjs
git commit -m "build: scaffold isolated 627 integrated preview"
```

### Task 2: Add stable operations feature interfaces

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/features/FeatureEntry.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/features/FeatureRegistry.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/features/FeatureRegistryTest.java`

- [ ] **Step 1: Write the failing registry test**

```java
@Test
public void registersExpertAndEveryReservedOperationsFeature() {
    FeatureRegistry registry = FeatureRegistry.createDefault();
    assertEquals(Arrays.asList(
            "expert_collab", "equipment_inspection", "field_records",
            "asset_records", "work_orders", "knowledge_base",
            "operations_reports", "safe_operations", "training_drills"),
            registry.ids());
    assertTrue(registry.require("expert_collab").isAvailable());
    assertFalse(registry.require("equipment_inspection").isAvailable());
}
```

- [ ] **Step 2: Run the test and verify red**

Run: `air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "*FeatureRegistryTest"`

Expected: FAIL because `FeatureRegistry` does not exist.

- [ ] **Step 3: Implement the registry**

`FeatureEntry` must expose stable metadata and no Android side effects:

```java
public interface FeatureEntry {
    String id();
    String title();
    boolean isAvailable();
    void enter(FeatureHost host);
    void release();
    interface FeatureHost {
        void openFeature(String id, String title, boolean available);
    }
}
```

Register exact user-facing names: `专家协同`、`设备巡检`、`现场记录`、`设备档案`、`运维工单`、`运维知识库`、`运维报告`、`安全作业`、`培训演练`.

- [ ] **Step 4: Run tests and verify green**

Run: `air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "*FeatureRegistryTest"`

Expected: all registry tests pass.

- [ ] **Step 5: Commit**

```powershell
git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/features air3-dingdang-expert-integrated-app/app/src/test
git commit -m "feat: reserve operations feature interfaces"
```

### Task 3: Add a testable host mode controller

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/mode/IntegratedModeController.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/mode/IntegratedModeControllerTest.java`

- [ ] **Step 1: Write failing transition tests**

```java
@Test
public void expertEntryReleasesLegacyMediaBeforeShowingExpert() {
    FakeHooks hooks = new FakeHooks();
    IntegratedModeController controller = new IntegratedModeController(hooks);
    controller.enterExpert();
    assertEquals(Arrays.asList("persist", "stopVoice", "closeCamera", "showExpert"), hooks.events);
    assertEquals(IntegratedModeController.Mode.EXPERT, controller.mode());
}

@Test
public void expertExitReleasesTrtcBeforeRestoringChat() {
    FakeHooks hooks = new FakeHooks();
    IntegratedModeController controller = new IntegratedModeController(hooks);
    controller.enterExpert();
    hooks.events.clear();
    controller.exitExpert();
    assertEquals(Arrays.asList("releaseExpert", "showChat", "startCamera", "resumeVoice"), hooks.events);
    assertEquals(IntegratedModeController.Mode.CHAT, controller.mode());
}
```

- [ ] **Step 2: Run and verify red**

Run: `air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "*IntegratedModeControllerTest"`

Expected: FAIL because the controller does not exist.

- [ ] **Step 3: Implement exact transition ordering**

```java
public final class IntegratedModeController {
    public enum Mode { CHAT, CAMERA, EXPERT }
    public interface Hooks {
        void persistLegacyState();
        void stopLegacyVoice();
        void closeLegacyCamera();
        void showExpert();
        void releaseExpert();
        void showChat();
        void startLegacyCamera();
        void resumeLegacyVoice();
    }
    private final Hooks hooks;
    private Mode mode = Mode.CHAT;
    public IntegratedModeController(Hooks hooks) { this.hooks = hooks; }
    public Mode mode() { return mode; }
    public void enterExpert() {
        if (mode == Mode.EXPERT) return;
        hooks.persistLegacyState();
        hooks.stopLegacyVoice();
        hooks.closeLegacyCamera();
        hooks.showExpert();
        mode = Mode.EXPERT;
    }
    public void exitExpert() {
        if (mode != Mode.EXPERT) return;
        hooks.releaseExpert();
        hooks.showChat();
        hooks.startLegacyCamera();
        hooks.resumeLegacyVoice();
        mode = Mode.CHAT;
    }
}
```

- [ ] **Step 4: Run tests and verify green**

Run: `air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "*IntegratedModeControllerTest"`

Expected: all transition and idempotency tests pass.

- [ ] **Step 5: Commit**

```powershell
git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/mode air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/mode
git commit -m "feat: define integrated media mode transitions"
```

### Task 4: Migrate the verified expert collaboration module behind a facade

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/expertcollab/ExpertCollabCoordinator.java`
- Copy and adapt: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/*.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/expertcollab/CollabStateMachineTest.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/expertcollab/AnnotationMapperTest.java`

- [ ] **Step 1: Copy existing pure-Java tests and verify they fail in the new module**

Run: `air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "com.codex.expertcollab.*"`

Expected: FAIL because the collaboration classes are not yet present.

- [ ] **Step 2: Copy the verified protocol, state, socket, TRTC and annotation classes**

Copy mechanically from `air3-expert-collab-app`; preserve event names and credential behavior. Change freeze image scale to `CENTER_CROP` so the Air3 landscape display does not show portrait black bars.

- [ ] **Step 3: Implement the single public facade**

```java
public final class ExpertCollabCoordinator {
    public interface Host {
        void requestExitExpertMode();
        void showExpertStatus(String message);
    }
    public ExpertCollabCoordinator(Activity activity, String serverUrl, Host host) { }
    public View createView() { }
    public void start() { }
    public boolean handleConfirmKey() { }
    public void endCallAndExit() { }
    public void release() { }
}
```

The facade owns all collaboration callbacks and provides both `挂断` and `退出专家模式`. `release()` must invalidate late credential and TRTC callbacks before closing clients.

- [ ] **Step 4: Run copied tests and build**

Run:

```powershell
air3-dingdang-expert-integrated-app\gradlew.bat :app:testDebugUnitTest --tests "com.codex.expertcollab.*"
air3-dingdang-expert-integrated-app\gradlew.bat assembleDebug
```

Expected: collaboration tests pass and APK builds.

- [ ] **Step 5: Commit**

```powershell
git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/expertcollab air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/expertcollab
git commit -m "feat: migrate expert collaboration module"
```

### Task 5: Integrate expert entry, exit, voice and hardware keys into the 626 host

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Test: `scripts/validate-dingdang-integrated-host.mjs`

- [ ] **Step 1: Write failing host contract checks**

The validator must assert exact source contracts:

```js
import assert from "node:assert/strict";
const mustInclude = (content, needle) => assert.ok(
  content.includes(needle),
  `missing integrated host contract: ${needle}`,
);
mustInclude(main, "private enum ScreenMode { CHAT, CAMERA, EXPERT }");
mustInclude(main, "AI 智能运维指导");
mustInclude(main, "专家协同");
mustInclude(main, "设备巡检");
mustInclude(main, "现场记录");
mustInclude(main, "更多运维");
mustInclude(main, "enterExpertMode");
mustInclude(main, "exitExpertMode");
mustInclude(main, "呼叫专家");
mustInclude(main, "打开专家协同");
```

- [ ] **Step 2: Run and verify red**

Run: `node scripts/validate-dingdang-integrated-host.mjs`

Expected: FAIL because host integration is absent.

- [ ] **Step 3: Add UI entries without removing existing controls**

Add `专家协同`、`设备巡检`、`现场记录` and `更多运维` to the existing project rail. Expert entry calls `enterExpertMode()`. Reserved entries call one method:

```java
private void showReservedFeature(FeatureEntry feature) {
    stateText.setVisibility(View.VISIBLE);
    stateText.setText(feature.title() + "将在下一阶段启用");
}
```

The reserved action must not request permissions, open camera, start audio, or connect to a server.

- [ ] **Step 4: Wire mode transitions to real legacy resources**

`enterExpertMode()` must call `persistChatProjects()`, `cancelForegroundVoiceListening()`, `stopVoiceRecording(false, "expert_enter")`, `closeCamera()`, and `stopCameraThread()` before showing the expert view.

`exitExpertMode()` must call `ExpertCollabCoordinator.release()`, `renderChatScreen()`, `startCameraFlow()`, and `scheduleForegroundVoiceListening("expert_exit")` in that order.

- [ ] **Step 5: Add voice commands and hardware behavior**

Extend `VoiceCommand` with `OPEN_EXPERT`. Map `呼叫专家` and `打开专家协同` to this command. In `EXPERT`, BACK/DPAD_LEFT/F10 exits expert mode; camera keys are consumed without starting Camera2; confirm delegates to `ExpertCollabCoordinator.handleConfirmKey()`.

- [ ] **Step 6: Validate original and integrated contracts**

Run:

```powershell
npm run validate:native-hud
npm run validate:native-build
node scripts/validate-dingdang-integrated-host.mjs
air3-dingdang-expert-integrated-app\gradlew.bat test assembleDebug
```

Expected: existing 626 validators pass, integrated validator passes, all JUnit tests pass, APK builds.

- [ ] **Step 7: Commit**

```powershell
git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java scripts/validate-dingdang-integrated-host.mjs
git commit -m "feat: integrate expert mode into AI guidance host"
```

### Task 6: Add protected preview build and installation scripts

**Files:**
- Create: `scripts/build-dingdang-integrated-preview.ps1`
- Create: `scripts/install-and-verify-dingdang-integrated-preview.ps1`
- Modify: `scripts/validate-dingdang-integrated-isolation.mjs`

- [ ] **Step 1: Extend the failing validator with script contracts**

Assert that the install script uses only package `com.codex.air3nativecamera.dingdangexpert.follow.preview`, expects version 627, and verifies existing package versions 626 and 801 before and after installation.

- [ ] **Step 2: Run and verify red**

Run: `node scripts/validate-dingdang-integrated-isolation.mjs`

Expected: FAIL because preview scripts do not exist.

- [ ] **Step 3: Implement build script**

The build script accepts `-CollabServerUrl`, passes it as `-PcollabServerUrl=...`, runs `test assembleDebug`, and prints the APK path without printing secrets.

- [ ] **Step 4: Implement guarded install script**

Before `adb install`, assert:

```text
com.codex.air3nativecamera.dingdangexpert.follow = versionCode 626
com.codex.air3nativecamera.dingdangexpert.collab = versionCode 801
preview package is absent or versionCode 627
```

Use `adb install -r` only for the preview package. After install, repeat all three assertions and grant CAMERA/RECORD_AUDIO only to the preview package.

- [ ] **Step 5: Validate scripts without installing**

Run: `node scripts/validate-dingdang-integrated-isolation.mjs`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add scripts/build-dingdang-integrated-preview.ps1 scripts/install-and-verify-dingdang-integrated-preview.ps1 scripts/validate-dingdang-integrated-isolation.mjs
git commit -m "build: protect integrated preview installation"
```

### Task 7: Run full automated and Air3 end-to-end verification

**Files:**
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`
- Evidence: `tmp/dingdang-integrated-preview/`

- [ ] **Step 1: Run full automated verification**

```powershell
npm run validate:native-hud
npm run validate:native-build
npm run validate:expert-collab
node scripts/validate-dingdang-integrated-isolation.mjs
node scripts/validate-dingdang-integrated-host.mjs
air3-dingdang-expert-integrated-app\gradlew.bat test assembleDebug
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 2: Install only the preview package**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-dingdang-integrated-preview.ps1 `
  -Serial YM00FCF3NW0031 `
  -CollabServerUrl http://192.168.30.205:8787
```

Expected: packages 626, 801, and 627-preview coexist; no uninstall command is issued.

- [ ] **Step 3: Verify original AI guidance flows in the preview package**

In the preview package, create a test project and messages, then use ADB UI tree and screenshots to verify: project title and messages survive expert entry/exit, photo entry, photo confirmation, voice start/stop, new project, history switching, and the existing website diagnosis demonstration. Do not claim original 626 data migration from the separate preview package.

- [ ] **Step 4: Verify expert mode entry and call**

Enter from menu, confirm legacy ASR is stopped, call Wang expert, verify TRTC live video, audio, annotation, freeze, screenshot and observer invitation.

- [ ] **Step 5: Verify expert mode exit three times**

Exit once with the visible button, once with BACK, and once with F10/DPAD_LEFT. Each time verify the browser receives hangup, the same chat project returns, Camera2 works, and foreground voice listening resumes.

- [ ] **Step 6: Verify reserved interfaces are inert**

Open each reserved entry and confirm it shows its name plus next-stage status, while logcat shows no camera open, audio recording, HTTP call, WebSocket connection, file write, or permission request.

- [ ] **Step 7: Capture evidence and update memory**

Save package versions, UI XML, screenshots, filtered logcat and test summaries under ignored `tmp/dingdang-integrated-preview/`. Record verified facts and remaining risks in the three `agent_memory` files.

- [ ] **Step 8: Do not build or install formal 627 yet**

Stop after preview verification and report results. Formal same-package upgrade requires explicit user approval because it replaces installed 626.
