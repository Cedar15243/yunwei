# V9 Project Identity And Control Plane Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Supabase the authoritative project identity and management record while the V9 gateway remains the low-latency device runtime, with reliable background event delivery and immutable project mapping before AI execution.

**Architecture:** The gateway persists every device event locally first, appends the same canonical event to a SQLite outbox, and delivers it to the Supabase device-sync whitelist with a server-held bootstrap credential and renewable short session. Supabase creates or resolves the authoritative project UUID; the existing content-manifest pull returns that UUID, and the gateway stores an immutable binding between organization, user, device, local project ID, gateway project UUID and Supabase project UUID. AI requests continue to perform one provider call only; cloud synchronization runs independently and missing or conflicting identity fails closed.

**Tech Stack:** Python 3 standard library, SQLite WAL, Supabase Edge Functions/Deno, TypeScript, existing V9 HTTPS gateway, `unittest`, Deno tests.

---

### Task 1: Immutable Gateway Project Identity Binding

**Files:**
- Modify: `v9-ops-gateway/test_execution_context.py`
- Modify: `v9-ops-gateway/execution_context.py`

- [x] **Step 1: Write failing binding tests**

Add repository tests proving that the first valid authoritative manifest creates one binding, a repeated manifest only advances its source manifest metadata, a different Supabase project for the same local project is rejected, and the same Supabase project cannot be rebound to a second local project for the same organization/user/device identity.

- [x] **Step 2: Run the focused tests and verify RED**

Run: `py -3 -m unittest -v test_execution_context.ExecutionContextRepositoryTest.test_authoritative_manifest_creates_an_immutable_project_identity_binding test_execution_context.ExecutionContextRepositoryTest.test_authoritative_manifest_rejects_project_identity_rebinding`

Expected: FAIL because `project_identity_bindings` and conflict enforcement do not exist.

- [x] **Step 3: Implement the binding ledger**

Create `project_identity_bindings` and `project_identity_audit_events` in `ExecutionContextRepository`. Store `organization_id`, `user_id`, `device_id`, `local_project_id`, gateway `project_id`, Supabase `authoritative_project_id`, source manifest version/ETag, status and timestamps. Insert the binding in the same transaction as the first manifest and reject forward or reverse rebinding with `project_identity_conflict`.

- [x] **Step 4: Require the binding in runtime reads**

Before returning a device content manifest, activating a Skill snapshot or building `ExecutionContext`, verify that the latest manifest and immutable binding agree. Expose `authoritativeProjectId` in project detail and execution audits without replacing the existing gateway-internal `projectId` contract.

- [x] **Step 5: Run repository and gateway suites**

Run: `py -3 -m unittest -v test_execution_context.py test_gateway.py`

Expected: all tests pass.

### Task 2: Reliable Gateway-To-Supabase Event Outbox

**Files:**
- Create: `v9-ops-gateway/control_plane_sync.py`
- Create: `v9-ops-gateway/test_control_plane_sync.py`
- Modify: `v9-ops-gateway/gateway.py`
- Modify: `v9-ops-gateway/test_gateway.py`
- Modify: `v9-ops-gateway/deploy/dingdang-v9-gateway.env.example`
- Modify: `v9-ops-gateway/deploy/install.sh`
- Modify: `v9-ops-gateway/test_deployment.py`

- [x] **Step 1: Write failing client and outbox tests**

Cover HTTPS-only configuration, server-held bootstrap exchange, short-session reuse/renewal, canonical event forwarding, 401 session refresh, idempotent duplicate acceptance, retry backoff, restart recovery and no supplier secret persistence in SQLite.

- [x] **Step 2: Run the focused tests and verify RED**

Run: `py -3 -m unittest -v test_control_plane_sync.py test_gateway.py`

Expected: FAIL because the control-plane client, outbox and worker are missing.

- [x] **Step 3: Implement the background relay**

Persist the outbox in the same SQLite transaction as a new local device event. Deliver in a bounded background worker to `/device-sync/session` and `/device-sync/events`; cache only the short access session in memory, never the bootstrap credential or access token in SQLite. Mark duplicate cloud responses as success and retain failed items with bounded exponential backoff.

- [x] **Step 4: Trigger content synchronization after cloud acceptance**

After a `task_started` event is accepted by Supabase, trigger the existing content-manifest worker for its `localProjectId`. Do not block the device event response or add an extra network round trip to the AI provider path.

- [x] **Step 5: Validate deployment configuration**

Require both `V9_CONTROL_PLANE_SYNC_BASE_URL` and `V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN` when either is present, keep environment files root-only, and preserve existing rollback behavior.

### Task 3: Supabase Authoritative Project Resolution

**Files:**
- Modify: `supabase/functions/ops-glasses/device-sync.test.ts`
- Modify: `supabase/functions/ops-glasses/device-sync.ts`
- Modify: `supabase/functions/ops-glasses/skill-knowledge-sync.test.ts`
- Modify: `supabase/functions/ops-glasses/skill-knowledge-sync.ts`

- [x] **Step 1: Write failing authorization tests**

Prove task events reject inactive projects, cross-organization local project collisions and revoked device/project membership. Prove manifest resolution returns only the active authoritative project UUID bound to the authenticated profile/device.

- [x] **Step 2: Run the focused Deno tests and verify RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/device-sync.test.ts supabase/functions/ops-glasses/skill-knowledge-sync.test.ts`

Expected: the new negative cases fail against the current permissive upsert/lookup path.

- [x] **Step 3: Harden project resolution**

Resolve an existing project by organization and local project ID without changing a closed/archived project back to active. Require an active profile membership and compatible active device binding before accepting events or producing the execution manifest. Keep project UUID generation inside Supabase and return no local fake identity.

- [x] **Step 4: Run Supabase focused and full tests**

Run: `deno test --allow-env supabase/functions/ops-glasses/device-sync.test.ts supabase/functions/ops-glasses/skill-knowledge-sync.test.ts` and `npm run validate:supabase`.

Expected: all tests pass.

### Task 4: Verification And Release Refresh

**Files:**
- Create: `docs/verification/2026-08-05-v9-project-identity-control-plane-sync.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [x] **Step 1: Run the complete gateway, Edge, Web and Android contract suites**

Run the current full Python, Supabase, management Web, Android JVM, typecheck/build and static validation commands. Confirm the AI provider receives one request and the project binding lookup remains local SQLite work.

- [x] **Step 2: Verify failure closure**

Exercise cloud offline, expired cloud session, revoked binding, missing manifest, stale manifest and conflicting project UUID cases. Confirm local evidence/events remain queued and no Skill, instruction or AI context is executed under the wrong project.

- [x] **Step 3: Update records and rebuild delivery artifacts**

Record exact test counts and unresolved production blockers, rebuild `output/DingdangAI-V9-9.0.0-formal-delivery.zip`, and rerun `validate:v9-release`, `validate:v9-delivery` and `git diff --check` before treating the package as current.
