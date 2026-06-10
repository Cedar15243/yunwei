# Air3 Delivery APK Design

## Confirmed Goal

Build a delivery-grade Air3 APK version without overwriting previously installed APKs. The new APK must install beside:

- `com.codex.air3nativecamera` (`2.0.15`, current formal package)
- `com.codex.air3nativecamera.fast` (`2.1.6-fast`, current fast package)

The new delivery package is:

- Application ID: `com.codex.air3nativecamera.delivery`
- Version code: `300`
- Version name: `3.0.0-delivery`
- App label: `叮当保AI`

## Scope

The delivery version keeps the proven Camera2, HUD, Supabase, and Android 14 voice path, then hardens the parts that matter for field delivery:

1. Build and install as a separate package so old APKs remain available.
2. Center the launcher icon visual content.
3. Upload Air3 voice audio with multipart form data instead of base64 JSON, while preserving the existing JSON backend path for older APKs.
4. Keep Android 14 `AudioRecord` voice capture with `VOICE_RECOGNITION`, 16 kHz mono PCM WAV, VAD auto-stop, and local diagnostics.
5. Add explicit Air3 key mappings for the documented hardware/input keys: center/enter, back, menu, DVR/camera, F10, F12, d-pad, home, and volume.
6. Add repeatable build/install verification scripts for the delivery package.

## Architecture

The native APK remains the glasses runtime. The build script already supports dynamic package name, label, output name, version code, version name, and fast upload flags; the delivery build wraps these settings in a dedicated script so the package identity is repeatable.

The voice upload path moves from JSON base64 to `multipart/form-data` with metadata fields plus one `audio` file part. Supabase adds a request parser that accepts both multipart and legacy JSON. `handleVoice` consumes a normalized payload, stores the audio, transcribes it, rejects empty or suspicious transcripts, and sends valid transcript plus latest image to the main AI.

The input layer keeps existing focus-dispatch behavior for the bottom three buttons and adds explicit mappings for Air3/Android key codes. This improves hardware control without changing the HUD layout.

## Success Criteria

- Building delivery APK produces `Air3NativeCameraDelivery.apk` and a versioned artifact name containing `3.0.0-delivery`.
- ADB install leaves all three packages installed: original, fast, and delivery.
- `dumpsys package com.codex.air3nativecamera.delivery` reports `versionCode=300` and `versionName=3.0.0-delivery`.
- `cmd package resolve-activity --brief com.codex.air3nativecamera.delivery` resolves `com.codex.air3nativecamera.MainActivity`.
- Launcher/app label for delivery is `叮当保AI`.
- Icon vector foreground is visually centered in the 108 x 108 viewport.
- Native validation passes: `npm run validate:native-hud`, `npm run validate:native-build`.
- Backend validation passes: `npm run validate:supabase`, `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`.
- Device evidence is pulled for UI screenshot/tree and latest voice/photo response files.

## Non-Goals

- Do not remove or overwrite old APK packages.
- Do not commit APK files, build outputs, or secrets.
- Do not add a direct dependency on the vendor AirSDK for voice or camera because the inspected SDK does not expose STT or camera APIs.
- Do not change the product rule that clear general-scene photos must receive real AI feedback instead of being forced into a server-console template.

## Risks

- Full photo and voice response time still depends on backend AI/STT latency. The APK already shows slow-response hints; this design does not claim backend latency is fully optimized.
- Real hardware key events may differ from documented sample key codes. The delivery APK should log and handle the known Android key codes first; further key-specific fixes require real key event evidence if a physical key still fails.
