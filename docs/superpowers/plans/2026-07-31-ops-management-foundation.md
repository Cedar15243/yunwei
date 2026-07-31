# Operations Management Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build authenticated web visibility for Air3 tasks, photos, and short videos without changing the Air3 HUD or expert collaboration application.

**Architecture:** Add organization-scoped projects, tasks, events, and media to Supabase. Extend the existing Edge Function with authenticated management endpoints. A separate React/Vite application consumes those endpoints. Android writes events through a background queue only after current operations succeed.

**Tech Stack:** Android Java, Supabase Postgres/Storage/Edge Functions, React 19, TypeScript, Vite, Vitest.

**Scope lock:** Do not change `expert-collab-web/`, `expert-collab-server/`, TRTC, expert deployment, `voice-first-hud.html`, or `HudWebPresentation.java`. Do not add Huafang work orders.

---

### Task 1: Add Organization-Scoped Schema

**Files:**
- Create: `supabase/migrations/202607310002_ops_management_foundation.sql`
- Create: `scripts/validate-ops-management.mjs`

- [ ] **Step 1: Write the failing schema validator**

```js
for (const table of ["organizations", "ops_profiles", "glasses_devices", "ops_projects", "maintenance_tasks", "task_events", "media_assets", "audit_events"]) {
  assert.match(sql, new RegExp(`create table public\\.${table}\\b`, "i"));
}
assert.match(sql, /create unique index task_events_idempotency_idx/i);
assert.match(sql, /enable row level security/i);
```

- [ ] **Step 2: Run `node scripts/validate-ops-management.mjs` and verify failure**

Expected: migration is absent.

- [ ] **Step 3: Create the schema and RLS policies**

```sql
create table public.maintenance_tasks (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  project_id uuid not null references public.ops_projects(id) on delete cascade,
  created_by uuid not null references public.ops_profiles(id),
  local_task_id text not null,
  status text not null check (status in ('active','completed','closed','aborted')),
  title text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, local_task_id)
);
create table public.task_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  task_id uuid not null references public.maintenance_tasks(id) on delete cascade,
  idempotency_key text not null,
  event_type text not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create unique index task_events_idempotency_idx on public.task_events(task_id, idempotency_key);
```

Create `media_assets` with task/event IDs, kind, content type, storage path, SHA-256, byte size, upload state, and timestamp. Enable RLS on every new table; policies require `auth.uid()` organization membership and do not grant `anon` access.

- [ ] **Step 4: Run `node scripts/validate-ops-management.mjs` and verify pass**

- [ ] **Step 5: Commit with `git add supabase/migrations/202607310002_ops_management_foundation.sql scripts/validate-ops-management.mjs && git commit -m "feat: add operations management data foundation"`**

### Task 2: Add Authorized Management API

**Files:**
- Create: `supabase/functions/ops-glasses/management.ts`
- Create: `supabase/functions/ops-glasses/management.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [ ] **Step 1: Write failing route tests**

```ts
it("rejects task lists without bearer authentication", async () => {
  expect((await routeManagement(new Request("https://ops/management/tasks"), deps)).status).toBe(401);
});
it("returns only the authenticated organization tasks", async () => {
  const result = await routeManagement(authorizedRequest("/management/tasks"), fakeDeps);
  expect(await result.json()).toEqual({ items: [expect.objectContaining({ id: "task-a" })], nextCursor: null });
});
it("returns signed media only for an authorized task", async () => {
  const result = await routeManagement(authorizedRequest("/management/tasks/task-a"), fakeDeps);
  expect((await result.json()).media[0].url).toContain("signed.example");
});
```

- [ ] **Step 2: Run `deno test --allow-env supabase/functions/ops-glasses/management.test.ts` and verify failure**

- [ ] **Step 3: Implement narrow routes**

Verify `Authorization: Bearer` through `supabase.auth.getUser`. Implement only `GET /management/dashboard`, `GET /management/projects`, `GET /management/tasks`, `GET /management/tasks/:taskId`, and `POST /management/tasks/:taskId/media/:mediaId/retry`. Return task detail as `{ task, events, messages, media, steps }`; create short-lived media URLs only after organization and task checks. Existing `/sessions/*` behavior remains unchanged.

- [ ] **Step 4: Run `deno test --allow-env supabase/functions/ops-glasses/management.test.ts` and `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`**

- [ ] **Step 5: Commit with `git add supabase/functions/ops-glasses && git commit -m "feat: expose authorized operations management API"`**

### Task 3: Add UI-Neutral Android Event Queue

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncEvent.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncQueue.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncClient.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/TaskSyncQueueTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] **Step 1: Write failing queue tests**

```java
@Test public void coalescesSameIdempotencyKey() {
    queue.enqueue(event("task-a", "key-1"));
    queue.enqueue(event("task-a", "key-1"));
    assertThat(queue.pending()).hasSize(1);
}
@Test public void leavesFailedEventReadyForRetry() {
    queue.enqueue(event("task-a", "key-1"));
    queue.markFailed("key-1", "timeout");
    assertThat(queue.nextReady().idempotencyKey()).isEqualTo("key-1");
}
```

- [ ] **Step 2: Run `./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.TaskSyncQueueTest` and verify failure**

- [ ] **Step 3: Implement local-first queue and client**

Make events immutable with project/task local IDs, event type, JSON payload, timestamp, and idempotency key. Persist an independent app-private queue file and never modify the present chat-project persistence format. Send one worker request at a time with exponential backoff capped at 60 seconds. Enqueue only after current photo, retained video, finalized user message, finalized AI reply, step transition, or task completion succeeds. Do not alter any HUD, view construction, navigation, expert, camera, or audio code.

- [ ] **Step 4: Run queue and task tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.TaskSyncQueueTest --tests com.codex.air3nativecamera.task.TaskSessionManagerTest`

- [ ] **Step 5: Commit with `git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java && git commit -m "feat: sync Air3 task events without UI changes"`**

### Task 4: Add Background Video Upload

**Files:**
- Modify: `TaskSyncClient.java`
- Modify: `management.ts`
- Modify: `202607310002_ops_management_foundation.sql`
- Modify: `TaskSyncQueueTest.java`

- [ ] **Step 1: Write failing video lifecycle tests**

```java
@Test public void queuesVideoBeforeTransfer() {
    client.enqueueVideo(task, videoFile);
    assertThat(queue.pending().get(0).eventType()).isEqualTo("video_recorded");
}
@Test public void retainsVideoAfterUploadFailure() {
    client.onUploadFailed("video-key", "network");
    assertThat(videoFile.exists()).isTrue();
}
```

- [ ] **Step 2: Run the queue test and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.TaskSyncQueueTest`

- [ ] **Step 3: Add upload intent and completion flow**

Create `POST /management/tasks/:taskId/media/upload-intents` and `POST /management/tasks/:taskId/media/:mediaId/complete`. The intent creates a queued `media_assets` row and returns one short-lived upload URL. Upload MP4 only from the background client; retain the local file until upload and completion both succeed. Validate byte size and SHA-256 server-side.

- [ ] **Step 4: Run Android queue tests and management Deno tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.codex.air3nativecamera.sync.TaskSyncQueueTest`

Run: `deno test --allow-env supabase/functions/ops-glasses/management.test.ts`

- [ ] **Step 5: Commit with `git add air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync supabase/functions/ops-glasses/management.ts supabase/migrations/202607310002_ops_management_foundation.sql && git commit -m "feat: upload retained Air3 video evidence"`**

### Task 5: Build the Management Web Shell

**Files:**
- Create: `ops-management-web/package.json`
- Create: `ops-management-web/vite.config.ts`
- Create: `ops-management-web/src/main.tsx`
- Create: `ops-management-web/src/App.tsx`
- Create: `ops-management-web/src/auth/AuthProvider.tsx`
- Create: `ops-management-web/src/styles/tokens.css`
- Create: `ops-management-web/src/styles/app.css`
- Create: `ops-management-web/src/App.test.tsx`

- [ ] **Step 1: Write failing login and navigation tests**

```tsx
it("shows sign in without a session", () => {
  render(<App auth={unauthenticatedAuth} />);
  expect(screen.getByRole("heading", { name: "叮当 AI 运维管理平台" })).toBeVisible();
  expect(screen.getByLabelText("账号邮箱")).toBeVisible();
});
it("keeps navigation after selecting tasks", async () => {
  render(<App auth={authenticatedAuth} />);
  await userEvent.click(screen.getByRole("link", { name: "项目与任务" }));
  expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
});
```

- [ ] **Step 2: Run `npm --prefix ops-management-web test -- --run` and verify failure**

- [ ] **Step 3: Implement the authenticated desktop shell**

Use Supabase Auth email/password. Require `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, and `VITE_OPS_API_BASE_URL`; fail closed before API calls when missing. Implement only working navigation: 工作台, 项目与任务, 媒体中心, 人员与设备. Use `--fog:#edf1ee`, `--glass:rgba(255,255,255,.72)`, `--ink:#14211d`, `--mint:#2c8d72`, and fine grid lines. Use fixed desktop navigation, compact command strip, and wide unframed work surface. Do not create empty feature entries, dark default dashboards, marketing heroes, or nested cards.

- [ ] **Step 4: Run tests and `npm --prefix ops-management-web run build`**

- [ ] **Step 5: Commit with `git add ops-management-web && git commit -m "feat: add operations management web shell"`**

### Task 6: Build Dashboard, Timeline, and Media Viewer

**Files:**
- Create: `ops-management-web/src/api/management-api.ts`
- Create: `ops-management-web/src/features/dashboard/DashboardPage.tsx`
- Create: `ops-management-web/src/features/tasks/TaskListPage.tsx`
- Create: `ops-management-web/src/features/tasks/TaskTimelinePage.tsx`
- Create: `ops-management-web/src/features/media/MediaCenterPage.tsx`
- Create: `ops-management-web/src/features/tasks/TaskTimelinePage.test.tsx`

- [ ] **Step 1: Write failing timeline tests**

```tsx
it("renders photo, video, user message and AI reply in chronological order", async () => {
  render(<TaskTimelinePage taskId="task-a" api={fakeApi} />);
  expect(await screen.findByText("现场照片")).toBeVisible();
  expect(screen.getByText("AI 运维回复")).toBeVisible();
  expect(screen.getByRole("video")).toHaveAttribute("src", "https://signed.example/video.mp4");
});
it("hides media retry from read-only users", async () => {
  render(<TaskTimelinePage taskId="task-a" api={readOnlyApi} />);
  expect(screen.queryByRole("button", { name: "重传媒体" })).toBeNull();
});
```

- [ ] **Step 2: Run `npm --prefix ops-management-web test -- --run src/features/tasks/TaskTimelinePage.test.tsx` and verify failure**

- [ ] **Step 3: Implement real projections**

The API client attaches the browser access token. Dashboard uses `/management/dashboard`; task list uses cursor pagination and filters; timeline uses signed media URLs and server order; retry appears only with `canRetryMedia`. The browser must never construct object-storage paths.

- [ ] **Step 4: Run `npm --prefix ops-management-web test -- --run`, `npm --prefix ops-management-web run typecheck`, and `npm --prefix ops-management-web run build`**

- [ ] **Step 5: Commit with `git add ops-management-web scripts/validate-ops-management.mjs && git commit -m "feat: show field task timelines and evidence"`**

### Task 7: Verify UI Freeze and Bounded Delivery

**Files:**
- Modify: `scripts/validate-ops-management.mjs`
- Create: `docs/verification/2026-07-31-ops-management-foundation.md`

- [ ] **Step 1: Add the freeze assertion**

```js
for (const file of [
  "air3-dingdang-expert-integrated-app/app/src/main/assets/voice-first-hud.html",
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/ui/hud/HudWebPresentation.java",
  "expert-collab-web/src/App.tsx",
  "expert-collab-server/src/server.ts",
]) assert.equal(gitDiffNameOnly.includes(file), false, `${file} must remain unchanged`);
```

- [ ] **Step 2: Run bounded verification**

Run: `node scripts/validate-ops-management.mjs`

Run: `./gradlew.bat :app:testDebugUnitTest`

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run build`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`

Run: `git diff --check`

Expected: every command exits 0. Do not build or install an APK until explicit user approval after this milestone.

- [ ] **Step 3: Write exact command outcomes and commit with `git add scripts/validate-ops-management.mjs docs/verification/2026-07-31-ops-management-foundation.md && git commit -m "test: verify operations management foundation"`**

## Later Plans

After this foundation is accepted, create separate implementation plans for skills/knowledge publishing, voiceprint control, expert-task integration after a confirmed maintenance window, and Huafang work-order integration after formal API contracts.
