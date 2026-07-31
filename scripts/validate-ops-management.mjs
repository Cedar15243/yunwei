import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const migrationPath = new URL("../supabase/migrations/202607310002_ops_management_foundation.sql", import.meta.url);
const sql = readFileSync(migrationPath, "utf8");

for (const table of [
  "organizations",
  "ops_profiles",
  "glasses_devices",
  "ops_projects",
  "maintenance_tasks",
  "task_events",
  "media_assets",
  "audit_events",
]) {
  assert.match(sql, new RegExp(`create table public\\.${table}\\b`, "i"));
}

assert.match(sql, /create unique index task_events_idempotency_idx/i);
assert.match(sql, /enable row level security/i);

console.log("ops-management schema contract passed");
