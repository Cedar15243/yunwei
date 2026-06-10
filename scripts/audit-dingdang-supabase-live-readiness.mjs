import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

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

function run(command, args) {
  const executable = process.platform === "win32" && command === "npx"
    ? (process.env.ComSpec || "cmd.exe")
    : command;
  const finalArgs = process.platform === "win32" && command === "npx"
    ? ["/d", "/s", "/c", ["npx", ...args].join(" ")]
    : args;
  const result = spawnSync(executable, finalArgs, {
    cwd: root,
    encoding: "utf8",
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

const envExample = readText("supabase/.env.example");
const edgeFunction = readText("supabase/functions/ops-glasses/index.ts");
const deployScriptPath = "scripts/deploy-dingdang-supabase-prod.ps1";
const deployScript = readText(deployScriptPath);
const realSmokeScriptPath = "scripts/run-dingdang-real-live-smoke.ps1";
const realSmokeScript = readText(realSmokeScriptPath);
const realSmokeValidatorPath = "scripts/validate-dingdang-real-smoke-summary.mjs";
const realSmokeValidator = readText(realSmokeValidatorPath);
const denoConfigExists = exists("supabase/functions/ops-glasses/deno.json");
const projectRefExists = exists(".supabase/project-ref");
const projectRef = projectRefExists ? readText(".supabase/project-ref").trim() : "";
const hasAccessToken = Boolean(process.env.SUPABASE_ACCESS_TOKEN);
const hasLocalSupabaseToken =
  fileExists(path.join(process.env.USERPROFILE || "", ".supabase", "access-token")) ||
  fileExists(path.join(process.env.USERPROFILE || "", ".supabase", "config.toml"));

const cliVersion = run("npx", ["supabase", "--version"]);
const deployHelp = run("npx", ["supabase", "functions", "deploy", "--help"]);
const secretsHelp = run("npx", ["supabase", "secrets", "set", "--help"]);
const linkHelp = run("npx", ["supabase", "link", "--help"]);

const requiredSecretNames = [
  "SUPABASE_URL",
  "SUPABASE_SERVICE_ROLE_KEY",
  "OPS_GLASSES_API_KEY",
  "OPENAI_API_KEY",
  "OPENAI_BASE_URL",
  "OPENAI_VISION_MODEL",
  "DASHSCOPE_API_KEY",
  "DASHSCOPE_FUNASR_URL",
  "DASHSCOPE_FUNASR_MODEL",
];

const requiredSecretsInExample = requiredSecretNames.filter((name) => envExample.includes(`${name}=`));
const missingSecretsInExample = requiredSecretNames.filter((name) => !envExample.includes(`${name}=`));
const requiredSecretsInProcessEnv = requiredSecretNames.filter((name) => Boolean(process.env[name]));

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
];
const missingEdgeMarkers = edgeFunctionMarkers.filter((marker) => !edgeFunction.includes(marker));
const deployScriptMarkers = [
  "supabase",
  "secrets",
  "set",
  "--env-file",
  "functions",
  "deploy",
  "DINGDANG_BACKEND_BASE_URL",
  "OPS_GLASSES_API_KEY",
  "install-and-verify-dingdang-ops-ai.ps1",
];
const missingDeployScriptMarkers = deployScriptMarkers.filter((marker) => !deployScript.includes(marker));
const realSmokeScriptMarkers = [
  "BackendBaseUrl",
  "DryRun",
  "AllowNoRealProviderEvidence",
  "DingdangKey",
  "Realtime ASR partial",
  "Realtime ASR final",
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
    projectRefExists && projectRef.length > 0 ? "ready" : "missing",
    projectRefExists ? "Local .supabase/project-ref exists." : "Local .supabase/project-ref is missing.",
    [projectRefExists ? ".supabase/project-ref" : null],
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
    ["supabase/functions/ops-glasses/index.ts", "supabase/functions/ops-glasses/deno.json"],
  ),
  item(
    "secret-names-documented",
    missingSecretsInExample.length === 0 ? "ready" : "missing",
    missingSecretsInExample.length
      ? `supabase/.env.example missing secret placeholders: ${missingSecretsInExample.join(", ")}`
      : "supabase/.env.example documents all required secret names.",
    ["supabase/.env.example"],
  ),
  item(
    "production-deploy-script",
    exists(deployScriptPath) && missingDeployScriptMarkers.length === 0 ? "ready" : "missing",
    missingDeployScriptMarkers.length
      ? `Deployment script is missing expected safe deployment markers: ${missingDeployScriptMarkers.join(", ")}`
      : "Production deploy helper uses Supabase secrets env-file, deploys the Edge Function, and builds/installs the APK with backend config.",
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
    requiredSecretsInProcessEnv.length === requiredSecretNames.length ? "ready" : "missing",
    requiredSecretsInProcessEnv.length === requiredSecretNames.length
      ? "All required secret names are present in the process environment. Values are intentionally not printed."
      : `Only ${requiredSecretsInProcessEnv.length}/${requiredSecretNames.length} required secret names are present in the process environment. Values are intentionally not printed.`,
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
  "production-deploy-script",
  "real-provider-smoke-script",
  "real-provider-smoke-validator",
]);
const criticalMissing = missing.some((check) => criticalIds.has(check.id));
const canDeployNow = !criticalMissing && projectRefExists && (hasAccessToken || hasLocalSupabaseToken);
const canRunRealLiveSmoke =
  canDeployNow && requiredSecretsInProcessEnv.length === requiredSecretNames.length;

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
  accessTokenAvailable: hasAccessToken || hasLocalSupabaseToken,
  requiredSecretNames,
  requiredSecretsDocumented: requiredSecretsInExample,
  requiredSecretsPresentInProcessEnv: requiredSecretsInProcessEnv,
  missing,
  checks,
  nextCommands,
};

console.log(JSON.stringify(result, null, 2));
