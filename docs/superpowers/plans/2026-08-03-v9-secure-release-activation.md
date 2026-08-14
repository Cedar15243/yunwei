# V9 Secure Release Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a formally signed V9 release path with MDM-first or one-time-code device activation, Android Keystore credential storage, fail-closed model contracts, and revocation-safe short-lived sessions.

**Architecture:** Keep `RestrictionsManager` as the highest-priority enterprise source and add a release-only encrypted local activation record for non-MDM deployments. Supabase remains the device/account authority, the V9 gateway remains the execution service, and the Android client stores only our device bootstrap while all supplier keys and 15-minute access tokens stay server-side or in memory.

**Tech Stack:** Android Java/API 34, Android Keystore AES/GCM, JUnit, Supabase Postgres/Edge Functions/Deno, Python V9 gateway, Gradle signing, PowerShell release automation.

---

### Task 1: Freeze the release and model contract

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/build.gradle`
- Create: `scripts/build-v9-release.ps1`
- Create: `scripts/validate-v9-release.mjs`
- Test: `scripts/validate-v9-release.mjs`

- [x] Add a failing release validation that rejects an unstable production package, missing explicit signing configuration, enabled Debug private provisioning, non-secure runtime, and any model other than `qwen3-vl-plus` / `fun-asr-realtime` / iFlytek `s1aa729d0`.
- [x] Run `node scripts/validate-v9-release.mjs` and confirm it fails because the formal release contract is not implemented.
- [x] Add a `formalRelease` build property that fixes `applicationId=com.codex.air3nativecamera.dingdangexpert.v9`, `versionCode=900000`, `versionName=9.0.0`, requires secure runtime, disables direct GPT and Debug private provisioning, and requires a Release signing file, alias and passwords from ignored environment values.
- [x] Add a PowerShell builder that verifies the required environment, builds `assembleRelease`, verifies the signer, calculates SHA-256, scans the APK for forbidden models and credentials, and writes a release manifest without secret values.
- [x] Re-run the release validation and Gradle configuration tests.

### Task 2: Add encrypted Android activation storage

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceActivationRecord.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceCredentialStore.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/AndroidKeystoreDeviceCredentialStore.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/runtime/DeviceActivationRecordTest.java`

- [x] Write failing tests for HTTPS-only records, required bootstrap, expiry, JSON round-trip, unsupported schema rejection and no supplier-key fields.
- [x] Run the target JUnit test and confirm RED because the activation record does not exist.
- [x] Implement the immutable record and storage interface.
- [x] Implement Android Keystore AES-256/GCM storage with package/schema AAD, app-private `SharedPreferences`, explicit clear and no access-token persistence.
- [x] Run target and runtime test suites.

### Task 3: Resolve MDM and local activation without changing UI

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/ManagedRuntimeConfiguration.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/runtime/ManagedRuntimeConfigurationTest.java`

- [x] Add failing tests proving valid MDM values override a local activation record, an unprovisioned MDM source can use a valid local record, and expired/invalid local records never fall back to generated APK credentials.
- [x] Run the target test and confirm RED for the new local activation cases.
- [x] Load the encrypted record before secure runtime initialization and merge it below MDM priority.
- [x] Keep the existing home/HUD layout and show activation state only through the current Settings capability.
- [x] Run runtime and `MainActivity` tests.

### Task 4: Implement one-time activation control plane

**Files:**
- Create: `supabase/migrations/202608030003_device_activation_codes.sql`
- Create: `supabase/functions/ops-glasses/device-activation.ts`
- Create: `supabase/functions/ops-glasses/device-activation.test.ts`
- Modify: `supabase/functions/ops-glasses/management.ts`
- Modify: `supabase/functions/ops-glasses/management.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] Write failing Deno tests for super-admin issuance, explicit confirmation, 5-15 minute expiry, hash-only persistence, one-time atomic redemption, revoked device and `Cache-Control: no-store`.
- [x] Run the focused Deno tests and confirm RED because the routes and repository contracts are absent.
- [x] Add the activation-code table, immutable audit events and a security-definer atomic redemption RPC.
- [x] Add management issuance and unauthenticated device redemption routes; return only our backend/bootstrap contract and never supplier keys.
- [x] Run focused and full Edge Function tests plus migration contract validation.

### Task 5: Add the Android activation client and current Settings entry

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceActivationClient.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/runtime/DeviceActivationClientTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/features/operations/OperationDetailFactory.java`

- [x] Write failing tests for the exact request/response contract, HTTPS enforcement, no-store response requirement, timeout/error mapping and absence of supplier key fields.
- [x] Run the target tests and confirm RED.
- [x] Implement the activation exchange off the UI thread, persist only a validated `DeviceActivationRecord`, and rebuild the managed clients after activation.
- [x] Reuse the existing Settings HUD for activation status, code entry/QR result confirmation, reactivation and local credential deletion; do not change the home layout.
- [x] Run Android unit tests and HUD contract validation.

### Task 6: Make session revocation clear only authoritative credentials

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/DeviceSessionManager.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/HttpDeviceSessionIssuer.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/DeviceSessionManagerTest.java`
- Test: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/HttpDeviceSessionIssuerTest.java`

- [x] Write failing tests that keep credentials on timeout/`5xx`, clear them only for stable revoked/disabled error codes, erase in-memory access tokens on clear, and allow a later reactivation to replace the credential source.
- [x] Run the focused tests and confirm RED.
- [x] Replace the immutable bootstrap string with a credential provider and add an authoritative revocation callback.
- [x] Parse stable server error codes without logging response secrets.
- [x] Run all sync and workflow tests.

### Task 7: Verify formal Release and Air3 coexistence

**Files:**
- Update: `docs/verification/2026-08-03-v9-secure-release-activation.md`
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [x] Run Android full JVM tests, `validate:native-hud`, `validate:native-build`, Supabase full tests, V9 gateway full tests and `git diff --check`.
- [ ] Build the formally signed Release candidate from ignored local signing inputs and verify package/version/signing certificate.
- [ ] Scan the final APK for all locally managed credentials, supplier long-term keys, OpenClaw and forbidden models; require zero hits, allowing only the managed `fun-asr-realtime` protocol constant when present.
- [ ] Install alongside V8 on Air3 only after the build checks pass; verify activation, restart restore, revoke/reactivate, AI/ASR/voiceprint routing, camera, video, expert collaboration and crash/ANR buffers.
- [x] Record exact hashes, test counts, device evidence and remaining production blockers without calling the candidate market-ready before all gates pass.
