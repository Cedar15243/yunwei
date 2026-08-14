# V9 Operations Record Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the organization-scoped task and media record center with validated search/filter/pagination, authorized CSV export, smallest-failure retry, and no N+1 media loading.

**Architecture:** Extend the existing `ops-glasses` management route instead of creating a second service. The Edge Function validates every filter and derives project scope from the authenticated profile; task and media cursors remain opaque to the Web client. The React workbench consumes paged contracts and preserves the existing Product Design layout.

**Tech Stack:** Supabase Edge Functions/Deno/PostgREST, React 19/Vite/TypeScript, Vitest/Testing Library, Node contract validators.

---

### Task 1: Typed Task Query and Export Contract

**Files:**
- Modify: `supabase/functions/ops-glasses/management.ts`
- Modify: `supabase/functions/ops-glasses/management.test.ts`

- [x] **Step 1: Write failing route tests**

Add tests proving `/management/tasks` accepts only `status`, `projectId`, `query`, `before`, and `limit`; rejects repeated/unknown/invalid filters; passes a typed filter object to the gateway; and returns `400 invalid_request` before database access.

- [x] **Step 2: Run the tests and confirm RED**

Run: `deno test --allow-env --allow-net supabase/functions/ops-glasses/management.test.ts`

Expected: the new filter-validation tests fail because the route still forwards raw `URLSearchParams`.

- [x] **Step 3: Implement typed filters and stable pagination**

Add `TaskListFilters` with validated status, UUID project ID, title query up to 120 characters, page size 1-100, and an opaque cursor containing `updatedAt` plus task `id`. Query `limit + 1`, order by `updated_at desc, id desc`, apply organization/project scope, and return the next cursor only when another page exists.

- [x] **Step 4: Add authorized CSV export**

Add `GET /management/tasks/export` before the task-detail route. Reuse the validated status/project/query filters, cap exports at 5,000 rows, escape CSV cells, return UTF-8 BOM CSV with `Cache-Control: no-store`, and fail with `task_export_too_large` instead of truncating silently.

- [x] **Step 5: Run management tests and Deno check**

Run:

```powershell
deno test --allow-env --allow-net supabase/functions/ops-glasses/management.test.ts
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
```

Expected: all management tests and type checking pass.

### Task 2: Server-side Media Record Endpoint

**Files:**
- Modify: `supabase/functions/ops-glasses/management.ts`
- Modify: `supabase/functions/ops-glasses/management.test.ts`

- [x] **Step 1: Write failing media-list tests**

Add tests for `GET /management/media` covering organization/project scope, `kind`, `uploadStatus`, `taskId`, `before`, and `limit`; include signed URLs only for synced assets and `canRetryMedia` only for authorized administrators with failed assets.

- [x] **Step 2: Run RED test**

Run: `deno test --allow-env --allow-net supabase/functions/ops-glasses/management.test.ts`

Expected: `404` or missing gateway method before implementation.

- [x] **Step 3: Implement paged media DTO**

Return media rows with task ID/title, project ID, kind, content type, status, failure reason, captured time, signed URL, and retry permission. Batch-load task metadata instead of requesting every task detail, and return an opaque created-at/id cursor.

- [x] **Step 4: Verify backend**

Run the management test and Deno check commands from Task 1.

Expected: all tests pass and no raw storage path is returned to the browser.

### Task 3: Web API and Download Client

**Files:**
- Modify: `ops-management-web/src/api/management-api.ts`
- Modify: `ops-management-web/src/api/identity-device-api.test.ts`

- [x] **Step 1: Write failing API tests**

Add tests proving task/media filters are URL encoded, cursor/limit are forwarded once, task export uses the bearer session and returns a Blob, and failed download responses use `ManagementApiError`.

- [x] **Step 2: Run RED test**

Run: `npm --prefix ops-management-web test -- --run src/api/identity-device-api.test.ts`

- [x] **Step 3: Implement typed client methods**

Add `TaskListFilters`, `MediaListFilters`, `MediaRecord`, `PagedResult<T>`, `getMedia`, and `exportTasks`. Keep JSON requests on the existing helper and use a dedicated authenticated Blob download helper for CSV.

- [x] **Step 4: Run API tests and typecheck**

Run:

```powershell
npm --prefix ops-management-web test -- --run src/api/identity-device-api.test.ts
npm --prefix ops-management-web run typecheck
```

### Task 4: Task Record Workbench

**Files:**
- Modify: `ops-management-web/src/features/tasks/TaskListPage.tsx`
- Create: `ops-management-web/src/features/tasks/TaskListPage.test.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [x] **Step 1: Write failing interaction tests**

Cover explicit title search, status/project filters, loading/empty/error/retry, append-only “load next page”, duplicate-row protection, filter reset, and CSV export command state.

- [x] **Step 2: Run RED test**

Run: `npm --prefix ops-management-web test -- --run src/features/tasks/TaskListPage.test.tsx`

- [x] **Step 3: Implement the workbench**

Use a compact search input plus icon command, existing selects, reset command, download icon, and stable action dimensions. Fetch the first page on applied filters, append subsequent pages by cursor, preserve the existing row layout, and surface recoverable errors without discarding already loaded records.

- [x] **Step 4: Verify TaskList UI**

Run the TaskList test plus full TypeScript check.

### Task 5: Media Center Without N+1 Requests

**Files:**
- Modify: `ops-management-web/src/features/media/MediaCenterPage.tsx`
- Create: `ops-management-web/src/features/media/MediaCenterPage.test.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [x] **Step 1: Write failing interaction tests**

Prove the page calls only `getMedia`, filters by evidence kind/status, loads the next page, shows the precise failure reason, retries the smallest failed asset, preserves loaded records on retry failure, and never calls `getTask` per tile.

- [x] **Step 2: Run RED test**

Run: `npm --prefix ops-management-web test -- --run src/features/media/MediaCenterPage.test.tsx`

- [x] **Step 3: Implement the paged media center**

Replace the current task-detail fan-out with the server-side media endpoint. Keep real signed media rendering, add compact filters and retry controls, and reuse existing visual language without modifying the expert Web or glasses HUD.

- [x] **Step 4: Verify Media Center UI**

Run the MediaCenter test plus full TypeScript check.

### Task 6: Release Contracts and Evidence

**Files:**
- Modify: `scripts/validate-identity-device-control-plane.mjs`
- Modify: `docs/superpowers/plans/2026-07-31-v9-delivery-roadmap.md`
- Modify: `design/v9-functional-closure-audit.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [x] **Step 1: Add static contract markers**

Require typed task filters, the media endpoint, CSV export, paged Web API methods, and the removal of the Media Center task-detail fan-out.

- [x] **Step 2: Run full regression**

Run:

```powershell
npm run validate:supabase
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
py -3 -m unittest discover -s v9-ops-gateway -p 'test_*.py'
npm --prefix ops-management-web test -- --run
npm --prefix ops-management-web run typecheck
npm --prefix ops-management-web run build
npm run validate:v9-release
npm run validate:native-build
npm run validate:native-hud
npm run validate:ai-brain
git diff --check
```

- [x] **Step 3: Rebuild and validate formal delivery**

Run:

```powershell
npm run package:v9-delivery
npm run validate:v9-delivery
```

Record the current ZIP/APK SHA256 and leave production login, cloud deployment, MVS write-back, supplier approval, and Air3 real-device performance as explicit external gates.

## Self-review

- Spec coverage: closes task record search/filter/page/export, media/sync-failure drilldown, smallest-operation retry, and N+1 latency risk from sections G and H.
- Scope boundary: does not change the glasses HUD, Camera2, expert collaboration, stable APK, MVS write-back, or provider credentials.
- Security: all filters are server validated, export is scoped by authenticated identity, storage paths remain private, and no client filter is treated as authorization.
- Execution note: the worktree already contains the full V9 program. Do not create commits or stage broad changes automatically; preserve existing user/previous-phase modifications.
