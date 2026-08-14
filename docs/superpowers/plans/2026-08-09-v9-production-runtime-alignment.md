# V9 Production Runtime Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure the post-login Supabase deployment and real-provider Air3 smoke path builds, installs, and validates the formal V9 application instead of the retired `dingdangops` package.

**Architecture:** Keep the existing Supabase deploy and real-smoke entry points, but align their runtime identity with the formal V9 release contract. Add a dedicated formal V9 installer that verifies the release manifest, APK hash, package identity, version, launcher activity, device installation, and V8 coexistence without storing or printing long-lived credentials.

**Tech Stack:** PowerShell 5.1, Node.js contract tests, Android SDK `aapt2`, ADB, Gradle/AGP formal V9 release build.

---

### Task 1: Lock the V9 production runtime contract

**Files:**
- Create: `scripts/test-v9-production-runtime-contract.mjs`

- [ ] Write a test that requires the production deploy helper to call `build-v9-release.ps1` and `install-and-verify-v9-release.ps1`.
- [ ] Require the real-provider smoke defaults to use `com.codex.air3nativecamera.dingdangexpert.v9`, `900000`, and `9.0.0`.
- [ ] Require all production scripts to reject the retired `dingdangops` package and to support secret-free dry runs.
- [ ] Run `node scripts/test-v9-production-runtime-contract.mjs` and confirm RED against the current legacy defaults.

### Task 2: Add a formal V9 installer

**Files:**
- Create: `scripts/install-and-verify-v9-release.ps1`

- [ ] Add a `-DryRun` contract that prints only paths and public package/version identifiers.
- [ ] Validate the release manifest, APK SHA-256, package ID, version code/name, and launcher activity before installation.
- [ ] Install with `adb install -r -g`, verify the device package identity, launch the resolved component, and ensure the existing V8 stable package remains installed.
- [ ] Re-run the production runtime contract test and keep it RED until the deployment and smoke scripts are aligned.

### Task 3: Align deployment and real smoke with V9

**Files:**
- Modify: `scripts/deploy-dingdang-supabase-prod.ps1`
- Modify: `scripts/run-dingdang-real-live-smoke.ps1`
- Modify: `scripts/audit-dingdang-supabase-live-readiness.mjs`
- Modify: `package.json`

- [ ] Replace the legacy build/install helpers with the formal V9 builder and installer.
- [ ] Pass only the public backend and activation URLs into the secure V9 build; do not read or embed `OPS_GLASSES_API_KEY` in the APK.
- [ ] Make real smoke validate V9 identity, dynamically resolve the launcher component, and compute foreground evidence from the configured package.
- [ ] Add `test:v9-production-runtime` and update the Supabase readiness audit markers.
- [ ] Run the new test, existing Supabase project-ref tests, deploy dry-run, real-smoke dry-run, and `validate:supabase`.

### Task 4: Refresh release evidence and documentation

**Files:**
- Modify: `docs/dingdang-final-verification-runbook.md`
- Modify: `docs/verification/2026-08-09-v9-supabase-production-readiness.md`
- Modify: `docs/audits/2026-08-06-v9-completion-matrix.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] Replace legacy installer commands with the V9 production sequence.
- [ ] Record the discovered legacy-target risk and the V9-aligned remediation.
- [ ] Run `validate:v9-release`, `validate:v9-delivery`, `test:v9-production-runtime`, `audit:dingdang-supabase`, and `git diff --check`.
