# Dingdang V7.3 Local Operations Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the seven reserved capability-center modules useful through local, recoverable operations data without changing the existing diagnosis, voice, camera, AI or expert-collaboration contracts.

**Architecture:** Add pure-Java local models for inspection, device profiles and knowledge entries. Extend the existing HUD ability-detail presentation to render capability-specific content sourced from those models and the active `MaintenanceTask`; local actions only mutate task data or open existing capture flows.

**Tech Stack:** Java 8, Android SDK, JUnit 4, local WebView HUD, Camera2, `MaintenanceTask`.

---

### Task 1: Version Baseline
- [ ] Store the V7.2.4 Air3 package, verified flows, scope boundary and rollback condition.
- [ ] Confirm V7.3.0 retains the same preview application ID and increments version metadata only at build time.

### Task 2: Local Domain Models
- [ ] Write failing JUnit tests for inspection checklist progress, device profile records and knowledge catalog lookup.
- [ ] Add Android-free models under `features/operations` with immutable catalog data and task-scoped progress.
- [ ] Run targeted unit tests, then all Android unit tests.

### Task 3: Field Evidence And Task Center
- [ ] Add HUD detail content for current task evidence, diagnosis phase and repair progress.
- [ ] Route field capture actions only to existing photo/video/voice entry points.
- [ ] Confirm back navigation returns to the same HUD task without holding Camera2.

### Task 4: Inspection, Device And Knowledge
- [ ] Render a local inspection checklist with explicit completion count and existing evidence references.
- [ ] Render a local device profile and a read-only SOP/knowledge catalog.
- [ ] Keep all content clearly local or catalog-only; do not claim OCR, video understanding, cloud history or AI execution.

### Task 5: Skill And Agent Catalogs
- [ ] Render Skill domain cards and Agent-to-Skill associations from the existing config models.
- [ ] Ensure voice routes only open explanation pages and never trigger unimplemented operations.

### Task 6: Verification
- [ ] Run Android unit tests, debug build, `git diff --check`, expert service/web tests.
- [ ] Install a V7.3.0 preview APK on Air3 and verify navigation, evidence/task state, inspection completion, expert entry/exit and resource release.
- [ ] Record only verified results and residual real-voice/long-run risks in `agent_memory`.
