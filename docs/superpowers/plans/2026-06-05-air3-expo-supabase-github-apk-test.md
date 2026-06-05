# Air3 Expo Supabase APK Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a demo-ready Air3 operations app flow with Expo UI design, Supabase backend verification, local Git storage, and a rebuilt Air3 APK installed and tested on the glasses.

**Architecture:** Keep the proven native Camera2 APK as the installable Air3 camera path because it already owns real Camera2 capture on the glasses. Use the Expo project as the product UI reference and Android-app prototype for the operations HUD. Keep Supabase as the session orchestration backend and build the APK with the local ops API key only at build time.

**Tech Stack:** Expo Router / React Native, native Android Java Camera2, Supabase Edge Functions/Postgres/Storage, Git/GitHub CLI, adb-based Test Android Apps verification.

---

## Task 1: Expo HUD Page

**Files:**
- Modify: `air3-ops-expo-app/app/index.tsx`
- Verify: `air3-ops-expo-app/package.json`

- [ ] Replace the mock-only HUD copy with the same operator-facing Chinese flow used by the APK: photo guidance, AI analysis, diagnostic command, recovery command, unclear photo, retest, completion, and human handoff.
- [ ] Keep a 16:9 landscape HUD, high-contrast command text, bottom operation strip, no raw HTTP/session/debug data.
- [ ] Run `npm run typecheck` in `air3-ops-expo-app`.

## Task 2: Supabase Backend Verification

**Files:**
- Read: `supabase/functions/ops-glasses/index.ts`
- Read: `supabase/functions/ops-glasses/automigrate.ts`

- [ ] Verify Supabase CLI is available with `npx supabase --version`.
- [ ] Verify cloud function status with `npx supabase functions list --project-ref zasgzaatthvfglhbxpgo`.
- [ ] Run `npm run validate:supabase`.
- [ ] Run Deno check for the Edge Function.

## Task 3: Native APK Rebuild With Supabase Key

**Files:**
- Modify already completed: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Build output: `air3-native-camera-test/build/Air3NativeCameraTest.apk`

- [ ] Read `tmp/ops_glasses_api_key.local` into `OPS_GLASSES_API_KEY` for the build process only.
- [ ] Run `air3-native-camera-test/build-native-apk.ps1`.
- [ ] Confirm APK v3 signature verification passes.
- [ ] Clean `build/generated-src`, `build/classes`, and `build/dex` after build to avoid retaining generated config.

## Task 4: Git/GitHub Storage

**Files:**
- Repo root: `C:\Users\59979\Documents\New project`

- [ ] If the root is not a Git repo, run `git init`.
- [ ] Add a `.gitignore` entry that excludes `node_modules`, generated APK intermediates, local key files, and transient logs.
- [ ] Commit the current project state locally.
- [ ] Check `gh auth status`; if not logged in, record GitHub push as blocked by missing auth.

## Task 5: Test Android Apps Install And Verify

**Files:**
- APK: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Screenshots: `tmp/air3-post-fix-*.png`
- Logs: `tmp/air3-post-fix-logcat.txt`

- [ ] Confirm Air3 device `YM00FCF3NW0031` is online with adb.
- [ ] Install rebuilt APK with `adb install -r`.
- [ ] Grant `CAMERA` and `RECORD_AUDIO`.
- [ ] Launch `com.codex.air3nativecamera/.MainActivity`.
- [ ] Capture a screenshot and logcat evidence.
- [ ] Tap the HUD to trigger capture/upload, then capture a second screenshot and logs.
- [ ] Verify no operator-facing English debug text or HTTP/bytes/session line appears in screenshots.

## Acceptance Criteria

- Expo HUD typechecks and reflects the intended Air3 operator flow.
- Supabase function is active and local validation passes.
- Rebuilt APK includes the temporary ops API key only in the signed artifact, not in generated source folders.
- Git has a local commit; GitHub push is either completed or explicitly blocked by missing auth.
- Air3 has the rebuilt APK installed and launched, with screenshot/log evidence from Test Android Apps.
