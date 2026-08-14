import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/20260808055012_equipment_catalog_management.sql",
  import.meta.url,
);
const managementPath = new URL(
  "../supabase/functions/ops-glasses/management.ts",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "equipment catalog management migration is missing");

const sql = readFileSync(migrationPath, "utf8");
const management = readFileSync(managementPath, "utf8");

for (const contract of [
  /alter table public\.audit_events[\s\S]*?add column if not exists idempotency_key text/i,
  /create unique index if not exists audit_events_equipment_idempotency_idx/i,
  /action in \('equipment_created', 'equipment_updated'\)/i,
  /create or replace function public\.manage_ops_equipment\(/i,
  /security definer/i,
  /set search_path = ''/i,
  /operation_name is null or operation_name not in \('create', 'update'\)/i,
  /profile\.role in \('super_admin', 'ops_admin'\)/i,
  /profile\.active = true/i,
  /profile\.status = 'active'/i,
  /command_idempotency_key is null\s+or command_idempotency_key !~/i,
  /command_hash is null or command_hash !~/i,
  /target_equipment_key is null/i,
  /target_quantity is null/i,
  /target_status is null/i,
  /target_fault_count is null/i,
  /target_repair_count is null/i,
  /count\(distinct project_id\)/i,
  /project\.organization_id = target_organization_id/i,
  /pg_advisory_xact_lock\(hashtextextended/i,
  /audit\.metadata ->> 'commandHash'/i,
  /from public\.ops_equipment equipment[\s\S]*?for update/i,
  /current_updated_at is distinct from expected_updated_at/i,
  /delete from public\.ops_equipment_projects/i,
  /insert into public\.ops_equipment_projects/i,
  /insert into public\.audit_events/i,
]) {
  assert.match(sql, contract, `equipment migration missing contract: ${contract}`);
}

for (const role of ["public", "anon", "authenticated"]) {
  assert.match(
    sql,
    new RegExp(
      `revoke all on function public\\.manage_ops_equipment\\([\\s\\S]*?\\) from ${role};`,
      "i",
    ),
    `manage_ops_equipment must be revoked from ${role}`,
  );
}
assert.match(
  sql,
  /grant execute on function public\.manage_ops_equipment\([\s\S]*?\) to service_role;/i,
  "manage_ops_equipment must be service-role only",
);

for (const marker of [
  'request.method === "POST" && path === "/management/equipment"',
  'request.method === "PUT" && equipmentUpdate',
  'supabase.rpc("manage_ops_equipment"',
  "command_hash: commandHash",
  "equipment_version_conflict",
  "equipment_idempotency_conflict",
  "equipment_management_unavailable",
]) {
  assert.ok(management.includes(marker), `equipment management Edge contract missing ${marker}`);
}

console.log("equipment catalog management contract passed");
