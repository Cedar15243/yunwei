import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202608010003_workflow_binding_rule_management.sql",
  import.meta.url,
);
const edgeFunctionPath = new URL(
  "../supabase/functions/ops-glasses/index.ts",
  import.meta.url,
);

assert.ok(
  existsSync(migrationPath),
  "workflow binding rule management migration is missing",
);

const sql = readFileSync(migrationPath, "utf8");
const edgeFunction = readFileSync(edgeFunctionPath, "utf8");
for (const route of [
  "/management/workflow-catalog",
  "/management/work-orders",
  "/management/workflow-binding-rules",
]) {
  assert.match(
    edgeFunction,
    new RegExp(`path === ["']${route.replaceAll("/", "\\/")}["']`),
    `${route} must be dispatched by the deployed Edge Function entry`,
  );
}
assert.match(
  sql,
  /alter table public\.workflow_binding_rules[\s\S]*?add column version integer not null default 1/i,
  "binding rules require an optimistic concurrency version",
);
assert.match(
  sql,
  /create table public\.workflow_binding_rule_command_events/i,
  "binding rule commands require an idempotency event table",
);
assert.match(
  sql,
  /unique\s*\(organization_id, idempotency_key\)/i,
  "binding rule idempotency must be organization scoped",
);

const functionNames = [
  "create_workflow_binding_rule",
  "update_workflow_binding_rule",
];

function functionBody(functionName) {
  const match = sql.match(
    new RegExp(
      `create or replace function public\\.${functionName}\\([\\s\\S]*?\\n\\$\\$;`,
      "i",
    ),
  );
  assert.ok(match, `${functionName} RPC is missing`);
  return match[0];
}

for (const functionName of functionNames) {
  const body = functionBody(functionName);
  for (const contract of [
    /security definer/i,
    /set search_path = ''/i,
    /from public\.ops_profiles/i,
    /profile\.role in \('super_admin', 'ops_admin'\)/i,
    /from public\.organizations[\s\S]*?for update/i,
    /idempotency_key/i,
    /from public\.workflow_versions/i,
    /status\s*=\s*'published'/i,
    /jsonb_typeof/i,
    /insert into public\.audit_events/i,
  ]) {
    assert.match(body, contract, `${functionName} is missing a safety contract`);
  }
  assert.doesNotMatch(
    body.split(/returns/i, 1)[0],
    /organization_id/i,
    `${functionName} must not accept client-controlled organization scope`,
  );
  assert.match(
    sql,
    new RegExp(
      `revoke all on function public\\.${functionName}\\([\\s\\S]*?from public, anon, authenticated;`,
      "i",
    ),
    `${functionName} must be revoked from browser roles`,
  );
  assert.match(
    sql,
    new RegExp(
      `grant execute on function public\\.${functionName}\\([\\s\\S]*?to service_role;`,
      "i",
    ),
    `${functionName} must be service-role only`,
  );
}

const createRule = functionBody("create_workflow_binding_rule");
for (const contract of [
  /from public\.workflow_binding_rule_command_events/i,
  /insert into public\.workflow_binding_rules/i,
  /insert into public\.workflow_binding_rule_command_events/i,
  /'workflow_binding_rule\.created'/i,
]) {
  assert.match(createRule, contract, "create rule RPC must be atomic and idempotent");
}

const updateRule = functionBody("update_workflow_binding_rule");
for (const contract of [
  /from public\.workflow_binding_rules[\s\S]*?for update/i,
  /version\s*<>\s*expected_version/i,
  /errcode\s*=\s*'40001'/i,
  /version\s*=\s*version\s*\+\s*1/i,
  /insert into public\.workflow_binding_rule_command_events/i,
  /'workflow_binding_rule\.updated'/i,
]) {
  assert.match(updateRule, contract, "update rule RPC must enforce optimistic concurrency");
}

assert.match(
  sql,
  /alter table public\.workflow_binding_rule_command_events enable row level security/i,
  "binding rule command events require RLS",
);
assert.match(
  sql,
  /revoke all on table public\.workflow_binding_rule_command_events from public, anon, authenticated/i,
  "binding rule command events must not be browser writable",
);
assert.doesNotMatch(
  sql,
  /grant\s+execute[\s\S]{0,300}\sto\s+(anon|authenticated)/i,
  "binding rule write RPCs must not be executable by browser roles",
);

console.log("workflow binding rule management contract passed");
