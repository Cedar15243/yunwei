# Air3 Camera Input HUD Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Air3 APK camera distortion/quality gap, add clear photo/voice control entry points, and remove operator-facing debug/English text without disturbing the currently installed demo APK until the user approves post-demo testing.

**Architecture:** Keep the current native Android Camera2 APK as the proven glasses path, but separate preview sizing from still capture sizing, add an aspect-correct TextureView transform, and move debug details out of the HUD. Use Air3 documentation and SDK evidence for input mapping: public Touchpad docs confirm center click/back/home/camera keys, while the local INMO SDK sample maps Touchpad/Ring events to Return, Escape, F10, and arrow keys; the Android APK must first log real KeyEvent values on the device before final binding.

**Tech Stack:** Native Android Java Camera2, Air3/INMO Touchpad and SDK docs, Expo only for UI prototype reference, Supabase Edge Function for existing ops sessions, Test Android Apps/ADB for post-demo verification.

---

## Current Constraints

- Do not install, launch, force-stop, clear data, restart, or otherwise change the APK currently running on the Air3 glasses before the user finishes the demo.
- All work before approval is limited to offline code inspection, planning, Figma design, and uninstalled source changes if explicitly approved later.
- Do not invent Air3 controls. Bind operator actions only after confirming public docs or logging actual Android key events on the Air3.

## Evidence

- Source: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Current preview is a full-screen `TextureView` with `MATCH_PARENT` sizing and no `Matrix` transform.
- Current still size is chosen from JPEG sizes but capped to `<= 1920x1080`, which wastes the Air3 16MP camera capability and can mismatch the preview aspect.
- Current preview buffer uses `captureSize` via `texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight())`.
- Current HUD draws English text: `Put screen text inside this frame, 25-35cm`.
- Current operator status shows debug lines such as upload byte counts, HTTP status, short session id, and raw AI text.
- Current click handling is invisible: click anywhere captures, long press tries local Android STT, but there are no dedicated control affordances.
- Current code does not implement `dispatchKeyEvent`, `onKeyDown`, or Android `KeyEvent` logging.
- Air3 public Touchpad guide confirms center-area movement moves the cursor, center click clicks the screen, Back returns, Home returns home or launches voice assistant on long press, and a Camera Function Key exists.
- Local INMO SDK sample confirms Touchpad/Ring-style events are represented in SDK examples as `Return`, `Escape`, `F10`, and arrow keys, but this is Unity-side evidence and must be verified in the native Android APK.

## File Structure

- Modify later: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
  - Camera sizing, preview transform, input handling, operator HUD text.
- Modify later: `air3-native-camera-test/app/src/main/AndroidManifest.xml`
  - Keep permissions stable; remove `android:debuggable="true"` only when creating a release build path.
- Modify later: `air3-native-camera-test/build-native-apk.ps1`
  - Add separate debug/release build switches if needed.
- Create later: `air3-native-camera-test/docs/air3-key-event-map.md`
  - Record observed key codes from Touchpad/Ring/Camera key tests.
- Existing: `supabase/functions/ops-glasses/index.ts`
  - No immediate change for the three UI/camera bugs unless adding optional client diagnostic metadata.

## Task 1: Preserve Demo State and Prepare a Branch/Copy

**Files:**
- Read: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Create later only if repository is initialized: Git branch or isolated copy for post-demo work.

- [ ] **Step 1: Confirm no device mutation**

Run no ADB commands that affect device state. Allowed commands are local file reads and code inspection only.

Expected: Current Air3 demo APK remains untouched.

- [ ] **Step 2: Check repository state**

Run:

```powershell
git status --short
```

Expected:

```text
fatal: not a git repository (or any of the parent directories): .git
```

If still not a git repository, ask the user before initializing GitHub storage. Do not create Git history silently.

## Task 2: Add Camera Diagnostics Without Changing Release Behavior

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Test later: Air3 logcat and captured local diagnostics file.

- [ ] **Step 1: Add supported-size logging helper**

Add a helper that logs camera id, sensor orientation, active array, JPEG sizes, and SurfaceTexture preview sizes. Keep logs under tag `Air3NativeCameraTest`.

```java
private void logCameraCapabilities(CameraManager manager, String id) throws CameraAccessException {
    CameraCharacteristics c = manager.getCameraCharacteristics(id);
    Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
    Rect activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    android.util.Log.i("Air3NativeCameraTest", "cameraId=" + id
            + " sensorOrientation=" + sensorOrientation
            + " activeArray=" + activeArray);
    if (map == null) {
        android.util.Log.w("Air3NativeCameraTest", "No StreamConfigurationMap");
        return;
    }
    android.util.Log.i("Air3NativeCameraTest", "JPEG sizes=" + Arrays.toString(map.getOutputSizes(ImageFormat.JPEG)));
    android.util.Log.i("Air3NativeCameraTest", "Preview sizes=" + Arrays.toString(map.getOutputSizes(SurfaceTexture.class)));
}
```

- [ ] **Step 2: Call diagnostics only when opening camera**

Call `logCameraCapabilities(manager, cameraId)` after choosing the back camera, before selecting sizes.

Expected: The app still behaves the same, but logs the evidence needed to pick sizes.

## Task 3: Separate Preview Size From Capture Size

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Add field**

```java
private Size previewSize;
```

- [ ] **Step 2: Replace `chooseCaptureSize` logic**

Select the largest useful JPEG size, preferring 4:3 or native high-resolution sizes. Do not cap at 1920x1080.

```java
private Size chooseCaptureSize(CameraManager manager, String id) throws CameraAccessException {
    CameraCharacteristics c = manager.getCameraCharacteristics(id);
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (map == null) {
        return new Size(1920, 1080);
    }
    Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
    if (sizes == null || sizes.length == 0) {
        return new Size(1920, 1080);
    }
    Size best = sizes[0];
    int bestPixels = best.getWidth() * best.getHeight();
    for (Size size : sizes) {
        int pixels = size.getWidth() * size.getHeight();
        boolean usable = size.getWidth() >= 1920 && size.getHeight() >= 1080;
        if (usable && pixels > bestPixels) {
            best = size;
            bestPixels = pixels;
        }
    }
    return best;
}
```

- [ ] **Step 3: Add preview size chooser**

Choose a preview size that matches the 1920x1080 display aspect as closely as possible, preferring sizes at or above display resolution.

```java
private Size choosePreviewSize(CameraManager manager, String id) throws CameraAccessException {
    CameraCharacteristics c = manager.getCameraCharacteristics(id);
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (map == null) {
        return new Size(1920, 1080);
    }
    Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
    if (sizes == null || sizes.length == 0) {
        return new Size(1920, 1080);
    }
    Size best = sizes[0];
    double target = 16.0 / 9.0;
    double bestScore = Double.MAX_VALUE;
    for (Size size : sizes) {
        double ratio = (double) size.getWidth() / (double) size.getHeight();
        double aspectPenalty = Math.abs(ratio - target) * 10000.0;
        double sizePenalty = Math.abs((size.getWidth() * size.getHeight()) - (1920.0 * 1080.0)) / 100000.0;
        double score = aspectPenalty + sizePenalty;
        if (score < bestScore) {
            best = size;
            bestScore = score;
        }
    }
    return best;
}
```

- [ ] **Step 4: Use both sizes**

After selecting `captureSize`, also set:

```java
previewSize = choosePreviewSize(manager, cameraId);
```

Expected: Preview no longer depends on still capture size.

## Task 4: Add Aspect-Correct TextureView Transform

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Add transform method**

```java
private void configurePreviewTransform() {
    if (previewView == null || previewSize == null) {
        return;
    }
    int viewWidth = previewView.getWidth();
    int viewHeight = previewView.getHeight();
    if (viewWidth == 0 || viewHeight == 0) {
        return;
    }
    Matrix matrix = new Matrix();
    RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
    float centerX = viewRect.centerX();
    float centerY = viewRect.centerY();
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
    float scale = Math.max(
            (float) viewHeight / previewSize.getHeight(),
            (float) viewWidth / previewSize.getWidth());
    matrix.postScale(scale, scale, centerX, centerY);
    matrix.postRotate(90f, centerX, centerY);
    previewView.setTransform(matrix);
}
```

- [ ] **Step 2: Apply transform before session**

In `createPreviewSession()`, replace:

```java
texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
```

with:

```java
texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
configurePreviewTransform();
```

- [ ] **Step 3: Re-apply on TextureView size changes**

Call `configurePreviewTransform()` in `onSurfaceTextureSizeChanged`.

Expected: The preview fills the 16:9 display without obvious stretching.

## Task 5: Make Controls Visible and Air3-Friendly

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Create later: `air3-native-camera-test/docs/air3-key-event-map.md`

- [ ] **Step 1: Add operator control strip text**

Replace the invisible gesture-only hint with a bottom control strip:

```text
中心点击：拍照/下一步    长按中心：语音    返回键：重拍    相机键：拍照
```

Use only controls confirmed by docs or by the later key-event probe. If Camera key is not observed in native Android, remove it from the visible strip.

- [ ] **Step 2: Add KeyEvent logger**

```java
@Override
public boolean dispatchKeyEvent(KeyEvent event) {
    android.util.Log.i("Air3NativeCameraTest", "key action=" + event.getAction()
            + " code=" + event.getKeyCode()
            + " name=" + KeyEvent.keyCodeToString(event.getKeyCode())
            + " repeat=" + event.getRepeatCount());
    return super.dispatchKeyEvent(event);
}
```

- [ ] **Step 3: Bind only confirmed key codes**

After post-demo testing confirms native key codes, bind:

```java
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
        captureStillImage("key-center");
        return true;
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
        setStatus("已准备重拍。请重新对准服务器控制台。");
        return true;
    }
    if (keyCode == KeyEvent.KEYCODE_CAMERA) {
        captureStillImage("key-camera");
        return true;
    }
    return super.onKeyDown(keyCode, event);
}
```

Expected: Operators can discover controls from the HUD, and real Air3 key mappings are grounded in logs.

## Task 6: Replace Debug/English HUD Text With Operator Text

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Replace guide text**

Replace:

```java
canvas.drawText("Put screen text inside this frame, 25-35cm", centerX, frame.bottom + 42f, textPaint);
```

with:

```java
canvas.drawText("把服务器控制台文字放入绿色框内", centerX, frame.bottom + 42f, textPaint);
```

- [ ] **Step 2: Hide debug upload status**

Replace:

```java
setStatus(String.format(Locale.US,
        "Uploading focused JPEG bytes=%d originalBytes=%d to ops session.",
        uploadBytes.length,
        jpegBytes.length));
```

with:

```java
setStatus("已拍照，AI 正在识别服务器控制台。请保持画面稳定。");
```

- [ ] **Step 3: Hide debug HTTP success line**

Replace the `OK http=...` status with:

```java
setStatus("AI 已返回下一步，请按屏幕提示操作。中心点击可继续拍照验证。");
```

Keep `persistLastResponse(responseText)` for diagnostics.

Expected: The operator never sees raw HTTP status, byte counts, session ids, or English debug text.

## Task 7: Rework Upload Crop Against the Guide Frame

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Record frame ratios as constants**

```java
private static final float GUIDE_FRAME_WIDTH_RATIO = 0.72f;
private static final float GUIDE_FRAME_HEIGHT_RATIO = 0.50f;
private static final float GUIDE_FRAME_TOP_OFFSET_RATIO = 0.27f;
```

- [ ] **Step 2: Use constants in overlay and upload crop**

Replace hardcoded `0.72f`, `0.50f`, and `0.27f` with the constants.

Expected: Visual guide and uploaded crop remain aligned, reducing "AI says unclear" caused by wrong crop.

## Task 8: Post-Demo Test Android Apps Verification

**Files:**
- Build output: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Screenshots: `tmp/*.png`
- Logs: `tmp/*.txt`

- [ ] **Step 1: Build only after user approval**

Run:

```powershell
$env:OPS_GLASSES_API_KEY=(Get-Content -Raw tmp\ops_glasses_api_key.local).Trim()
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\59979\Documents\New project\air3-native-camera-test\build-native-apk.ps1"
```

Expected: APK builds and signature verification passes.

- [ ] **Step 2: Install only after user approval**

Use Test Android Apps workflow with the connected Air3 serial.

Expected: New APK is installed only after the demo risk window ends.

- [ ] **Step 3: Verify camera preview**

Capture a screenshot from Air3 and compare:

- No visible stretching of computer screen edges.
- Green guide frame stays centered.
- Preview fills 1920x1080 display.
- Uploaded crop is readable by AI.

- [ ] **Step 4: Verify controls**

Test center click, long press, Back, Camera key, and directional swipes. Record actual codes in:

```text
air3-native-camera-test/docs/air3-key-event-map.md
```

Expected: Every visible HUD control has a matching observed event or is removed.

- [ ] **Step 5: Verify text cleanup**

Take screenshots after startup, capture, upload success, unclear photo, voice unavailable, and upload failure.

Expected: No English debug text, no mojibake/乱码, no raw HTTP/byte/session debug data in the operator HUD.

## Acceptance Criteria

- The currently installed demo APK is not touched before the user approves post-demo testing.
- The next test APK preview is aspect-correct on the Air3 1920x1080 display.
- The still image uploaded to AI uses a higher-resolution capture than the old 1920x1080 cap when the hardware supports it.
- The operator HUD has visible, simple entries for photo, voice, retake, and continue.
- All physical key bindings are backed by Air3 docs or observed native Android KeyEvent logs.
- No English debug/garbled text appears in the normal operator HUD.
- Supabase session behavior continues to work with existing `/functions/v1/ops-glasses/sessions/events`.
- Test Android Apps evidence includes screenshots and logs for camera, controls, text, AI upload, and failure recovery.

## Self-Review

- Spec coverage: Covers the three user-reported issues, protects the demo APK, grounds controls in Air3 docs/SDK evidence, and includes Expo/Figma/Supabase/GitHub/Test Android Apps boundaries.
- Placeholder scan: No TBD/TODO/fill-later placeholders remain; unknown key mappings are intentionally converted into a measurable key-event logging task.
- Type consistency: Java methods and fields use names already present or introduced in this plan.
