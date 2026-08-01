import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202608010001_configurable_field_workflows.sql",
  import.meta.url,
);

assert.ok(
  existsSync(migrationPath),
  "configurable field workflow migration is missing",
);
const sql = readFileSync(migrationPath, "utf8");

for (const table of [
  "field_apps",
  "workflow_definitions",
  "workflow_versions",
  "workflow_binding_rules",
  "work_orders",
  "workflow_assignments",
  "workflow_executions",
  "workflow_step_executions",
]) {
  assert.match(sql, new RegExp(`create table public\\.${table}\\b`, "i"));
  assert.match(
    sql,
    new RegExp(`alter table public\\.${table} enable row level security`, "i"),
    `${table} must enable RLS`,
  );
}

for (const contract of [
  /create sequence public\.workflow_assignment_sequence/i,
  /workflow_versions_immutable/i,
  /before update or delete on public\.workflow_versions/i,
  /check \(mode in \('required', 'optional', 'none'\)\)/i,
  /content_sha256 text not null check \(content_sha256 ~ '\^\[0-9a-f\]\{64\}\$'\)/i,
  /execution_package jsonb not null/i,
  /required_capabilities text\[\] not null/i,
  /delivery_sequence bigint not null default nextval\('public\.workflow_assignment_sequence'/i,
  /workflow_assignments_one_active_order_idx/i,
  /unique \(workflow_definition_id, version_number\)/i,
  /create or replace function public\.set_workflow_version_status/i,
  /insert into public\.audit_events/i,
  /revoke insert, update, delete on table public\.field_apps/i,
  /grant select on table public\.field_apps/i,
  /grant usage, select on sequence public\.workflow_assignment_sequence to service_role/i,
  /workflow_definitions_org_app_id_idx/i,
  /foreign key\s*\(\s*organization_id\s*,\s*id\s*,\s*default_workflow_definition_id\s*\)[\s\S]{0,120}references public\.workflow_definitions\s*\(\s*organization_id\s*,\s*field_app_id\s*,\s*id\s*\)/i,
  /maintenance_tasks_organization_project_id_idx/i,
  /foreign key\s*\(\s*organization_id\s*,\s*assignment_id\s*,\s*work_order_id\s*,\s*project_id\s*,\s*operator_profile_id\s*,\s*workflow_version_id\s*\)[\s\S]{0,240}references public\.workflow_assignments\s*\(\s*organization_id\s*,\s*id\s*,\s*work_order_id\s*,\s*project_id\s*,\s*assigned_profile_id\s*,\s*workflow_version_id\s*\)/i,
  /foreign key\s*\(\s*organization_id\s*,\s*project_id\s*,\s*task_id\s*\)[\s\S]{0,100}references public\.maintenance_tasks\s*\(\s*organization_id\s*,\s*project_id\s*,\s*id\s*\)/i,
  /check \(binding_source is null or binding_source in \('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default'\)\)/i,
  /check \(resolution_source in \('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default'\)\)/i,
]) {
  assert.match(sql, contract);
}

for (const parent of [
  "field_apps",
  "workflow_definitions",
  "workflow_versions",
  "work_orders",
  "workflow_assignments",
  "workflow_executions",
]) {
  assert.match(
    sql,
    new RegExp(
      `references public\\.${parent}\\s*\\(\\s*organization_id\\s*,\\s*id(?:\\s*,\\s*[a-z_]+)*\\s*\\)`,
      "i",
    ),
    `${parent} must be referenced with organization scope`,
  );
}

assert.doesNotMatch(
  sql,
  /create policy[\s\S]{0,200}using\s*\(\s*true\s*\)/i,
  "workflow RLS must not expose all rows",
);
assert.doesNotMatch(
  sql,
  /grant\s+(insert|update|delete|all)[\s\S]{0,120}\sto\s+(anon|authenticated)/i,
  "browser roles must not receive workflow writes",
);
assert.doesNotMatch(
  sql,
  /project_id is null\s+or project_id in/i,
  "field access requires an active project membership",
);

console.log("configurable workflow schema contract passed");
