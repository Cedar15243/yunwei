# V9 Air3 Hardware Soak Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a release-bound Air3 endurance runner that verifies V9 standby and Camera2 resource stability without requiring cloud login.

**Architecture:** A PowerShell 5.1-compatible runner resolves ADB from the current worktree, Git common directory, Android SDK, or PATH, then drives only the existing V9 package. It records package identity, foreground state, battery/thermal/memory/gfxinfo snapshots, Camera2 and audio ownership, filtered logcat, and a JSON summary; it fails closed on identity mismatch, crash/ANR, leaked camera/audio clients, or excessive jank. No secrets, network calls, or APK code changes are allowed.

**Tech Stack:** PowerShell 5.1, ADB, Android `dumpsys`, Node.js contract tests, JSON evidence, existing V9 release/delivery validators.

---

### Task 1: Define the soak contract

**Files:**
- Create: `scripts/test-v9-air3-soak-contract.mjs`
- Modify: `package.json`

- [ ] Write assertions for the V9 package/version, dynamic launcher resolution, worktree-safe ADB resolution, bounded duration/cycle arguments, battery/thermal/memory/gfxinfo/camera/audio evidence, crash/ANR failure-closed checks, JSON summary, and absence of network secrets.
- [ ] Run `npm run test:v9-air3-soak-contract`; expect RED because the runner is absent.

### Task 2: Implement the runner

**Files:**
- Create: `scripts/run-v9-air3-hardware-soak.ps1`

- [ ] Implement the identity and ADB preflight plus `-DryRun` output.
- [ ] Implement bounded Camera2 cycles using existing `FOCUS`/`ENTER`/`BACK` key events and current markers “取景中”/“返回 AI 对话”.
- [ ] Implement periodic idle snapshots and final snapshots for battery, `dumpsys thermalservice`, `dumpsys meminfo`, `dumpsys gfxinfo`, `dumpsys media.camera`, audio ownership, foreground Activity, crash buffer, and filtered ANR log lines.
- [ ] Emit `summary.json` with explicit sample counts, max temperature, battery delta, PSS delta, jank percentage, camera/audio leak flags, and `passed`; throw unless all required checks pass.

### Task 3: Verify and record evidence

**Files:**
- Create: `docs/verification/2026-08-09-v9-air3-hardware-soak.md`
- Modify: `docs/audits/2026-08-06-v9-completion-matrix.md`
- Modify: `docs/releases/v9.0.0-formal-release.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] Run the contract test, PowerShell parser/dry-run probes, and existing release/delivery validators.
- [ ] Run the runner on Air3 for a bounded local sample, then a release soak window; preserve raw evidence under `output/air3-v9-hardware-soak-<timestamp>/`.
- [ ] Record only measured values; if duration or thermal evidence is incomplete, keep the matrix item pending rather than inferring a pass.
- [ ] Rebuild the formal ZIP only after the evidence and validators agree, then run `git diff --check`.
