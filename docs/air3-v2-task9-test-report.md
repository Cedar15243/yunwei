# Air3 V2 Task 9 Test Report

Date: 2026-06-06

Device: INMO Air3 `YM00FCF3NW0031`

APK:

- Base artifact: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Versioned artifact naming: `air3-native-camera-test/build/Air3NativeCameraTest-v2.0.9-<gitSha>.apk`
- Package: `com.codex.air3nativecamera`
- Version: `versionCode=209`, `versionName=2.0.9`

## Result

Task 9 real-device verification passed for install, launch, camera HUD, photo upload, Supabase/main-AI response, voice audio upload, local diagnostics, and operator-facing debug hiding.

2026-06-06 rule update: the current visual scene was not a real server console, but under the revised V2 testing rule this should no longer be treated as an invalid photo target by itself. Any clear captured photo must receive real AI scene feedback, and server SSH recovery is now a default operations template rather than the only valid scene.

2026-06-06 V2.0.9 retest: the first rebuilt APK reproduced a build-time secret injection defect on device (`OPS_GLASSES_API_KEY missing in APK build`). The build script now reads `OPS_GLASSES_API_KEY` from the environment first, falls back to ignored local file `tmp/ops_glasses_api_key.local`, trims the value, and fails the build if no key is available. The rebuilt APK uploaded successfully and returned general scene feedback.

## Evidence

- Missing-key reproduction log: `tmp/air3-v2-2.0.9-repro-logcat.txt`
- Fixed photo HUD screenshot: `tmp/air3-v2-2.0.9-fixed-after-photo-valid.png`
- Fixed photo response: `tmp/air3-v2-2.0.9-fixed-last-ops-response.json`
- Direct transcript response: `tmp/air3-v2-2.0.9-fixed-voice-transcript-response-utf8.json`
- Physical voice HUD screenshot: `tmp/air3-v2-2.0.9-physical-voice.png`
- Physical voice response: `tmp/air3-v2-2.0.9-physical-voice-response.json`
- Physical voice logcat: `tmp/air3-v2-2.0.9-physical-voice-logcat.txt`

## Verified

- ADB device online: `YM00FCF3NW0031 device`.
- Network validated: Wi-Fi `youmI_5G`, IP `192.168.110.16`, Supabase host ping passed.
- APK install: `adb install -r` returned `Success`.
- Permissions granted: `CAMERA` and `RECORD_AUDIO` are granted.
- App launch: `com.codex.air3nativecamera/.MainActivity` starts.
- Build key guard: `npm run validate:native-build` now requires local key fallback, key trimming, and build-time failure when no key is available.
- Photo flow: center tap captured and uploaded image; response contained structured HUD fields.
- Fixed photo AI result: `resultType=instruction`, `feedbackCode=null`, `displayTitle=看到的是笔记本和文档页面`. This confirms a clear non-server photo now receives real scene feedback instead of `wrong_target`.
- Direct transcript flow: posting transcript `你看一下现在这个场景是什么，有什么问题，下一步我应该怎么做` to the same session `/voice` endpoint returned HTTP `200`, `resultType=instruction`, `feedbackCode=null`, and `displayTitle=当前是笔记本上的文档/代码编辑场景`. This verifies image + STT text are jointly sent to the main AI brain.
- Physical voice flow: long press plus host TTS recorded and uploaded audio; response HTTP status was `200`, `audioBytes=42145`.
- Physical voice AI result: `feedbackCode=voice_unclear`, `voiceIntent=unknown`, `transcript=""`. The HUD still returned image-based scene understanding and asked the operator to restate the question.
- Operator-facing debug hiding: HUD screenshots did not show raw HTTP/session/debug text.

## Observations

- The photo result HUD and physical voice result HUD show Chinese operator guidance, green guide frame, and no raw HTTP/session/debug text.
- The first home screenshot from earlier Task 9 showed the Android system time/date overlay overlapping the top-left HUD title. Later screenshots did not show the overlap. This should be watched in later UI polish.
- The user reported that Settings could not be opened by tapping on the glasses. ADB successfully launched `com.android.settings/.Settings`, and the screenshot showed Settings open on the network page with Wi-Fi `youmI_5G`. This suggests a launcher/touch/focus issue rather than a broken Settings app.

## Remaining Risks

- Voice transcript accuracy still needs a real human voice sample close to the glasses microphone. This run verified upload and backend handling, but host TTS was not transcribed.
- A real black/white terminal or server local console is still needed to verify the server SSH template branch (`safeCommandKey`, diagnostic/recovery command guidance).
- The top-left system time/date overlay may need native full-screen immersive handling if it recurs on first launch.
