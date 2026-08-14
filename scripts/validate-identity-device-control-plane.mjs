import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202607310004_identity_device_control_plane.sql",
  import.meta.url,
);
const accountLifecycleMigrationPath = new URL(
  "../supabase/migrations/20260805110000_account_lifecycle_management.sql",
  import.meta.url,
);
const accountEmailUniquenessMigrationPath = new URL(
  "../supabase/migrations/20260805113000_account_email_uniqueness.sql",
  import.meta.url,
);
const managementFunctionPath = new URL(
  "../supabase/functions/ops-glasses/management.ts",
  import.meta.url,
);
const opsEdgeIndexPath = new URL(
  "../supabase/functions/ops-glasses/index.ts",
  import.meta.url,
);
const managementApiPath = new URL(
  "../ops-management-web/src/api/management-api.ts",
  import.meta.url,
);
const devicesPagePath = new URL(
  "../ops-management-web/src/features/devices/DevicesPage.tsx",
  import.meta.url,
);
const devicesCssPath = new URL(
  "../ops-management-web/src/features/devices/devices.css",
  import.meta.url,
);
const projectRecordsPagePath = new URL(
  "../ops-management-web/src/features/projects/ProjectRecordsPage.tsx",
  import.meta.url,
);
const projectRecordsCssPath = new URL(
  "../ops-management-web/src/features/projects/project-records.css",
  import.meta.url,
);
const managementAppPath = new URL(
  "../ops-management-web/src/App.tsx",
  import.meta.url,
);
const managementMainPath = new URL(
  "../ops-management-web/src/main.tsx",
  import.meta.url,
);
const supabaseEnvExamplePath = new URL("../supabase/.env.example", import.meta.url);
const taskListPagePath = new URL(
  "../ops-management-web/src/features/tasks/TaskListPage.tsx",
  import.meta.url,
);
const mediaCenterPagePath = new URL(
  "../ops-management-web/src/features/media/MediaCenterPage.tsx",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "identity/device control-plane migration is missing");
const sql = readFileSync(migrationPath, "utf8");
const accountLifecycleSql = readFileSync(accountLifecycleMigrationPath, "utf8");
const accountEmailUniquenessSql = readFileSync(accountEmailUniquenessMigrationPath, "utf8");
const managementFunction = readFileSync(managementFunctionPath, "utf8");
const opsEdgeIndex = readFileSync(opsEdgeIndexPath, "utf8");
const managementApi = readFileSync(managementApiPath, "utf8");
const devicesPage = readFileSync(devicesPagePath, "utf8");
const devicesCss = readFileSync(devicesCssPath, "utf8");
const projectRecordsPage = readFileSync(projectRecordsPagePath, "utf8");
const projectRecordsCss = readFileSync(projectRecordsCssPath, "utf8");
const managementApp = readFileSync(managementAppPath, "utf8");
const managementMain = readFileSync(managementMainPath, "utf8");
const supabaseEnvExample = readFileSync(supabaseEnvExamplePath, "utf8");
const taskListPage = readFileSync(taskListPagePath, "utf8");
const mediaCenterPage = readFileSync(mediaCenterPagePath, "utf8");

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

for (const contract of [
  /add column if not exists email text/i,
  /create or replace function public\.set_ops_profile_role/i,
  /organization must retain an active super administrator/i,
  /administrators cannot change their own role/i,
  /profile_role_changed/i,
  /grant execute on function public\.set_ops_profile_role/i,
]) {
  assert.match(accountLifecycleSql, contract);
}

for (const contract of [
  /create unique index/i,
  /ops_profiles_organization_normalized_email_unique_idx/i,
  /organization_id, lower\(btrim\(email\)\)/i,
  /where btrim\(email\) <> ''/i,
]) {
  assert.match(accountEmailUniquenessSql, contract);
}

console.log("account lifecycle schema contract passed");

for (const contract of [
  /const profileRecovery = path\.match/,
  /SEND_ACCOUNT_RECOVERY/,
  /profile_recovery_requested/,
  /resetPasswordForEmail\(email, \{ redirectTo \}\)/,
  /account_recovery_redirect_unconfigured/,
  /account_recovery_redirect_invalid/,
]) {
  assert.match(managementFunction, contract);
}
for (const contract of [
  /sendPersonRecovery\(personId: string, command: ReasonedCommand\)/,
  /confirmation: "SEND_ACCOUNT_RECOVERY"/,
]) {
  assert.match(managementApi, contract);
}
for (const contract of [
  /恢复 .* 登录身份/,
  /发送恢复邮件/,
  /不生成或显示恢复链接和新密码/,
]) {
  assert.match(devicesPage, contract);
}
for (const contract of [
  /设置新密码/,
  /completePasswordRecovery/,
  /重新登录并再次校验账号状态与项目权限/,
]) {
  assert.match(managementApp, contract);
}
for (const contract of [
  /event === "PASSWORD_RECOVERY"/,
  /client\.auth\.updateUser\(\{ password \}\)/,
  /client\.auth\.signOut\(\{ scope: "local" \}\)/,
]) {
  assert.match(managementMain, contract);
}
assert.match(opsEdgeIndex, /OPS_ACCOUNT_RECOVERY_REDIRECT_URL/);
assert.match(opsEdgeIndex, /accountRecoveryRedirectUrl: env\.OPS_ACCOUNT_RECOVERY_REDIRECT_URL/);
assert.match(supabaseEnvExample, /OPS_ACCOUNT_RECOVERY_REDIRECT_URL=https:\/\//);
assert.doesNotMatch(managementFunction, /generateLink\(/);

console.log("account identity recovery contract passed");

for (const contract of [
  /glasses_device_tokens/,
  /glasses_device_sessions/,
  /device_content_manifests/,
  /credential_status: credentialStatus/,
  /session_status: sessionStatus/,
  /manifest_status: manifestStatus/,
  /sync_health: sync\.health/,
  /sync_issue: sync\.issue/,
]) {
  assert.match(managementFunction, contract);
}
for (const contract of [
  /credential_status\?: "active" \| "missing" \| "expired" \| "revoked"/,
  /session_status\?: "active" \| "missing" \| "expired" \| "revoked"/,
  /manifest_status\?: "healthy" \| "missing" \| "expired" \| "revoked" \| "superseded"/,
  /sync_health\?: "healthy" \| "attention" \| "offline" \| "blocked"/,
]) {
  assert.match(managementApi, contract);
}
for (const contract of [
  /Skill\/知识清单/,
  /设备凭据有效/,
  /同步正常/,
  /sync-health/,
]) {
  assert.match(devicesPage, contract);
}
for (const contract of [
  /\.device-columns\{grid-template-columns:minmax\(220px,1\.2fr\) minmax\(150px,\.8fr\) 118px 150px 196px\}/,
  /\.sync-health\.healthy/,
  /\.sync-health\.attention/,
  /\.sync-health\.offline/,
  /\.sync-health\.blocked/,
  /\.management-row\.device-columns>span:nth-child\(4\)/,
]) {
  assert.match(devicesCss, contract);
}

console.log("device health control-plane contract passed");

for (const contract of [
  /const projectRecord = path\.match/,
  /projectRecord\(identity: Identity, projectId: string\)/,
  /humanConfirmed !== true/,
  /payload\.phase !== "COMPLETED"/,
  /projectMemoryRevision/,
  /task_events/,
]) {
  assert.match(managementFunction, contract);
}
for (const contract of [
  /export type ProjectMemorySnapshot/,
  /export type ProjectRecord/,
  /getProjectRecord\(projectId: string\)/,
  /\/management\/projects\/\$\{encoded\(projectId\)\}\/record/,
]) {
  assert.match(managementApi, contract);
}
for (const contract of [
  /已同步到云端的任务、人工确认摘要、事实、风险和 Skill 版本/,
  /尚无人工确认并同步的项目结束摘要/,
  /项目记忆 v/,
  /查看任务/,
]) {
  assert.match(projectRecordsPage, contract);
}
for (const contract of [
  /\.project-record-layout/,
  /\.project-memory-groups/,
  /body:has\(\.project-task-workspace\),body:has\(\.task-title\)\{min-width:0\}/,
  /\.project-skill-section>header,\.project-task-section>header\{justify-content:flex-start\}/,
  /\.project-record-heading>\.status\{white-space:nowrap;flex:0 0 auto\}/,
  /body:has\(\.project-task-workspace\) \.shell,body:has\(\.task-title\) \.shell\{grid-template-columns:minmax\(0,1fr\)\}/,
  /body:has\(\.project-task-workspace\) \.nav nav,body:has\(\.task-title\) \.nav nav\{display:flex;overflow-x:auto/,
  /@media\(max-width:720px\)/,
]) {
  assert.match(projectRecordsCss, contract);
}
assert.match(managementApp, /ProjectTaskWorkspace/);

console.log("cloud project record contract passed");

for (const contract of [
  /export type TaskListFilters/,
  /export type MediaListFilters/,
  /path === "\/management\/tasks\/export"/,
  /path === "\/management\/media"/,
  /async media\(identity, filters\)/,
  /encodeTaskCursor/,
  /encodeMediaCursor/,
]) {
  assert.match(managementFunction, contract);
}

for (const contract of [
  /export type PagedResult<T>/,
  /getTasks\(filters\?: TaskListFilters\)/,
  /exportTasks\(filters\?: TaskExportFilters\)/,
  /getMedia\(filters\?: MediaListFilters\)/,
  /return download\(`\/management\/tasks\/export/,
  /return request<PagedResult<MediaRecord>>\(`\/management\/media/,
]) {
  assert.match(managementApi, contract);
}

for (const contract of [
  /queryDraft/,
  /nextCursor/,
  /api\.exportTasks/,
  /uniqueTasks/,
]) {
  assert.match(taskListPage, contract);
}

for (const contract of [
  /api\.getMedia/,
  /uniqueMedia/,
  /api\.retryMedia\(media\.task_id, media\.id\)/,
  /failure_reason/,
]) {
  assert.match(mediaCenterPage, contract);
}
assert.doesNotMatch(mediaCenterPage, /api\.getTasks\(/);
assert.doesNotMatch(mediaCenterPage, /api\.getTask\(/);

console.log("operations record-center contract passed");
