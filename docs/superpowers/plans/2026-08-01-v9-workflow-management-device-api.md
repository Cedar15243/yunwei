# V9 Workflow Management And Device API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the authenticated management and device APIs that turn validated workflow drafts into signed immutable versions, resolve work-order bindings, deliver assignments by cursor, and persist device execution audit events.

**Architecture:** Keep workflow routing separate from the existing operations management and task event routes. Management commands use a role-gated gateway and server-side compiler/signer; device commands use the existing fifteen-minute device session, deterministic ownership checks, monotonic assignment cursors, and idempotent database RPCs. The API never accepts arbitrary HTTP, scripts, client credentials, or unsigned execution packages.

**Tech Stack:** Supabase Edge Functions, Deno TypeScript, PostgreSQL/RLS/RPC, Web Crypto Ed25519, existing workflow domain compiler and binding resolver.

---

### Task 1: Management API route contract and authorization

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-management.ts`
- Create: `supabase/functions/ops-glasses/workflow-management.test.ts`

- [ ] **Step 1: Write failing route tests**

Define a `WorkflowManagementGateway` fake and prove that missing/invalid bearer tokens return `401`, non-admin roles return `403`, deployed Supabase paths normalize correctly, and unknown paths return `404`.

```ts
Deno.test("allows only organization administrators to list field apps", async () => {
  const denied = await routeWorkflowManagement(
    request("GET", "/management/field-apps", "field-token"), gateway(),
  );
  const allowed = await routeWorkflowManagement(
    request("GET", "/management/field-apps", "admin-token"), gateway(),
  );
  assertEquals(denied.status, 403);
  assertEquals(allowed.status, 200);
});
```

- [ ] **Step 2: Verify RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-management.test.ts`

Expected: FAIL because `workflow-management.ts` does not exist.

- [ ] **Step 3: Implement the route shell**

Export one identity contract and one gateway contract. Normalize `/functions/v1/ops-glasses` and `/ops-glasses`, authenticate once, require `super_admin|ops_admin` for every workflow management route, and return structured JSON errors.

- [ ] **Step 4: Verify GREEN**

Run the target test and expect all route-shell tests to pass.

- [ ] **Step 5: Commit**

Commit: `feat: add workflow management route contract`

### Task 2: Field app and workflow draft CRUD with validation

**Files:**
- Modify: `supabase/functions/ops-glasses/workflow-management.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.test.ts`

- [ ] **Step 1: Write failing CRUD tests**

Cover:

```text
GET  /management/field-apps
POST /management/field-apps
GET  /management/field-apps/:appId/workflows
POST /management/field-apps/:appId/workflows
GET  /management/workflows/:workflowId
PUT  /management/workflows/:workflowId/draft
POST /management/workflows/:workflowId/validate
```

Require `appKey`, `name`, `workflowKey`, `title`, exact organization ownership, and a draft whose `workflowId` equals the target definition ID. Return compiler errors with stable `code/path` pairs and never persist an invalid draft.

- [ ] **Step 2: Verify RED**

Run the target test and confirm the first missing gateway method is the failure reason.

- [ ] **Step 3: Implement CRUD and Supabase gateway operations**

Use organization-filtered selects/inserts/updates, stamp `created_by/updated_by` from the authenticated identity, and expose only fields needed by the editor. Do not trust organization or actor IDs from request bodies.

- [ ] **Step 4: Verify GREEN and regression**

Run the target test and all `ops-glasses/*.test.ts` tests.

- [ ] **Step 5: Commit**

Commit: `feat: manage validated workflow drafts`

### Task 3: Server-only package signing and immutable publication

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-signing.ts`
- Create: `supabase/functions/ops-glasses/workflow-signing.test.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [ ] **Step 1: Write failing signing tests**

Generate an Ed25519 key in the test, sign the compiler `contentSha256`, verify with the public key, reject malformed PKCS#8/base64, and prove the response never returns private key material.

```ts
const signature = await signer.sign(packageValue.contentSha256);
assertEquals(await verify(signature, packageValue.contentSha256), true);
```

- [ ] **Step 2: Write failing publication tests**

`POST /management/workflows/:workflowId/publish` must require `PUBLISH_WORKFLOW`, a non-empty reason, a valid draft, configured signing key/key ID, and `minAppVersionCode >= 9000`. It returns `503 signing_unavailable` if server signing is unavailable and never creates an unsigned version.

- [ ] **Step 3: Verify RED**

Run the two target test files and confirm missing signer/publication behavior.

- [ ] **Step 4: Implement signer and publication gateway**

Read `WORKFLOW_SIGNING_PRIVATE_KEY_PKCS8` and `WORKFLOW_SIGNING_KEY_ID` only from Edge Function secrets. Compile on the server, sign the content hash, and call one database RPC that inserts the immutable version, increments the version number, changes definition status, and writes an audit event atomically.

- [ ] **Step 5: Verify GREEN and typecheck**

Run target tests plus `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`.

- [ ] **Step 6: Commit**

Commit: `feat: publish signed workflow versions`

### Task 4: Atomic publication and workflow execution RPCs

**Files:**
- Create: `supabase/migrations/202608010002_workflow_api_transactions.sql`
- Create: `scripts/validate-workflow-api-transactions.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write the failing migration validator**

Require these service-role-only RPCs and their transaction guards:

```text
publish_workflow_version
apply_work_order_workflow_resolution
claim_workflow_assignment
report_workflow_assignment_status
start_workflow_execution
append_workflow_step_execution
```

The validator must require organization checks, actor/device ownership, idempotency keys, legal status transitions, and audit insertion.

- [ ] **Step 2: Verify RED**

Run: `node scripts/validate-workflow-api-transactions.mjs`

Expected: FAIL because the migration does not exist.

- [ ] **Step 3: Implement the migration**

Use `security definer set search_path = ''`, revoke public/authenticated execution, grant only `service_role`, use row locking for version and assignment transitions, and return existing rows for repeated idempotency keys.

- [ ] **Step 4: Verify GREEN**

Run the new validator and `npm run validate:supabase`.

- [ ] **Step 5: Commit**

Commit: `feat: add workflow API transactions`

### Task 5: Work-order binding resolution and assignment API

**Files:**
- Modify: `supabase/functions/ops-glasses/workflow-management.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.test.ts`

- [ ] **Step 1: Write failing resolution tests**

`POST /management/work-orders/:workOrderId/resolve-workflow` must load the organization-scoped order and published rules, call `resolveWorkflowBinding`, and persist exactly one of `assigned`, `none`, or `conflict`. Creating/replacing an unstarted assignment requires `RESOLVE_WORKFLOW`, a reason, and an idempotency key. Started work cannot be rebound in place.

- [ ] **Step 2: Verify RED**

Run the target test and confirm the missing resolution method fails.

- [ ] **Step 3: Implement resolution and assignment persistence**

Convert database rows to typed `WorkOrderFacts/BindingCandidate`, ignore malformed rows through the existing resolver, and call `apply_work_order_workflow_resolution`. Never accept a workflow version ID directly from an untrusted external work order payload.

- [ ] **Step 4: Verify GREEN and regression**

Run management, workflow binding, and full Edge Function tests.

- [ ] **Step 5: Commit**

Commit: `feat: resolve and assign work order workflows`

### Task 6: Device cursor, package retrieval, and delivery state

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-device.ts`
- Create: `supabase/functions/ops-glasses/workflow-device.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [ ] **Step 1: Write failing device route tests**

Cover:

```text
GET  /device-sync/workflows/assignments?afterSequence=0&limit=50
GET  /device-sync/workflows/assignments/:assignmentId/package
POST /device-sync/workflows/assignments/:assignmentId/status
```

Reject bootstrap/expired sessions, another engineer/device/organization, invalid cursors, unsupported schema/capabilities, backward transitions, and direct `revoked` reports. Return only assignment metadata in cursor results; the package endpoint returns the signed package without credentials or arbitrary URLs.

- [ ] **Step 2: Verify RED**

Run the target test and confirm the module is missing.

- [ ] **Step 3: Implement device gateway**

Reuse the existing device-session authenticator. Query by organization, assigned profile, optional claimed device, and increasing `delivery_sequence`; cap the page at 100. Package retrieval and status reports must claim/verify the assignment through RPC before returning executable content.

- [ ] **Step 4: Verify GREEN and typecheck**

Run target tests, all Edge Function tests, and Deno typecheck.

- [ ] **Step 5: Commit**

Commit: `feat: deliver signed workflows to devices`

### Task 7: Device execution and step audit API

**Files:**
- Modify: `supabase/functions/ops-glasses/workflow-device.ts`
- Modify: `supabase/functions/ops-glasses/workflow-device.test.ts`

- [ ] **Step 1: Write failing execution tests**

Cover:

```text
POST /device-sync/workflows/executions
POST /device-sync/workflows/executions/:executionId/steps
```

Starting requires an owned `ready|active` assignment, local task identity, current node, and idempotency key. Step reports require a node present in the signed package, legal step status, object-shaped input/output/transition data, evidence UUIDs, and no client-supplied organization/operator/device identity.

- [ ] **Step 2: Verify RED**

Run the target test and confirm execution methods are missing.

- [ ] **Step 3: Implement execution commands**

Call `start_workflow_execution` and `append_workflow_step_execution`; return existing results for duplicate idempotency keys. Never allow a completed/cancelled execution to accept new active steps.

- [ ] **Step 4: Verify GREEN and regression**

Run workflow-device tests and all Edge Function tests.

- [ ] **Step 5: Commit**

Commit: `feat: persist workflow execution audit`

### Task 8: API verification evidence and phase closeout

**Files:**
- Create: `docs/verification/2026-08-01-v9-workflow-management-device-api.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: Run complete local verification**

```powershell
deno test --allow-env supabase/functions/ops-glasses/*.test.ts
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
npm run validate:supabase
deno fmt --check supabase/functions/ops-glasses/*.ts
git diff --check
```

- [ ] **Step 2: Record exact evidence and limitations**

State test totals, commits, routes, RPC contracts, and negative cases. Keep real Postgres/Supabase execution, live signing secrets, push transport, Android runtime, and Air3 verification explicitly unverified until direct evidence exists.

- [ ] **Step 3: Commit**

Commit: `docs: verify workflow management and device APIs`
