import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(import.meta.dirname, "..");
const scriptPath = path.join(repoRoot, "scripts", "prepare-v9-production-secrets.mjs");

async function fixture() {
  const root = await mkdtemp(path.join(tmpdir(), "v9-production-secrets-"));
  const gatewayEnv = path.join(root, "gateway.env");
  const voiceprintEnv = path.join(root, "voiceprint.env");
  const bootstrapToken = path.join(root, "bootstrap-token.local");
  const supabaseSeed = path.join(root, "supabase-seed.env.local");
  const gatewaySeed = path.join(root, "gateway-seed.env.local");
  const supabaseOutput = path.join(root, "supabase.env.local");
  const gatewayOutput = path.join(root, "gateway-overlay.env.local");
  const voiceprintOutput = path.join(root, "voiceprint-overlay.env.local");
  const seededOpsToken = "o".repeat(43);
  const seededVoiceprintToken = "v".repeat(43);
  const seededParserToken = "p".repeat(43);
  const seededManifestToken = "m".repeat(43);

  await writeFile(gatewayEnv, [
    "V9_BOOTSTRAP_TOKEN_SHA256=unused-by-composer",
    "V9_DEVICE_ID=air3-production-device",
    "V9_ORGANIZATION_ID=org-production",
    "V9_USER_ID=user-production",
    "V9_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1",
    "V9_AI_API_KEY=fake-dashscope-provider-secret",
    "V9_AI_MODEL=qwen3-vl-plus",
    "V9_ASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    "V9_ASR_MODEL=fun-asr-realtime",
    "",
  ].join("\n"), "utf8");
  await writeFile(voiceprintEnv, [
    "IFLYTEK_APP_ID=fake-app-id",
    "IFLYTEK_API_KEY=fake-iflytek-key",
    "IFLYTEK_API_SECRET=fake-iflytek-secret",
    "V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0",
    `V9_VOICEPRINT_ADMIN_TOKEN_SHA256=${"c".repeat(64)}`,
    "",
  ].join("\n"), "utf8");
  await writeFile(bootstrapToken, "fake-existing-bootstrap-token-with-43-bytes\n", "utf8");
  await writeFile(supabaseSeed, [
    `OPS_GLASSES_API_KEY=${seededOpsToken}`,
    `V9_VOICEPRINT_ADMIN_TOKEN=${seededVoiceprintToken}`,
    `V9_KNOWLEDGE_PARSER_TOKEN=${seededParserToken}`,
    "",
  ].join("\n"), "utf8");
  await writeFile(gatewaySeed, [
    `V9_CONTENT_MANIFEST_SYNC_TOKEN=${seededManifestToken}`,
    "",
  ].join("\n"), "utf8");

  return {
    root,
    gatewayEnv,
    voiceprintEnv,
    bootstrapToken,
    supabaseSeed,
    gatewaySeed,
    supabaseOutput,
    gatewayOutput,
    voiceprintOutput,
    seededOpsToken,
    seededVoiceprintToken,
    seededParserToken,
    seededManifestToken,
  };
}

function runComposer(paths, extra = {}) {
  return spawnSync(process.execPath, [
    scriptPath,
    "--gateway-env", paths.gatewayEnv,
    "--voiceprint-env", paths.voiceprintEnv,
    "--bootstrap-token-file", paths.bootstrapToken,
    "--supabase-seed-env", paths.supabaseSeed,
    "--gateway-seed-env", paths.gatewaySeed,
    "--supabase-output", paths.supabaseOutput,
    "--gateway-output", paths.gatewayOutput,
    "--voiceprint-output", paths.voiceprintOutput,
    "--voiceprint-rotation-mode", "transition",
    "--project-ref", "zasgzaatthvfglhbxpgo",
    "--public-gateway-base-url", "https://bb.chinacedar.top:2305/v9-ops",
    "--account-recovery-redirect-url", "https://ops.example.com/",
    "--default-asset-tag", "HF-CLOUD-SERVER-001",
    "--default-target-ssh-port", "2304",
    "--default-target-app-port", "2305",
    "--remote-probe-mode", "tcp",
  ], {
    cwd: repoRoot,
    encoding: "utf8",
    env: {
      ...process.env,
      ...extra,
    },
  });
}

function parseEnv(text) {
  return Object.fromEntries(text.split(/\r?\n/).filter(Boolean).map((line) => {
    const separator = line.indexOf("=");
    return [line.slice(0, separator), line.slice(separator + 1)];
  }));
}

test("composes complete Supabase and gateway production secrets without printing values", async () => {
  const paths = await fixture();
  const result = runComposer(paths);
  assert.equal(result.status, 0, result.stderr);

  const supabase = parseEnv(await readFile(paths.supabaseOutput, "utf8"));
  const gateway = parseEnv(await readFile(paths.gatewayOutput, "utf8"));
  const voiceprint = parseEnv(await readFile(paths.voiceprintOutput, "utf8"));
  assert.equal(Object.keys(supabase).some((name) => name.startsWith("SUPABASE_")), false);
  assert.equal(supabase.OPENAI_API_KEY, "fake-dashscope-provider-secret");
  assert.equal(supabase.OPENAI_VISION_MODEL, "qwen3-vl-plus");
  assert.equal(supabase.DASHSCOPE_FUNASR_MODEL, "fun-asr-realtime");
  assert.equal(supabase.V9_VOICEPRINT_ADMIN_URL, "https://bb.chinacedar.top:2305/v9-ops");
  assert.equal(supabase.V9_KNOWLEDGE_PARSER_URL, "https://bb.chinacedar.top:2305/v9-ops");
  assert.equal(supabase.V9_GATEWAY_SYNC_DEVICE_ID, "air3-production-device");
  assert.equal(supabase.DEFAULT_ASSET_TAG, "HF-CLOUD-SERVER-001");
  assert.equal(supabase.DEFAULT_TARGET_SSH_PORT, "2304");
  assert.equal(supabase.DEFAULT_TARGET_APP_PORT, "2305");
  assert.equal(supabase.REMOTE_PROBE_MODE, "tcp");
  assert.match(supabase.OPS_GLASSES_API_KEY, /^[A-Za-z0-9_-]{43,}$/);
  assert.match(supabase.V9_VOICEPRINT_ADMIN_TOKEN, /^[A-Za-z0-9_-]{43,}$/);
  assert.match(supabase.V9_KNOWLEDGE_PARSER_TOKEN, /^[A-Za-z0-9_-]{43,}$/);
  assert.equal(
    supabase.V9_GATEWAY_SYNC_TOKEN_SHA256,
    createHash("sha256").update(gateway.V9_CONTENT_MANIFEST_SYNC_TOKEN).digest("hex"),
  );
  assert.equal(supabase.OPS_GLASSES_API_KEY, paths.seededOpsToken);
  assert.equal(supabase.V9_VOICEPRINT_ADMIN_TOKEN, paths.seededVoiceprintToken);
  assert.equal(supabase.V9_KNOWLEDGE_PARSER_TOKEN, paths.seededParserToken);
  assert.equal(gateway.V9_CONTENT_MANIFEST_SYNC_TOKEN, paths.seededManifestToken);
  assert.equal(
    voiceprint.V9_VOICEPRINT_ADMIN_TOKEN_SHA256,
    createHash("sha256").update(supabase.V9_VOICEPRINT_ADMIN_TOKEN).digest("hex"),
  );
  assert.equal(
    gateway.V9_KNOWLEDGE_PARSER_TOKEN_SHA256,
    createHash("sha256").update(supabase.V9_KNOWLEDGE_PARSER_TOKEN).digest("hex"),
  );
  assert.equal(gateway.V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN, "fake-existing-bootstrap-token-with-43-bytes");
  assert.equal(gateway.V9_CONTENT_MANIFEST_SYNC_BASE_URL, "https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses");
  assert.equal("V9_VOICEPRINT_ADMIN_TOKEN_SHA256" in gateway, false);
  assert.deepEqual(Object.keys(voiceprint), [
    "V9_VOICEPRINT_ADMIN_TOKEN_SHA256",
    "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256",
  ]);
  assert.equal(voiceprint.V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256, "c".repeat(64));

  const combinedOutput = result.stdout + result.stderr;
  for (const secret of [
    "fake-dashscope-provider-secret",
    "fake-iflytek-secret",
    supabase.OPS_GLASSES_API_KEY,
    supabase.V9_VOICEPRINT_ADMIN_TOKEN,
    supabase.V9_KNOWLEDGE_PARSER_TOKEN,
    gateway.V9_CONTENT_MANIFEST_SYNC_TOKEN,
  ]) {
    assert.equal(combinedOutput.includes(secret), false);
  }
});

test("preserves generated bridge tokens across repeated preparation", async () => {
  const paths = await fixture();
  assert.equal(runComposer(paths).status, 0);
  const firstSupabase = parseEnv(await readFile(paths.supabaseOutput, "utf8"));
  const firstGateway = parseEnv(await readFile(paths.gatewayOutput, "utf8"));
  const firstVoiceprint = parseEnv(await readFile(paths.voiceprintOutput, "utf8"));

  assert.equal(runComposer(paths).status, 0);
  const secondSupabase = parseEnv(await readFile(paths.supabaseOutput, "utf8"));
  const secondGateway = parseEnv(await readFile(paths.gatewayOutput, "utf8"));
  const secondVoiceprint = parseEnv(await readFile(paths.voiceprintOutput, "utf8"));
  assert.equal(secondSupabase.OPS_GLASSES_API_KEY, firstSupabase.OPS_GLASSES_API_KEY);
  assert.equal(secondSupabase.V9_VOICEPRINT_ADMIN_TOKEN, firstSupabase.V9_VOICEPRINT_ADMIN_TOKEN);
  assert.equal(secondSupabase.V9_KNOWLEDGE_PARSER_TOKEN, firstSupabase.V9_KNOWLEDGE_PARSER_TOKEN);
  assert.equal(secondGateway.V9_CONTENT_MANIFEST_SYNC_TOKEN, firstGateway.V9_CONTENT_MANIFEST_SYNC_TOKEN);
  assert.equal(
    secondVoiceprint.V9_VOICEPRINT_ADMIN_TOKEN_SHA256,
    firstVoiceprint.V9_VOICEPRINT_ADMIN_TOKEN_SHA256,
  );
  assert.equal(
    secondVoiceprint.V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256,
    firstVoiceprint.V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256,
  );
});

test("fails closed before writing when the provider model baseline drifts", async () => {
  const paths = await fixture();
  await writeFile(paths.gatewayEnv, [
    "V9_DEVICE_ID=air3-production-device",
    "V9_ORGANIZATION_ID=org-production",
    "V9_USER_ID=user-production",
    "V9_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1",
    "V9_AI_API_KEY=fake-dashscope-provider-secret",
    "V9_AI_MODEL=unapproved-new-model",
    "V9_ASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    "V9_ASR_MODEL=fun-asr-realtime",
    "",
  ].join("\n"), "utf8");

  const result = runComposer(paths);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /provider_model_not_approved/);
  await assert.rejects(readFile(paths.supabaseOutput, "utf8"), /ENOENT/);
  await assert.rejects(readFile(paths.gatewayOutput, "utf8"), /ENOENT/);
  await assert.rejects(readFile(paths.voiceprintOutput, "utf8"), /ENOENT/);
});
