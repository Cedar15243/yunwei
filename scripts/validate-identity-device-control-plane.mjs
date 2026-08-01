import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202607310004_identity_device_control_plane.sql",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "identity/device control-plane migration is missing");
const sql = readFileSync(migrationPath, "utf8");

for (const contract of [
  /create type public\.ops_profile_status/i,
  /create table public\.ops_project_memberships\b/i,
  /create table public\.device_bindings\b/i,
  /device_bindings_active_device_idx/i,
  /add column model text/i,
  /add column app_version text/i,
  /add column mdm_policy_version text/i,
  /add column mdm_compliance_status text/i,
  /create or replace function public\.set_ops_profile_status/i,
  /create or replace function public\.set_ops_project_membership/i,
  /create or replace function public\.revoke_ops_project_membership/i,
  /create or replace function public\.rotate_glasses_device_token/i,
  /create or replace function public\.bind_glasses_device/i,
  /create or replace function public\.unbind_glasses_device/i,
  /create or replace function public\.revoke_glasses_device/i,
  /create trigger audit_events_immutable/i,
  /revoke_reason = 'device_rebound'/i,
  /enable row level security/i,
]) {
  assert.match(sql, contract);
}

for (const functionName of [
  "set_ops_profile_status",
  "set_ops_project_membership",
  "revoke_ops_project_membership",
  "rotate_glasses_device_token",
  "bind_glasses_device",
  "unbind_glasses_device",
  "revoke_glasses_device",
]) {
  assert.match(
    sql,
    new RegExp(`grant execute on function public\\.${functionName}`, "i"),
    `${functionName} must be service-role only`,
  );
}

console.log("identity-device control-plane schema contract passed");
