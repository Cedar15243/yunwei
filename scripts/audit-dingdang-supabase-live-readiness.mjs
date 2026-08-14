import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

import { resolveSupabaseProjectRef } from "./supabase-project-ref.mjs";

const root = process.cwd();

function exists(relativePath) {
  return fs.existsSync(path.join(root, relativePath));
}

function fileExists(filePath) {
  return fs.existsSync(filePath);
}

function readText(relativePath) {
  const filePath = path.join(root, relativePath);
  if (!fs.existsSync(filePath)) {
    return "";
  }
  return fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "");
}

function run(command, args, options = {}) {
  const executable = process.platform === "win32" && command === "npx"
    ? (process.env.ComSpec || "cmd.exe")
    : command;
  const finalArgs = process.platform === "win32" && command === "npx"
    ? ["/d", "/s", "/c", ["npx", ...args].join(" ")]
    : args;
  const result = spawnSync(executable, finalArgs, {
    cwd: root,
    encoding: "utf8",
    timeout: options.timeout,
  });
  return {
    ok: result.status === 0,
    status: result.status,
    stdout: (result.stdout || "").trim(),
    stderr: (result.stderr || "").trim(),
  };
}

function item(id, status, details, evidence = []) {
  return { id, status, details, evidence: evidence.filter(Boolean) };
}

function probeProductionHttpsEndpoint(value) {
  if (!isValidHttpsEndpoint(value)) {
    return { ready: false, reason: "invalid_https_url" };
  }
  const probe = run(process.execPath, [
    "--input-type=module",
    "-e",
    `
      import dns from "node:dns/promises";
      import https from "node:https";

      const endpoint = new URL(process.argv[1]);
      const addresses = await dns.resolve4(endpoint.hostname);
      if (!addresses.length) process.exit(2);
      const request = https.get(endpoint, {
        timeout: 15000,
        lookup: (_hostname, _options, callback) => callback(null, addresses[0], 4),
      }, (response) => {
        response.resume();
        process.exit(response.statusCode >= 200 && response.statusCode < 400 ? 0 : 3);
      });
      request.on("timeout", () => request.destroy(new Error("timeout")));
      request.on("error", () => process.exit(4));
    `,
    value,
  ], { timeout: 20000 });
  if (probe.ok) return { ready: true, reason: "reachable" };
  return {
    ready: false,
    reason: probe.status === null ? "probe_timeout" : "dns_or_https_unreachable",
  };
}

function readEnvValues(filePath) {
  if (!filePath || !fs.existsSync(filePath)) return {};
  const values = {};
  for (const rawLine of fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const name = line.slice(0, separator).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) continue;
    let value = line.slice(separator + 1).trim();
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    values[name] = value;
  }
  return values;
}

function isConfiguredValue(value) {
  const normalized = String(value ?? "").trim();
  return Boolean(normalized) &&
    !/replace-with|your-|<[^>]+>|REPLACE_WITH/i.test(normalized);
}

function isValidHttpsEndpoint(value) {
  try {
    const endpoint = new URL(value);
    return endpoint.protocol === "https:" &&
      Boolean(endpoint.hostname) &&
      !endpoint.username &&
      !endpoint.password;
  } catch {
    return false;
  }
}

const envExample = readText("supabase/.env.example");
const edgeFunction = readText("supabase/functions/ops-glasses/index.ts");
const skillKnowledgeSync = readText(
  "supabase/functions/ops-glasses/skill-knowledge-sync.ts",
);
const edgeFunctionSource = `${edgeFunction}\n${skillKnowledgeSync}`;
const deployScriptPath = "scripts/deploy-dingdang-supabase-prod.ps1";
const deployScript = readText(deployScriptPath);
const realSmokeScriptPath = "scripts/run-dingdang-real-live-smoke.ps1";
const realSmokeScript = readText(realSmokeScriptPath);
const realSmokeValidatorPath = "scripts/validate-dingdang-real-smoke-summary.mjs";
const realSmokeValidator = readText(realSmokeValidatorPath);
const denoConfigExists = exists("supabase/functions/ops-glasses/deno.json");
const projectRefResolution = resolveSupabaseProjectRef(root);
const projectRef = projectRefResolution.projectRef;
const projectRefExists = projectRef.length > 0;
const hasAccessToken = Boolean(process.env.SUPABASE_ACCESS_TOKEN);
const hasLocalSupabaseToken =
  fileExists(path.join(process.env.USERPROFILE || "", ".supabase", "access-token")) ||
  fileExists(path.join(process.env.USERPROFILE || "", ".supabase", "config.toml"));
const configuredSecretsPath = String(process.env.V9_PRODUCTION_SECRETS_FILE || "").trim();
const productionSecretsPath = configuredSecretsPath
  ? path.resolve(configuredSecretsPath)
  : path.join(root, "supabase", ".env.production.local");
const productionSecretsFileExists = fileExists(productionSecretsPath);
const productionSecretsFromFile = readEnvValues(productionSecretsPath);

const cliVersion = run("npx", ["supabase", "--version"]);
const deployHelp = run("npx", ["supabase", "functions", "deploy", "--help"]);
const secretsHelp = run("npx", ["supabase", "secrets", "set", "--help"]);
const linkHelp = run("npx", ["supabase", "link", "--help"]);

const requiredSecretNames = [
  "AUTO_MIGRATE",
  "OPS_GLASSES_API_KEY",
  "OPENAI_API_KEY",
  "OPENAI_BASE_URL",
  "OPENAI_VISION_MODEL",
  "OPENAI_TRANSCRIBE_API_KEY",
  "OPENAI_TRANSCRIBE_BASE_URL",
  "OPENAI_TRANSCRIBE_MODEL",
  "DASHSCOPE_API_KEY",
  "DASHSCOPE_FUNASR_URL",
  "DASHSCOPE_FUNASR_MODEL",
  "V9_GATEWAY_SYNC_TOKEN_SHA256",
  "V9_GATEWAY_SYNC_ORGANIZATION_ID",
  "V9_GATEWAY_SYNC_USER_ID",
  "V9_GATEWAY_SYNC_DEVICE_ID",
  "V9_VOICEPRINT_ADMIN_URL",
  "V9_VOICEPRINT_ADMIN_TOKEN",
  "V9_KNOWLEDGE_PARSER_URL",
  "V9_KNOWLEDGE_PARSER_TOKEN",
  "V9_DEVICE_ACTIVATION_BACKEND_BASE_URL",
  "V9_DEVICE_ACTIVATION_POLICY_VERSION",
  "OPS_ACCOUNT_RECOVERY_REDIRECT_URL",
  "DEFAULT_ASSET_TAG",
  "DEFAULT_TARGET_SSH_PORT",
  "REMOTE_PROBE_MODE",
];

const requiredSecretsInExample = requiredSecretNames.filter((name) => envExample.includes(`${name}=`));
const missingSecretsInExample = requiredSecretNames.filter((name) => !envExample.includes(`${name}=`));
const reservedSecretNamesInExample = envExample.split(/\r?\n/)
  .map((line) => line.match(/^\s*(SUPABASE_[A-Za-z0-9_]*)\s*=/)?.[1] ?? "")
  .filter(Boolean);
const productionSecrets = productionSecretsFileExists ? productionSecretsFromFile : process.env;
const productionSecretsSource = productionSecretsFileExists ? productionSecretsPath : "process-environment";
const requiredSecretsPresent = requiredSecretNames.filter((name) => isConfiguredValue(productionSecrets[name]));
const remoteProbeUrlDocumented = envExample.includes("REMOTE_PROBE_URL=");
const defaultAssetTag = String(productionSecrets.DEFAULT_ASSET_TAG || "").trim();
const remoteProbeMode = String(productionSecrets.REMOTE_PROBE_MODE || "").trim().toLowerCase();
const remoteProbeUrl = String(productionSecrets.REMOTE_PROBE_URL || "").trim();
const defaultAssetReady = Boolean(defaultAssetTag) &&
  defaultAssetTag !== "ASSET-CONSOLE-001" &&
  !defaultAssetTag.startsWith("replace-with-");
const remoteProbeModeReady = remoteProbeMode === "tcp" || remoteProbeMode === "http";
const remoteProbeUrlReady = remoteProbeMode !== "http" || isValidHttpsEndpoint(remoteProbeUrl);
const remoteProbeProductionReady = defaultAssetReady && remoteProbeModeReady && remoteProbeUrlReady;
const accountRecoveryRedirectUrl = String(
  productionSecrets.OPS_ACCOUNT_RECOVERY_REDIRECT_URL || "",
).trim();
const accountRecoveryRedirectProbe = probeProductionHttpsEndpoint(accountRecoveryRedirectUrl);

const edgeFunctionMarkers = [
  "DASHSCOPE_API_KEY",
  "DASHSCOPE_FUNASR_MODEL",
  "Deno.connectTls",
  "Deno.upgradeWebSocket",
  "text/event-stream",
  "event: delta",
  "event: done",
  "/images",
  "/asr",
  "diagnose\\/stream",
  "/internal/v9/content-manifest",
  "V9_GATEWAY_SYNC_TOKEN_SHA256",
  "V9_VOICEPRINT_ADMIN_URL",
  "V9_VOICEPRINT_ADMIN_TOKEN",
  "V9_DEVICE_ACTIVATION_BACKEND_BASE_URL",
  "V9_DEVICE_ACTIVATION_POLICY_VERSION",
  "/device-activation/redeem",
];
const missingEdgeMarkers = edgeFunctionMarkers.filter((marker) => !edgeFunctionSource.includes(marker));
const deployScriptMarkers = [
  "supabase",
  "secrets",
  "set",
  "--env-file",
  "functions",
  "deploy",
  "build-v9-release.ps1",
  "install-and-verify-v9-release.ps1",
  "DINGDANG_BACKEND_BASE_URL",
  "V9_DEVICE_ACTIVATION_BASE_URL",
];
const missingDeployScriptMarkers = deployScriptMarkers.filter((marker) => !deployScript.includes(marker));
const realSmokeScriptMarkers = [
  "BackendBaseUrl",
  "com.codex.air3nativecamera.dingdangexpert.v9",
  "ExpectedVersionCode = 900000",
  "ExpectedVersionName = \"9.0.0\"",
  "resolve-activity",
  "DryRun",
  "AllowNoRealProviderEvidence",
  "DingdangKey",
  "Voice latency stage=asr_first_partial",
  "Voice latency stage=asr_final",
  "Realtime ASR selected source=",
  "Photo context ready imageId=",
  "GPT stream first delta latencyMs",
  "asrPartialSource",
  "asrFinalSource",
  "latencyWithinBudget",
  "summary.json",
  "No API key or secret value is read or printed",
];
const missingRealSmokeScriptMarkers = realSmokeScriptMarkers.filter((marker) => !realSmokeScript.includes(marker));
const realSmokeValidatorMarkers = [
  "provider",
  "real",
  "com.codex.air3nativecamera.dingdangexpert.v9",
  "900000",
  "9.0.0",
  "backendBaseUrlProvided",
  "healthOk",
  "asrPartialSource",
  "asrFinalSource",
  "gptFirstDeltaSource",
  "latencyWithinBudget",
  "latestRealSummaryPath",
];
const missingRealSmokeValidatorMarkers = realSmokeValidatorMarkers.filter((marker) => !realSmokeValidator.includes(marker));

const checks = [
  item(
    "supabase-cli",
    cliVersion.ok ? "ready" : "missing",
    cliVersion.ok ? `Supabase CLI available via npx, version ${cliVersion.stdout}.` : "Supabase CLI is not available via npx.",
    ["package.json"],
  ),
  item(
    "cli-deploy-command",
    deployHelp.ok && deployHelp.stdout.includes("--project-ref") ? "ready" : "missing",
    "Current CLI supports `supabase functions deploy ... --project-ref <ref>`.",
  ),
  item(
    "cli-secrets-command",
    secretsHelp.ok && secretsHelp.stdout.includes("--env-file") ? "ready" : "missing",
    "Current CLI supports `supabase secrets set --env-file <file> --project-ref <ref>`.",
  ),
  item(
    "cli-link-command",
    linkHelp.ok && linkHelp.stdout.includes("--project-ref") ? "ready" : "missing",
    "Current CLI supports `supabase link --project-ref <ref>`.",
  ),
  item(
    "project-ref",
    projectRefExists ? "ready" : "missing",
    projectRefExists
      ? `Supabase project reference is configured via ${projectRefResolution.source}.`
      : "No supported local Supabase project reference was found.",
    [projectRefResolution.source || null],
  ),
  item(
    "access-token",
    hasAccessToken || hasLocalSupabaseToken ? "ready" : "missing",
    hasAccessToken
      ? "SUPABASE_ACCESS_TOKEN is present in the current process environment."
      : hasLocalSupabaseToken
        ? "A local Supabase auth file appears to exist."
        : "No SUPABASE_ACCESS_TOKEN or local Supabase auth file found.",
  ),
  item(
    "edge-function-source",
    exists("supabase/functions/ops-glasses/index.ts") && denoConfigExists && missingEdgeMarkers.length === 0
      ? "ready"
      : "missing",
    missingEdgeMarkers.length
      ? `Edge function is missing expected markers: ${missingEdgeMarkers.join(", ")}`
      : "Edge function source contains image upload, ASR WebSocket proxy, and GPT SSE markers.",
    [
      "supabase/functions/ops-glasses/index.ts",
      "supabase/functions/ops-glasses/skill-knowledge-sync.ts",
      "supabase/functions/ops-glasses/deno.json",
    ],
  ),
  item(
    "secret-names-documented",
    missingSecretsInExample.length === 0 && remoteProbeUrlDocumented && reservedSecretNamesInExample.length === 0
      ? "ready"
      : "missing",
    missingSecretsInExample.length || !remoteProbeUrlDocumented || reservedSecretNamesInExample.length
      ? `supabase/.env.example missing secret placeholders: ${[
        ...missingSecretsInExample,
        ...(!remoteProbeUrlDocumented ? ["REMOTE_PROBE_URL"] : []),
        ...reservedSecretNamesInExample.map((name) => `reserved:${name}`),
      ].join(", ")}`
      : "supabase/.env.example documents all required upload secret names and excludes platform-reserved SUPABASE_* defaults.",
    ["supabase/.env.example"],
  ),
  item(
    "remote-probe-production-config",
    remoteProbeProductionReady ? "ready" : "missing",
    remoteProbeProductionReady
      ? "A real asset tag and production remote probe mode are configured; values are intentionally not printed."
      : "Production requires a real DEFAULT_ASSET_TAG, REMOTE_PROBE_MODE=tcp|http, and an explicit credential-free HTTPS REMOTE_PROBE_URL when using http.",
    ["supabase/.env.example", "supabase/functions/ops-glasses/remote-probe-policy.ts"],
  ),
  item(
    "account-recovery-redirect-reachable",
    accountRecoveryRedirectProbe.ready ? "ready" : "missing",
    accountRecoveryRedirectProbe.ready
      ? "The production account recovery redirect has an IPv4 A record and returns a usable HTTPS response."
      : `The production account recovery redirect is not deployable: ${accountRecoveryRedirectProbe.reason}.`,
    productionSecretsFileExists ? [productionSecretsPath] : [],
  ),
  item(
    "production-deploy-script",
    exists(deployScriptPath) && missingDeployScriptMarkers.length === 0 ? "ready" : "missing",
    missingDeployScriptMarkers.length
      ? `Deployment script is missing expected safe deployment markers: ${missingDeployScriptMarkers.join(", ")}`
      : "Production deploy helper uses the Supabase secrets env-file, deploys the Edge Function, and builds/installs the formal secure-runtime V9 APK without embedding the server API key.",
    [exists(deployScriptPath) ? deployScriptPath : null],
  ),
  item(
    "real-provider-smoke-script",
    exists(realSmokeScriptPath) && missingRealSmokeScriptMarkers.length === 0 ? "ready" : "missing",
    missingRealSmokeScriptMarkers.length
      ? `Real provider smoke script is missing expected markers: ${missingRealSmokeScriptMarkers.join(", ")}`
      : "Real provider smoke helper drives Air3 camera, real ASR, and GPT streaming evidence without reading or printing provider secrets.",
    [exists(realSmokeScriptPath) ? realSmokeScriptPath : null],
  ),
  item(
    "real-provider-smoke-validator",
    exists(realSmokeValidatorPath) && missingRealSmokeValidatorMarkers.length === 0 ? "ready" : "missing",
    missingRealSmokeValidatorMarkers.length
      ? `Real provider smoke validator is missing expected markers: ${missingRealSmokeValidatorMarkers.join(", ")}`
      : "Real provider smoke summary validator enforces real provider, health, ASR logcat partial/final, GPT first delta, and latency budgets.",
    [exists(realSmokeValidatorPath) ? realSmokeValidatorPath : null],
  ),
  item(
    "production-secrets-present-locally",
    requiredSecretsPresent.length === requiredSecretNames.length ? "ready" : "missing",
    requiredSecretsPresent.length === requiredSecretNames.length
      ? "All required secret names are present in the ignored production env source. Values are intentionally not printed."
      : `Only ${requiredSecretsPresent.length}/${requiredSecretNames.length} required secret names are present in the production env source. Values are intentionally not printed.`,
    productionSecretsFileExists ? [productionSecretsPath] : [],
  ),
];

const readyCount = checks.filter((check) => check.status === "ready").length;
const missing = checks.filter((check) => check.status !== "ready");
const criticalIds = new Set([
  "supabase-cli",
  "cli-deploy-command",
  "cli-secrets-command",
  "edge-function-source",
  "secret-names-documented",
  "remote-probe-production-config",
  "account-recovery-redirect-reachable",
  "production-deploy-script",
  "real-provider-smoke-script",
  "real-provider-smoke-validator",
]);
const criticalMissing = missing.some((check) => criticalIds.has(check.id));
const canDeployNow = !criticalMissing &&
  projectRefExists &&
  (hasAccessToken || hasLocalSupabaseToken) &&
  requiredSecretsPresent.length === requiredSecretNames.length &&
  remoteProbeProductionReady &&
  accountRecoveryRedirectProbe.ready;
const canRunRealLiveSmoke =
  canDeployNow;

const nextCommands = [
  "npx supabase login",
  "powershell -ExecutionPolicy Bypass -File scripts\\deploy-dingdang-supabase-prod.ps1 -ProjectRef <project-ref> -SecretsEnvFile <ignored-production-env-file>",
  "powershell -ExecutionPolicy Bypass -File scripts\\run-dingdang-real-live-smoke.ps1 -BackendBaseUrl https://<project-ref>.supabase.co/functions/v1/ops-glasses",
  "npm run validate:real-smoke-summary -- tmp\\real-live-smoke-<timestamp>\\summary.json",
];

const result = {
  generatedAt: new Date().toISOString(),
  readyCount,
  total: checks.length,
  canDeployNow,
  canRunRealLiveSmoke,
  projectRefConfigured: projectRefExists,
  projectRefSource: projectRefResolution.source || null,
  accessTokenAvailable: hasAccessToken || hasLocalSupabaseToken,
  accountRecoveryRedirectReachable: accountRecoveryRedirectProbe.ready,
  requiredSecretNames,
  requiredSecretsDocumented: requiredSecretsInExample,
  requiredSecretsPresentInProductionFile: productionSecretsFileExists ? requiredSecretsPresent : [],
  requiredSecretsPresentInProcessEnv: productionSecretsFileExists ? [] : requiredSecretsPresent,
  productionSecretsSource,
  missing,
  checks,
  nextCommands,
};

console.log(JSON.stringify(result, null, 2));
