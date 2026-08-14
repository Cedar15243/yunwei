# V9 Equipment Catalog Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add audited, idempotent equipment catalog creation and editing that atomically updates project bindings and automatically feeds the existing glasses read path.

**Architecture:** The management Web calls two authenticated Edge routes. Edge validates the public contract and invokes a service-role-only PostgreSQL RPC that performs optimistic concurrency, equipment persistence, project-link replacement, and immutable audit insertion in one transaction. The glasses client remains read-only and consumes the resulting catalog through its existing device-session endpoint.

**Tech Stack:** PostgreSQL/Supabase migrations and RPC, Deno Edge Functions, React 19, TypeScript, Vitest, Testing Library, Playwright, Android JVM tests.

---

### Task 1: Database Transaction Contract

**Files:**
- Create: `supabase/migrations/*_equipment_catalog_management.sql`
- Test: `supabase/functions/ops-glasses/management.test.ts`

- [ ] Add failing route/gateway tests for create, edit, idempotency and conflict behavior.
- [ ] Create the migration with `npx supabase migration new equipment_catalog_management`.
- [ ] Add the partial audit idempotency index and `manage_ops_equipment` transaction RPC.
- [ ] Revoke RPC execution from `PUBLIC`, `anon` and `authenticated`; grant only `service_role`.
- [ ] Run management and migration validation tests.

### Task 2: Edge Management API

**Files:**
- Modify: `supabase/functions/ops-glasses/management.ts`
- Modify: `supabase/functions/ops-glasses/management.test.ts`

- [ ] Add strict DTO parsers for equipment fields, project UUID arrays, ISO timestamps, reason, confirmation and idempotency key.
- [ ] Add `POST /management/equipment` and `PUT /management/equipment/:id` routes.
- [ ] Add gateway methods that call the transaction RPC and reload the authorized equipment DTO.
- [ ] Map uniqueness, version conflict and unavailable RPC failures to stable HTTP errors.
- [ ] Run `deno test` and `deno check`.

### Task 3: Web API and Dialog

**Files:**
- Modify: `ops-management-web/src/api/management-api.ts`
- Modify: `ops-management-web/src/features/devices/DevicesPage.tsx`
- Modify: `ops-management-web/src/features/devices/DevicesPage.test.tsx`
- Modify: `ops-management-web/src/features/devices/devices.css`

- [ ] Add failing tests for creating and editing equipment, selecting projects, preserving form values on errors and refreshing after success.
- [ ] Add typed create/update commands with generated idempotency keys and fixed confirmation values.
- [ ] Add the “登记设备” toolbar command and per-row edit icon.
- [ ] Add a reusable equipment dialog using the existing compact form and governance patterns.
- [ ] Keep desktop five-column and phone two-column layouts stable.

### Task 4: Verification and Delivery Evidence

**Files:**
- Modify: `design/v9-functional-closure-audit.md`
- Modify: `docs/audits/2026-08-06-v9-completion-matrix.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] Run Edge targeted/full tests and Deno type checking.
- [ ] Run Web targeted/full tests, TypeScript checking, production build and desktop/mobile Playwright.
- [ ] Run Android device-memory read-only regression and V9 validation gates.
- [ ] Run `git diff --check` and sensitive-data scans.
- [ ] Rebuild formal APK/delivery ZIP only when current signing inputs are available; otherwise keep the delivery artifact marked stale.
