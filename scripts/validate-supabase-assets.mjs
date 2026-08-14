import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const requiredFiles = [
  "supabase/functions/ops-glasses/index.ts",
  "supabase/functions/ops-glasses/automigrate.ts",
  "supabase/functions/ops-glasses/deno.json",
  "supabase/functions/ops-glasses/voiceprint-admin-client.ts",
  "supabase/functions/ops-glasses/model-contract.ts",
  "supabase/functions/ops-glasses/model-contract.test.ts",
  "supabase/functions/ops-glasses/device-activation.ts",
  "supabase/functions/ops-glasses/remote-probe-policy.ts",
  "supabase/functions/ops-glasses/remote-probe-policy.test.ts",
  "supabase/config.toml",
  "supabase/README.md",
  "supabase/migrations/202608030002_voiceprint_audit_idempotency.sql",
  "supabase/migrations/202608030003_device_activation_codes.sql",
  "supabase/migrations/20260805002445_remove_legacy_demo_asset.sql",
  "supabase/migrations/20260805110000_account_lifecycle_management.sql",
  "supabase/migrations/20260805113000_account_email_uniqueness.sql",
  "supabase/migrations/20260805170000_device_project_registration.sql",
  "scripts/audit-dingdang-supabase-live-readiness.mjs",
];

for (const file of requiredFiles) {
  const fullPath = path.join(root, file);
  if (!fs.existsSync(fullPath)) {
    throw new Error(`missing required file: ${file}`);
  }
}

const functionCode = fs.readFileSync(path.join(root, "supabase/functions/ops-glasses/index.ts"), "utf8");
const automigrateCode = fs.readFileSync(path.join(root, "supabase/functions/ops-glasses/automigrate.ts"), "utf8");
const envExample = fs.readFileSync(path.join(root, "supabase/.env.example"), "utf8");
const supabaseReadme = fs.readFileSync(path.join(root, "supabase/README.md"), "utf8");
const remoteProbePolicy = fs.readFileSync(
  path.join(root, "supabase/functions/ops-glasses/remote-probe-policy.ts"),
  "utf8",
);
const modelContract = fs.readFileSync(
  path.join(root, "supabase/functions/ops-glasses/model-contract.ts"),
  "utf8",
);
const legacyDemoCleanupMigration = fs.readFileSync(
  path.join(root, "supabase/migrations/20260805002445_remove_legacy_demo_asset.sql"),
  "utf8",
);
const voiceprintAuditMigration = fs.readFileSync(
  path.join(root, "supabase/migrations/202608030002_voiceprint_audit_idempotency.sql"),
  "utf8",
);
const deviceProjectRegistrationMigration = fs.readFileSync(
  path.join(root, "supabase/migrations/20260805170000_device_project_registration.sql"),
  "utf8",
);
const readinessAuditCode = fs.readFileSync(
  path.join(root, "scripts/audit-dingdang-supabase-live-readiness.mjs"),
  "utf8",
);

for (const secretName of [
  "V9_GATEWAY_SYNC_TOKEN_SHA256",
  "V9_GATEWAY_SYNC_ORGANIZATION_ID",
  "V9_GATEWAY_SYNC_USER_ID",
  "V9_GATEWAY_SYNC_DEVICE_ID",
  "V9_VOICEPRINT_ADMIN_URL",
  "V9_VOICEPRINT_ADMIN_TOKEN",
  "V9_DEVICE_ACTIVATION_BACKEND_BASE_URL",
  "V9_DEVICE_ACTIVATION_POLICY_VERSION",
]) {
  if (!readinessAuditCode.includes(`\"${secretName}\"`)) {
    throw new Error(`Supabase readiness audit missing required V9 secret: ${secretName}`);
  }
  if (!envExample.includes(`${secretName}=`)) {
    throw new Error(`Supabase environment example missing required V9 secret: ${secretName}`);
  }
}

for (const marker of [
  "audit_events_voiceprint_revoke_idempotency_idx",
  "organization_id",
  "action = 'voiceprint_revoked'",
  "metadata->>'idempotencyKey'",
]) {
  if (!voiceprintAuditMigration.includes(marker)) {
    throw new Error(`voiceprint audit idempotency migration missing marker: ${marker}`);
  }
}

for (const marker of [
  "register_device_project_from_task_start",
  "project_id is null",
  "device_project_created",
  "grant execute on function public.register_device_project_from_task_start",
]) {
  if (!deviceProjectRegistrationMigration.includes(marker)) {
    throw new Error(`device project registration migration missing marker: ${marker}`);
  }
}

for (const marker of [
  'PREVIOUS_STABLE_AI_MODEL = "qwen3-vl-plus"',
  'PREVIOUS_STABLE_ASR_MODEL = "fun-asr-realtime"',
  'IFLYTEK_VOICEPRINT_SERVICE_ID = "s1aa729d0"',
  "model_contract_ai_model_invalid",
  "model_contract_asr_model_invalid",
]) {
  if (!modelContract.includes(marker)) {
    throw new Error(`V9 model contract missing marker: ${marker}`);
  }
}

if (!readinessAuditCode.includes("supabase/functions/ops-glasses/skill-knowledge-sync.ts")) {
  throw new Error("Supabase readiness audit does not inspect the V9 sync route module");
}

for (const marker of [
  '"DEFAULT_ASSET_TAG"',
  '"REMOTE_PROBE_MODE"',
  "REMOTE_PROBE_URL",
  "remote-probe-production-config",
  "remoteProbeProductionReady",
]) {
  if (!readinessAuditCode.includes(marker)) {
    throw new Error(`Supabase readiness audit missing fail-closed probe marker: ${marker}`);
  }
}

const requiredTables = [
  "ops_assets",
  "safe_commands",
  "ops_sessions",
  "ops_events",
  "ops_images",
  "ai_requests",
  "ai_observations",
  "ai_context_bundles",
  "ai_decisions",
  "voice_inputs",
  "remote_probes",
];

for (const table of requiredTables) {
  if (!automigrateCode.includes(`public.${table}`)) {
    throw new Error(`automigrate does not define ${table}`);
  }
  if (!automigrateCode.includes("enable row level security")) {
    throw new Error("automigrate does not enable RLS");
  }
}

if (!functionCode.includes("ensureSchema(env)")) {
  throw new Error("edge function does not call automigrate ensureSchema(env)");
}

if (!automigrateCode.includes("ops-glasses-captures")) {
  throw new Error("automigrate does not create the ops-glasses-captures storage bucket");
}

if (!automigrateCode.includes("AUTO_MIGRATE")) {
  throw new Error("automigrate does not expose AUTO_MIGRATE");
}

for (const marker of [
  'return "disabled"',
  "remote_probe_not_configured",
  "remote_probe_https_required",
  "legacy_demo_asset_forbidden",
]) {
  if (!remoteProbePolicy.includes(marker)) {
    throw new Error(`remote probe fail-closed policy missing marker: ${marker}`);
  }
}

for (const forbidden of [
  'REMOTE_PROBE_MODE=mock',
  'MOCK_SSH_REACHABLE',
]) {
  if (envExample.includes(forbidden)) {
    throw new Error(`Supabase production env example contains fake probe setting: ${forbidden}`);
  }
}

for (const forbidden of [
  "REMOTE_PROBE_MODE=mock",
  "DEMO_ASSET_TAG=",
  "DEMO_TARGET_HOST=",
  "MOCK_SSH_REACHABLE",
]) {
  if (supabaseReadme.includes(forbidden)) {
    throw new Error(`Supabase production README contains fake probe setting: ${forbidden}`);
  }
}

if (automigrateCode.includes("SSH console recovery demo server")) {
  throw new Error("automigrate must not seed the legacy demo asset");
}

for (const marker of [
  "ASSET-CONSOLE-001",
  "SSH console recovery demo server",
  "192.168.1.50",
  "not exists",
  "public.ops_sessions",
]) {
  if (!legacyDemoCleanupMigration.includes(marker)) {
    throw new Error(`legacy demo cleanup migration missing marker: ${marker}`);
  }
}

const requiredSchemaMarkers = [
  "public.ai_decision_result_type",
  "public.ai_feedback_code",
  "context_version text not null default 'air3-v2-ai-brain-v1'",
  "full_text text not null default ''",
  "display_pages jsonb not null default '[]'::jsonb",
  "text_overflow_mode text not null default 'single'",
  "page_count integer not null default 1",
];

for (const marker of requiredSchemaMarkers) {
  if (!automigrateCode.includes(marker)) {
    throw new Error(`automigrate missing schema marker: ${marker}`);
  }
}

const requiredRoutes = [
  "/sessions/events",
  "/sessions/",
  "/probe",
  "/voice",
  "/escalate",
  "/health",
];

for (const route of requiredRoutes) {
  if (!functionCode.includes(route)) {
    throw new Error(`edge function missing route marker: ${route}`);
  }
}

const forbiddenCommandPatterns = [
  "rm -rf",
  "mkfs",
  "dd if=",
  "shutdown",
  "reboot",
];

for (const pattern of forbiddenCommandPatterns) {
  if (functionCode.includes(pattern) || automigrateCode.includes(pattern)) {
    throw new Error(`unsafe command pattern found: ${pattern}`);
  }
}

console.log("Supabase assets validation passed.");
