import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(import.meta.dirname, "..");
const auditPath = path.join(repoRoot, "scripts", "audit-dingdang-supabase-live-readiness.mjs");

const completeSecrets = {
  AUTO_MIGRATE: "true",
  OPS_GLASSES_API_KEY: "secret-ops-api-key-not-for-output",
  OPENAI_API_KEY: "secret-openai-key-not-for-output",
  OPENAI_BASE_URL: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  OPENAI_VISION_MODEL: "qwen3-vl-plus",
  OPENAI_TRANSCRIBE_API_KEY: "secret-asr-key-not-for-output",
  OPENAI_TRANSCRIBE_BASE_URL: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  OPENAI_TRANSCRIBE_MODEL: "fun-asr-realtime",
  DASHSCOPE_API_KEY: "secret-dashscope-key-not-for-output",
  DASHSCOPE_FUNASR_URL: "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
  DASHSCOPE_FUNASR_MODEL: "fun-asr-realtime",
  V9_GATEWAY_SYNC_TOKEN_SHA256: "a".repeat(64),
  V9_GATEWAY_SYNC_ORGANIZATION_ID: "org-production",
  V9_GATEWAY_SYNC_USER_ID: "user-production",
  V9_GATEWAY_SYNC_DEVICE_ID: "air3-production-device",
  V9_VOICEPRINT_ADMIN_URL: "https://bb.chinacedar.top:2305/v9-ops",
  V9_VOICEPRINT_ADMIN_TOKEN: "secret-voiceprint-admin-token-not-for-output",
  V9_KNOWLEDGE_PARSER_URL: "https://bb.chinacedar.top:2305/v9-ops",
  V9_KNOWLEDGE_PARSER_TOKEN: "secret-parser-token-not-for-output",
  V9_DEVICE_ACTIVATION_BACKEND_BASE_URL: "https://bb.chinacedar.top:2305/v9-ops",
  V9_DEVICE_ACTIVATION_POLICY_VERSION: "v9-production-1",
  OPS_ACCOUNT_RECOVERY_REDIRECT_URL: "https://ops.invalid/",
  DEFAULT_ASSET_TAG: "HF-CLOUD-SERVER-001",
  DEFAULT_TARGET_SSH_PORT: "2304",
  REMOTE_PROBE_MODE: "tcp",
  REMOTE_PROBE_URL: "",
};

test("reads production readiness from an ignored env file without exposing values", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "supabase-live-readiness-"));
  const envPath = path.join(root, ".env.production.local");
  await writeFile(
    envPath,
    `${Object.entries(completeSecrets).map(([name, value]) => `${name}=${value}`).join("\n")}\n`,
    "utf8",
  );

  const result = spawnSync(process.execPath, [auditPath], {
    cwd: repoRoot,
    encoding: "utf8",
    env: {
      ...process.env,
      SUPABASE_ACCESS_TOKEN: "test-access-token-not-for-output",
      V9_PRODUCTION_SECRETS_FILE: envPath,
    },
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const report = JSON.parse(result.stdout);
  const secretsCheck = report.checks.find((check) => check.id === "production-secrets-present-locally");
  const probeCheck = report.checks.find((check) => check.id === "remote-probe-production-config");
  const recoveryCheck = report.checks.find((check) => check.id === "account-recovery-redirect-reachable");
  assert.equal(secretsCheck.status, "ready");
  assert.equal(probeCheck.status, "ready");
  assert.equal(recoveryCheck.status, "missing");
  assert.equal(report.accountRecoveryRedirectReachable, false);
  assert.equal(report.canDeployNow, false);
  assert.equal(report.requiredSecretsPresentInProductionFile.length, report.requiredSecretNames.length);
  assert.equal(report.productionSecretsSource, envPath);
  assert.equal(report.requiredSecretNames.includes("SUPABASE_URL"), false);
  assert.equal(report.requiredSecretNames.includes("SUPABASE_SERVICE_ROLE_KEY"), false);
  assert.equal(report.requiredSecretNames.includes("SUPABASE_DB_URL"), false);

  const output = result.stdout + result.stderr;
  for (const [name, value] of Object.entries(completeSecrets)) {
    if (value && /(API_KEY|TOKEN)/.test(name)) {
      assert.equal(output.includes(value), false, `secret value leaked for ${name}`);
    }
  }
  assert.equal(output.includes("test-access-token-not-for-output"), false);
});
