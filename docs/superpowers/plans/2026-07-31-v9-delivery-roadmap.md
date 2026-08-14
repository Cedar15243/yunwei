# V9 AI Ops Glasses Delivery Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the V9 glasses-to-cloud operations loop without changing the approved glasses UI or the running remote expert web application.

**Architecture:** Implement independently testable vertical slices. The audio coordinator protects microphone ownership; local project/task state remains authoritative on the glasses; the managed-device queue asynchronously synchronizes authorized events and evidence to the isolated cloud management platform.

**Tech Stack:** Android Java/Camera2/AudioRecord/JUnit/ADB, Supabase Postgres/Edge Functions/Deno, React/Vite/TypeScript, Docker Compose, Caddy, MDM managed configuration.

---

## Ordered Work

### 1. Baseline and Isolation
- [x] Freeze current HUD, remote expert web, package identifiers, release target, and rollback target.
- [x] Introduce a separate V9 application ID/version build without altering existing variants.
- [x] Build and verify the V9 package identity before installation.

### 2. Account, Role, Device, and Voiceprint Foundation
- [x] Extend schema/API for organization-scoped account lifecycle, project scope, role assignments, device bindings, MDM policy status, and immutable administration audit events.
- [x] Build Personnel, Devices, and Voiceprint management workbench pages with server-side authorization, one-time credential handling, second confirmation/reason capture, and accessibility/loading/retry states.
- [x] Add consent version/time, opaque provider template reference, bound user/device, enrollment status, last verification, three-failure lockout, revoke/delete result, and audit records. Exclude raw samples/provider credentials from the database and application logs.
- [x] Implement backend-proxied enrollment, verification, re-enrollment, deletion, and provider-template cleanup before enabling glasses interactions.

### 3. Secure Device Event Sync
- [x] Build token-hash migration, protected device event API, administrator credential rotation API, Android file queue, HTTPS transport, and MDM configuration reader.
- [x] Verify Deno device/management tests and Edge Function type checking.
- [x] Verify Android event title serialization with `TaskSyncQueueTest`.
- [x] Connect events only after local success: task creation, final user turn, AI completion, photo/video persistence, repair-state change, and task completion.
- [ ] Deploy migration/Edge Function and enroll a device only after isolated-cloud readiness checks.

### 4. Audio Coordinator and Two Voice Modes
- [x] Write failing tests for exclusive audio ownership and non-resuming pause behavior.
- [x] Implement the coordinator and voice mode state machine with existing single-click and new double-click behavior.
- [ ] Verify camera/video/expert handoff on Air3 before enabling the mode in a release candidate.

### 5. Voiceprint HUD and Verification Gate
- [x] TDD passive/listening/verifying states, double-click enable/disable, microphone handoff, and no automatic resume after camera/video/expert release.
- [x] Add existing-HUD enrollment, re-enrollment, deletion, consent, verification, three-failure lockout, and recoverable error states without changing page layout.
- [x] Require verified final speech for voiceprint-mode commands and AI turns; failure never falls back to Xiao Dingdang mode or routes text.
- [ ] Prove deployed provider enrollment, rejection, lockout, re-enrollment, deletion, and revocation.

### 6. Command, Project, and Task Semantics
- [x] TDD direct-command precedence, high-risk confirmation, global return/new-project/close-task, and explicit historical restore.
- [x] TDD active-project-only AI context, turn/evidence pairing, and page/repair-step pagination separation.
- [x] Integrate with existing HUD data flow without changing visual layout.

### 7. Evidence and Management Platform
- [x] Add task-scoped resumable photo/video evidence transfer after local persistence: durable upload sessions, validated chunks, received-chunk cursor, idempotent completion, atomic finalization, and cleanup on Python gateway, Android, and Supabase Edge.
- [x] Extend management timeline/media/device views only within the existing Product Design context and audit.
- [x] Add abnormal-sync drilldown, one-time device credential display, local retry, focus, and ARIA states without touching expert web.

### 8. Cloud Records, Knowledge, and Skill Operations
- [x] Extend the operations record center with organization-scoped search/filter/page/export, immutable task timeline, evidence/sync-failure drilldown, and role-gated audited recovery actions.
- [x] Add knowledge base categories, document/attachment metadata, draft/review/published/archive lifecycle, revisions, audit history, authorized retrieval, and human-confirmed task-to-case drafting.
- [x] Add skill package identity, structured draft configuration, review/publish/rollback version lifecycle, assignment policy, release notes, knowledge references, and audit history.
- [x] Add a server-derived device pull manifest for assigned published skills, permitted knowledge index, feature policy, ETag/version cache control, and explicit active-task version snapshot behavior.
- [x] Apply Product Design context/audit to the new management workbench pages and run web interaction/accessibility checks without changing the existing remote expert web.

### 9. Cloud Deployment and Release Validation
- [ ] Build/deploy the management web in its isolated cloud directory, Docker container, dedicated port, and Caddy site block with rollback material.
- [ ] Validate Caddy before reload, authenticate management access, verify RLS, and prove the existing expert health endpoint remains unchanged.
- [x] Build/install the independent V9 APK, run the local Air3/ADB/performance regression matrix, and archive current artifacts (`docs/verification/2026-08-08-v9-formal-delivery-package.md`). Real service-dependent voice/voiceprint acceptance remains an external gate above.
- [x] Produce release notes, checksums, deployment manifest, rollback instructions, and external-prerequisite list before installation approval.
