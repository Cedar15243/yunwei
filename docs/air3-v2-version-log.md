# Air3 V2 Version Log

This document records APK version separation rules and GitHub storage checkpoints. APK binaries are build artifacts and must not be committed; code, scripts, docs, commits, and tags are the traceable source of truth.

## Versioning Rules

- `versionName` uses a human-readable version, for example `2.0.12`.
- `versionCode` uses an increasing integer, for example `212` for `2.0.12`.
- Formal checkpoint tags use `v<versionName>-<purpose>`, for example `v2.0.12-voice-stt-timeout-ux`.
- APK output files must include version and Git short SHA: `Air3NativeCameraTest-v<versionName>-<gitSha>.apk`.
- The base artifact remains `Air3NativeCameraTest.apk` for the current build/install flow.
- `air3-native-camera-test/build/` and `*.apk` must stay ignored. Do not commit APKs, signing intermediates, generated source, or local secrets.
- Temporary version overrides use `AIR3_APK_VERSION_CODE` and `AIR3_APK_VERSION_NAME`; formal checkpoints must update this file and the Git tag.

## Build Secret Rules

- `OPS_GLASSES_API_KEY` must be injected at build time. It is never committed.
- Build priority: environment variable `OPS_GLASSES_API_KEY`, then ignored local file `tmp/ops_glasses_api_key.local`.
- The build script trims the key value and fails immediately if no key is available, so an APK with a guaranteed upload failure is not produced.
- Build output may print `OpsKeySource`, but must never print the key value.

## Current Checkpoint

| Field | Value |
| --- | --- |
| Checkpoint | `v2.0.13-voice-diagnostic-hud` |
| Date | `2026-06-07` |
| Branch | `air3-v2-task1-docs` |
| APK versionCode | `213` |
| APK versionName | `2.0.13` |
| APK naming rule | `Air3NativeCameraTest-v<versionName>-<gitSha>.apk` |
| Purpose | Show stable voice diagnostic reasons on the Air3 HUD and support a dedicated official STT fallback key |

## Included Local Commits

- `8f89a02 feat: route Air3 context through AI brain`
- `a11bdea feat: render structured AI brain HUD responses`
- `54133b1 test: verify Air3 V2 backend contract`
- `36788c3 chore: protect Air3 build artifacts`
- `0bcdbd3 docs: record GitHub storage checkpoint status`
- `22e298b chore: version Air3 APK build artifacts`
- `0540e0c test: verify Air3 V2 APK on device`
- `eacf351 feat: generalize Air3 scene feedback`
- `7acec71 fix: stabilize Air3 HUD paging and scene feedback`
- `95eddc0 fix: tune Air3 voice VAD for room noise`
- `f5c5c32 fix: surface voice transcription timeouts`
- `9a0be9e feat: add Air3 voice diagnostic codes`
- Current checkpoint commit message: `feat: show voice diagnostic cause on HUD`

## GitHub Storage Status

Local Git checkpoints and GitHub remote storage are established.

- Branch: `origin/air3-v2-task1-docs`
- Existing tag: `v2.0.8-task8-versioned-build`
- Existing tag: `v2.0.9-general-scene-feedback`
- Existing tag: `v2.0.10-hud-paging-voice-controls`
- Existing tag: `v2.0.11-voice-vad-tuning`
- Existing tag: `v2.0.11-voice-timeout-feedback`
- Existing tag: `v2.0.12-voice-stt-timeout-ux`
- Next tag for this checkpoint after commit and push: `v2.0.13-voice-diagnostic-hud`
- PR entry: `https://github.com/Cedar15243/yunwei/pull/new/air3-v2-task1-docs`

Note: `gh auth status` may still show GitHub CLI as logged out. Git HTTPS credentials have worked for prior pushes; use `gh auth login` later only if PR or Actions operations need GitHub CLI.

## Backend Diagnostic Checkpoint

| Field | Value |
| --- | --- |
| Date | `2026-06-07` |
| Scope | Supabase Edge Function response contract |
| Purpose | Add stable `diagnosticCode` for voice transcription failures so APK tests can distinguish STT timeout, unsupported provider fallback, invalid official key, empty transcript, and generic STT failure |
| APK reinstall required | No |
| Deployed project | `zasgzaatthvfglhbxpgo` |

Validation evidence:

- `npm run validate:ai-brain`
- `npm run validate:native-hud`
- `npm run validate:native-build`
- `npm run validate:supabase`
- `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`
- Live Edge Function smoke test returned HTTP `200`, `feedbackCode=voice_unclear`, `diagnosticCode=custom_stt_timeout`.
- Air3 device `YM00FCF3NW0031` VAD stopped at `recordingMs=2351`, `stopReason=silence_detected`, and the APK persisted `diagnosticCode=custom_stt_timeout`.

## APK 2.0.13 HUD Diagnostic Checkpoint

| Field | Value |
| --- | --- |
| Date | `2026-06-07` |
| Version | `2.0.13` / `213` |
| Purpose | Show voice diagnostic cause directly on the HUD and allow a dedicated official STT fallback key via `OPENAI_OFFICIAL_TRANSCRIBE_API_KEY` |
| Installed device | `YM00FCF3NW0031` |

Validation evidence:

- APK build signed with v3 signature and installed successfully.
- Device package reports `versionCode=213`, `versionName=2.0.13`.
- Air3 voice VAD stopped at `recordingMs=2189`, `stopReason=silence_detected`.
- HUD displayed `诊断：语音转文字服务超时，按钮和录音已正常。`
- Device response contained `feedbackCode=voice_unclear`, `diagnosticCode=custom_stt_timeout`, `transcript=""`.
