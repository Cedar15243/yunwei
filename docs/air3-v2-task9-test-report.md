# Air3 V2 Task 9 Test Report

Date: 2026-06-06

Device: INMO Air3 `YM00FCF3NW0031`

APK:

- Base artifact: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Versioned artifact: `air3-native-camera-test/build/Air3NativeCameraTest-v2.0.8-22e298b.apk`
- Package: `com.codex.air3nativecamera`
- Version: `versionCode=208`, `versionName=2.0.8`

## Result

Task 9 real-device verification passed for install, launch, camera HUD, photo upload, Supabase/main-AI response, voice audio upload, local diagnostics, and operator-facing debug hiding.

The current visual scene was not a real server console. The main AI correctly returned `recognition_problem` / `wrong_target`, which is the expected V2 behavior for an invalid photo target.

## Evidence

- Home HUD screenshot: `tmp/air3-v2-home.png`
- After photo screenshot: `tmp/air3-v2-after-photo.png`
- After voice screenshot: `tmp/air3-v2-after-voice.png`
- Photo response: `tmp/air3-v2-last-ops-response.json`
- Voice response: `tmp/air3-v2-last-voice-response.json`
- Package/process log capture: `tmp/air3-v2-logcat-pid.txt`
- Settings diagnosis screenshot: `tmp/air3-settings-adb.png`

## Verified

- ADB device online: `YM00FCF3NW0031 device`
- Network validated: Wi-Fi `youmI_5G`, IP `192.168.110.16`, Supabase host ping passed.
- APK install: `adb install -r` returned `Success`.
- Permissions granted: `CAMERA` and `RECORD_AUDIO` are granted.
- App launch: `com.codex.air3nativecamera/.MainActivity` starts.
- Photo flow: center tap captured and uploaded image; response contained structured HUD fields.
- Photo AI result: `resultType=recognition_problem`, `feedbackCode=wrong_target`, `displayTitle=没有看到服务器控制台`.
- Voice flow: long press recorded and uploaded audio; response HTTP status was `200`, `audioBytes=42657`.
- Voice AI result: structured HUD response returned; `voiceIntent=unknown` and `transcript=""` because the spoken phrase was not captured/transcribed clearly.
- Operator-facing debug hiding: pulled response files did not contain `HTTP`, `bytes=`, `session=`, `Exception`, `No instruction`, `No local STT`, or `debug`.

## Observations

- The home HUD, photo result HUD, and voice result HUD all show Chinese operator guidance, green guide frame, and no raw HTTP/session/debug text.
- The first home screenshot showed the Android system time/date overlay overlapping the top-left HUD title. Later screenshots did not show the overlap. This should be watched in later UI polish.
- The user reported that Settings could not be opened by tapping on the glasses. ADB successfully launched `com.android.settings/.Settings`, and the screenshot showed Settings open on the network page with Wi-Fi `youmI_5G`. This suggests a launcher/touch/focus issue rather than a broken Settings app.

## Remaining Risks

- A real black/white terminal or server local console must be placed inside the green frame to verify the instruction branch (`safeCommandKey`, diagnostic/recovery command guidance).
- Voice transcript accuracy still needs a real human voice sample close to the glasses microphone. This run verified upload and backend handling, but transcript stayed empty.
- The top-left system time/date overlay may need native full-screen immersive handling if it recurs on first launch.
