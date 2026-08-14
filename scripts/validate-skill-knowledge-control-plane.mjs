import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/20260803054347_skill_knowledge_control_plane.sql",
  import.meta.url,
);
const edgeFunctionPath = new URL(
  "../supabase/functions/ops-glasses/index.ts",
  import.meta.url,
);
const documentParserClientPath = new URL(
  "../supabase/functions/ops-glasses/knowledge-document-parser-client.ts",
  import.meta.url,
);
const caseMigrationPath = new URL(
  "../supabase/migrations/20260805094500_knowledge_case_drafts_from_tasks.sql",
  import.meta.url,
);
const attachmentMigrationPath = new URL(
  "../supabase/migrations/20260805190000_knowledge_version_attachments.sql",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "Skill/knowledge control-plane migration is missing");
assert.ok(existsSync(caseMigrationPath), "Task-derived knowledge case migration is missing");
assert.ok(existsSync(attachmentMigrationPath), "Knowledge attachment lifecycle migration is missing");
assert.ok(existsSync(documentParserClientPath), "Private knowledge document parser client is missing");
const sql = readFileSync(migrationPath, "utf8");
const caseSql = readFileSync(caseMigrationPath, "utf8");
const attachmentSql = readFileSync(attachmentMigrationPath, "utf8");
const edgeFunction = readFileSync(edgeFunctionPath, "utf8");
const documentParserClient = readFileSync(documentParserClientPath, "utf8");

for (const contract of [
  /isSkillKnowledgeManagementPath\(path\)/,
  /createKnowledgeDocumentParserClient/,
  /V9_KNOWLEDGE_PARSER_URL/,
  /V9_KNOWLEDGE_PARSER_TOKEN/,
  /routeSkillKnowledgeManagement\([\s\S]*?createSkillKnowledgeManagementGateway\(\s*supabase,[\s\S]*?createKnowledgeDocumentParserClient/,
]) {
  assert.match(edgeFunction, contract, "deployed Edge Function must dispatch Skill/knowledge management routes");
}

for (const contract of [
  /parsedUrl\.protocol !== "https:"/,
  /Authorization: `Bearer \$\{token\}`/,
  /AbortSignal\.timeout\(timeoutMs\)/,
  /actualSha256 !== contentSha256/,
]) {
  assert.match(documentParserClient, contract, "private document parser client is missing a safety contract");
}

for (const table of [
  "skill_definitions",
  "skill_versions",
  "skill_version_reviews",
  "skill_assignments",
  "knowledge_entries",
  "knowledge_versions",
  "knowledge_version_reviews",
  "knowledge_grants",
  "knowledge_references",
  "device_content_manifests",
  "skill_knowledge_command_events",
]) {
  assert.match(sql, new RegExp(`create table public\\.${table}\\b`, "i"), `${table} table is missing`);
  assert.match(
    sql,
    new RegExp(`alter table public\\.${table} enable row level security`, "i"),
    `${table} must enable RLS`,
  );
  assert.match(
    sql,
    new RegExp(`revoke all on table public\\.${table} from public, anon, authenticated`, "i"),
    `${table} must not be browser writable`,
  );
}

for (const contract of [
  /create type public\.skill_version_status[\s\S]*?'draft'[\s\S]*?'review_pending'[\s\S]*?'published'[\s\S]*?'deprecated'[\s\S]*?'archived'/i,
  /create type public\.knowledge_version_status[\s\S]*?'uploaded'[\s\S]*?'scanning'[\s\S]*?'parsing'[\s\S]*?'parsed'[\s\S]*?'review_pending'[\s\S]*?'published'[\s\S]*?'expired'[\s\S]*?'deprecated'[\s\S]*?'archived'/i,
  /unique\s*\(organization_id,\s*skill_key\)/i,
  /unique\s*\(organization_id,\s*skill_definition_id,\s*version\)/i,
  /unique\s*\(organization_id,\s*knowledge_key\)/i,
  /unique\s*\(organization_id,\s*knowledge_entry_id,\s*version\)/i,
  /unique\s*\(organization_id,\s*idempotency_key\)/i,
  /create trigger skill_versions_published_immutable/i,
  /create trigger knowledge_versions_published_immutable/i,
  /content_sha256 text/i,
  /manifest_version bigint/i,
  /etag text/i,
  /insert into public\.audit_events/i,
]) {
  assert.match(sql, contract);
}

const functionNames = [
  "create_skill_draft",
  "transition_skill_version",
  "assign_skill_version",
  "revoke_skill_assignment",
  "create_knowledge_draft",
  "transition_knowledge_version",
  "grant_knowledge_version",
  "revoke_knowledge_grant",
];

function functionBody(functionName) {
  const match = sql.match(
    new RegExp(`create or replace function public\\.${functionName}\\([\\s\\S]*?\\n\\$\\$;`, "i"),
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
    /idempotency_key/i,
    /insert into public\.skill_knowledge_command_events/i,
    /insert into public\.audit_events/i,
  ]) {
    assert.match(body, contract, `${functionName} is missing a safety contract`);
  }
  assert.match(
    sql,
    new RegExp(`revoke all on function public\\.${functionName}\\([\\s\\S]*?from public, anon, authenticated;`, "i"),
    `${functionName} must be revoked from browser roles`,
  );
  assert.match(
    sql,
    new RegExp(`grant execute on function public\\.${functionName}\\([\\s\\S]*?to service_role;`, "i"),
    `${functionName} must be service-role only`,
  );
}

assert.doesNotMatch(
  sql,
  /grant\s+(insert|update|delete|all)[\s\S]{0,200}\sto\s+(anon|authenticated)/i,
  "browser roles must not receive direct Skill/knowledge write grants",
);

for (const table of ["knowledge_case_sources", "knowledge_case_evidence"]) {
  assert.match(caseSql, new RegExp(`create table public\\.${table}\\b`, "i"), `${table} table is missing`);
  assert.match(caseSql, new RegExp(`alter table public\\.${table} enable row level security`, "i"));
  assert.match(
    caseSql,
    new RegExp(`revoke all on table public\\.${table} from public, anon, authenticated`, "i"),
  );
}

const caseFunction = caseSql.match(
  /create or replace function public\.create_knowledge_case_draft_from_task\([\s\S]*?\n\$\$;/i,
);
assert.ok(caseFunction, "create_knowledge_case_draft_from_task RPC is missing");
for (const contract of [
  /security definer/i,
  /set search_path = ''/i,
  /profile\.role in \('super_admin', 'ops_admin'\)/i,
  /event_type in \('task_completed', 'task_closed'\)/i,
  /payload\s*->>\s*'humanConfirmed'\s*=\s*'true'/i,
  /upload_status\s*<>\s*'synced'/i,
  /insert into public\.knowledge_case_sources/i,
  /insert into public\.knowledge_case_evidence/i,
  /insert into public\.skill_knowledge_command_events/i,
  /insert into public\.audit_events/i,
]) {
  assert.match(caseFunction[0], contract, "Task-derived case RPC is missing a safety contract");
}
assert.match(
  caseSql,
  /revoke all on function public\.create_knowledge_case_draft_from_task\([\s\S]*?from public, anon, authenticated;/i,
);
assert.match(
  caseSql,
  /grant execute on function public\.create_knowledge_case_draft_from_task\([\s\S]*?to service_role;/i,
);

assert.match(
  attachmentSql,
  /create table public\.knowledge_version_attachments\b/i,
  "Knowledge attachment metadata table is missing",
);
for (const contract of [
  /unique\s*\(organization_id,\s*idempotency_key\)/i,
  /foreign key\s*\(organization_id,\s*knowledge_version_id\)[\s\S]*?references public\.knowledge_versions/i,
  /status text not null[\s\S]*?'uploaded'[\s\S]*?'parsing'[\s\S]*?'parsed'[\s\S]*?'processing_unavailable'[\s\S]*?'failed'[\s\S]*?'replaced'/i,
  /file_sha256 text not null[\s\S]*?\^\[0-9a-f\]\{64\}\$/i,
  /storage_bucket text not null/i,
  /storage_path text not null/i,
  /is_current boolean not null default false/i,
  /alter table public\.knowledge_version_attachments enable row level security/i,
  /revoke all on table public\.knowledge_version_attachments from public, anon, authenticated/i,
  /insert into storage\.buckets[\s\S]*?'ops-knowledge-attachments'[\s\S]*?false[\s\S]*?8388608/i,
  /create policy "service_role_manage_ops_knowledge_attachments"[\s\S]*?bucket_id = 'ops-knowledge-attachments'/i,
]) {
  assert.match(attachmentSql, contract);
}

const completeAttachmentFunction = attachmentSql.match(
  /create or replace function public\.complete_knowledge_attachment_processing\([\s\S]*?\n\$\$;/i,
);
assert.ok(completeAttachmentFunction, "complete_knowledge_attachment_processing RPC is missing");
for (const contract of [
  /security definer/i,
  /set search_path = ''/i,
  /profile\.role in \('super_admin', 'ops_admin'\)/i,
  /source_type <> 'uploaded_file'/i,
  /status not in \('uploaded', 'parsed'\)/i,
  /set status = 'replaced'/i,
  /content = normalized_content/i,
  /insert into public\.skill_knowledge_command_events/i,
  /insert into public\.audit_events/i,
]) {
  assert.match(completeAttachmentFunction[0], contract);
}
assert.match(
  attachmentSql,
  /revoke all on function public\.complete_knowledge_attachment_processing\([\s\S]*?from public, anon, authenticated;/i,
);
assert.match(
  attachmentSql,
  /grant execute on function public\.complete_knowledge_attachment_processing\([\s\S]*?to service_role;/i,
);

const attachmentDraftFunction = attachmentSql.match(
  /create or replace function public\.create_knowledge_draft\([\s\S]*?\n\$\$;/i,
);
assert.ok(attachmentDraftFunction, "Attachment-aware create_knowledge_draft RPC is missing");
assert.match(
  attachmentDraftFunction[0],
  /case when draft_source_type = 'uploaded_file' then 'uploaded'::public\.knowledge_version_status else 'parsed'::public\.knowledge_version_status end/i,
);
assert.match(
  attachmentDraftFunction[0],
  /draft_source_type = 'uploaded_file'[\s\S]*?char_length\(btrim\(coalesce\(draft_content, ''\)\)\) <> 0/i,
);

const attachmentTransitionFunction = attachmentSql.match(
  /create or replace function public\.transition_knowledge_version\([\s\S]*?\n\$\$;/i,
);
assert.ok(attachmentTransitionFunction, "Attachment-aware transition_knowledge_version RPC is missing");
for (const contract of [
  /knowledge_version\.source_type = 'uploaded_file'/i,
  /from public\.knowledge_version_attachments attachment/i,
  /attachment\.is_current = true/i,
  /attachment\.status = 'parsed'/i,
  /Knowledge attachment is not ready for review or publication/i,
]) {
  assert.match(attachmentTransitionFunction[0], contract);
}

console.log("Skill/knowledge control-plane contract passed");
