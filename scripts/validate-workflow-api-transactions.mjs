import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202608010002_workflow_api_transactions.sql",
  import.meta.url,
);

assert.ok(
  existsSync(migrationPath),
  "workflow API transaction migration is missing",
);

const sql = readFileSync(migrationPath, "utf8");
assert.match(
  sql,
  /alter table public\.work_orders[\s\S]*?add column external_workflow_code text/i,
  "trusted external workflow mapping requires a structured work-order field",
);
const functionNames = [
  "publish_workflow_version",
  "apply_work_order_workflow_resolution",
  "claim_workflow_assignment",
  "report_workflow_assignment_status",
  "start_workflow_execution",
  "append_workflow_step_execution",
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
  assert.match(body, /security definer/i, `${functionName} must be security definer`);
  assert.match(
    body,
    /set search_path = ''/i,
    `${functionName} must use an empty search_path`,
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
  assert.match(
    body,
    /organization_id/i,
    `${functionName} must enforce organization scope`,
  );
  assert.match(
    body,
    /insert into public\.audit_events/i,
    `${functionName} must append an audit event`,
  );
}

const publish = functionBody("publish_workflow_version");
for (const contract of [
  /from public\.workflow_definitions[\s\S]*?for update/i,
  /latest_version_number\s*\+\s*1/i,
  /insert into public\.workflow_versions/i,
  /publication_idempotency_key/i,
  /return previous_publication/i,
  /profile\.role in \('super_admin', 'ops_admin'\)/i,
  /profile\.organization_id\s*=\s*definition\.organization_id/i,
]) {
  assert.match(publish, contract, "workflow publication must be atomic and authorized");
}
assert.match(
  readFileSync(
    new URL("../supabase/migrations/202608010001_configurable_field_workflows.sql", import.meta.url),
    "utf8",
  ),
  /create unique index workflow_versions_publication_idempotency_idx[\s\S]*?organization_id, publication_idempotency_key/i,
  "workflow publication idempotency must be organization scoped",
);

const resolution = functionBody("apply_work_order_workflow_resolution");
for (const contract of [
  /'resolutionSource',\s*resolved_source/i,
  /'matchedRuleId',\s*resolved_rule_id/i,
  /'conflictRuleIds'/i,
]) {
  assert.match(resolution, contract, "workflow resolution must preserve a strict explainability result");
}
for (const contract of [
  /from public\.work_orders[\s\S]*?for update/i,
  /idempotency_key/i,
  /from public\.workflow_assignments[\s\S]*?for update/i,
  /status in \('active', 'completed'\)|status\s*=\s*'active'/i,
  /insert into public\.workflow_assignments/i,
  /profile\.role in \('super_admin', 'ops_admin'\)/i,
]) {
  assert.match(resolution, contract, "work-order resolution must lock and authorize assignment changes");
}
assert.match(
  resolution,
  /update public\.workflow_assignments[\s\S]*?status\s*=\s*'revoked'[\s\S]*?delivery_sequence\s*=\s*nextval\('public\.workflow_assignment_sequence'::regclass\)/i,
  "rebound assignments must advance the device delivery cursor when revoked",
);

const claim = functionBody("claim_workflow_assignment");
for (const contract of [
  /from public\.workflow_assignments[\s\S]*?for update/i,
  /idempotency_key/i,
  /assigned_profile_id/i,
  /assigned_device_id/i,
  /from public\.device_bindings/i,
  /status\s*=\s*'active'/i,
]) {
  assert.match(claim, contract, "assignment claim must verify engineer and device ownership");
}

const report = functionBody("report_workflow_assignment_status");
for (const contract of [
  /from public\.workflow_assignments[\s\S]*?for update/i,
  /idempotency_key/i,
  /invalid workflow assignment status transition/i,
  /new_status\s*=\s*'revoked'/i,
]) {
  assert.match(report, contract, "assignment reports must enforce legal forward transitions");
}

const start = functionBody("start_workflow_execution");
for (const contract of [
  /from public\.workflow_assignments[\s\S]*?for update/i,
  /start_idempotency_key/i,
  /requested_execution_id/i,
  /from public\.maintenance_tasks/i,
  /insert into public\.maintenance_tasks/i,
  /target_local_task_id/i,
  /from public\.device_bindings/i,
  /insert into public\.workflow_executions/i,
  /insert into public\.workflow_executions \(\s*id,/i,
  /status not in \('ready', 'active'\)/i,
]) {
  assert.match(start, contract, "execution start must enforce assignment and task ownership");
}

const step = functionBody("append_workflow_step_execution");
for (const contract of [
  /from public\.workflow_executions[\s\S]*?for update/i,
  /from public\.workflow_versions/i,
  /from public\.workflow_step_executions[\s\S]*?for update/i,
  /idempotency_key/i,
  /insert into public\.workflow_step_executions/i,
  /update public\.workflow_step_executions/i,
  /invalid workflow step status transition/i,
  /insert into public\.workflow_step_status_events/i,
  /completed', 'cancelled/i,
  /jsonb_typeof/i,
]) {
  assert.match(step, contract, "step append must validate package nodes, state, and typed payloads");
}

assert.match(
  sql,
  /create table public\.workflow_assignment_status_events/i,
  "assignment transition idempotency requires a domain event table",
);
assert.match(
  sql,
  /create table public\.workflow_step_status_events/i,
  "step transition idempotency requires a domain event table",
);
assert.match(
  sql,
  /unique \(organization_id, idempotency_key\)/i,
  "workflow command idempotency must be organization scoped",
);
assert.doesNotMatch(
  sql,
  /grant\s+execute[\s\S]{0,200}\sto\s+(anon|authenticated)/i,
  "workflow command RPCs must not be executable by browser roles",
);

console.log("workflow API transaction contract passed");
