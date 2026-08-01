import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202607310005_short_lived_device_sessions.sql",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "short-lived device session migration is missing");
const sql = readFileSync(migrationPath, "utf8");

for (const contract of [
  /create table public\.glasses_device_sessions\b/i,
  /bootstrap_token_id uuid not null references public\.glasses_device_tokens/i,
  /token_hash text not null unique/i,
  /expires_at timestamptz not null/i,
  /last_used_at timestamptz/i,
  /create or replace function public\.resolve_glasses_bootstrap_credential/i,
  /create or replace function public\.issue_glasses_device_session/i,
  /create or replace function public\.resolve_glasses_device_session/i,
  /create trigger revoke_device_sessions_with_bootstrap/i,
  /new_expires_at > now\(\)/i,
  /new_expires_at <= now\(\) \+ interval '15 minutes'/i,
  /bootstrap\.status = 'active'/i,
  /bootstrap\.expires_at > now\(\)/i,
  /device\.status <> 'disabled'/i,
  /binding\.status = 'active'/i,
  /profile\.status = 'active'/i,
  /enable row level security/i,
]) {
  assert.match(sql, contract);
}

for (const functionName of [
  "resolve_glasses_bootstrap_credential",
  "issue_glasses_device_session",
  "resolve_glasses_device_session",
]) {
  assert.match(
    sql,
    new RegExp(`grant execute on function public\\.${functionName}`, "i"),
    `${functionName} must be service-role only`,
  );
}

console.log("short-lived device session schema contract passed");
