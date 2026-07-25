# Dingdang V7 Task-Guided Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing photo-and-voice diagnosis flow into a recoverable AR maintenance task with guided repair, voice pagination, and expert handoff recovery.

**Architecture:** Add a pure-Java maintenance domain layer that owns facts, evidence, response pages, and repair progress. `MainActivity` adapts the domain state to the existing AI, ASR, Camera2, HUD WebView, and TRTC entry points without changing their transport contracts.

**Tech Stack:** Java 8, Android SDK, JUnit 4, WebView HUD, Camera2, realtime ASR, TRTC.

---

### Task 1: Task Domain
- [ ] Add `MaintenanceTask` and unit tests for evidence, facts, response pagination, dynamic repair steps, and snapshot restore.
- [ ] Run `:app:testDebugUnitTest` and fix failures.

### Task 2: Voice And AI Integration
- [ ] Make `MainActivity` create/update the current maintenance task from voice, photos, and streamed AI responses.
- [ ] Send task memory plus recent messages to direct AI while preserving the existing backend contract.
- [ ] Map page navigation and repair commands only in the matching task state.

### Task 3: Expert Recovery
- [ ] Save a task snapshot before entering expert collaboration and restore the HUD state after exit.
- [ ] Keep camera and microphone resource ownership in the existing `IntegratedModeController`.

### Task 4: HUD And Capability Center
- [ ] Replace fixed English repair content with dynamic Chinese task content.
- [ ] Implement diagnosis paging controls and the approved HUD proportions, breathing effects, status labels, and capability copy.

### Task 5: Verification
- [ ] Run targeted unit tests, all Android unit tests, native HUD validation, build, and connected-device smoke checks when ADB is available.
