import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const sourceRoot = path.join(import.meta.dirname, "..");
const sourceScript = path.join(sourceRoot, "scripts/deploy-dingdang-supabase-prod.ps1");
const sourceContent = fs.readFileSync(sourceScript, "utf8");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "supabase-deploy-ref-"));

function createFixture(name) {
  const root = path.join(temporary, name);
  const script = path.join(root, "scripts/deploy-dingdang-supabase-prod.ps1");
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.copyFileSync(sourceScript, script);
  return { root, script };
}

function runDryRun(fixture) {
  const executable = process.platform === "win32" ? "powershell" : "pwsh";
  return spawnSync(executable, [
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    fixture.script,
    "-DryRun",
  ], {
    cwd: fixture.root,
    encoding: "utf8",
  });
}

function runPreflight(fixture, secretsFile, gatewayOverlayFile, voiceprintOverlayFile) {
  const executable = process.platform === "win32" ? "powershell" : "pwsh";
  return spawnSync(executable, [
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    fixture.script,
    "-ProjectRef",
    "linkedprojectref00001",
    "-SecretsEnvFile",
    secretsFile,
    "-GatewayOverlayEnvFile",
    gatewayOverlayFile,
    "-VoiceprintOverlayEnvFile",
    voiceprintOverlayFile,
    "-PreflightOnly",
  ], {
    cwd: fixture.root,
    encoding: "utf8",
  });
}

try {
  assert.match(sourceContent, /Assert-ProductionRecoveryRedirectReachable/);
  assert.match(sourceContent, /OPS_ACCOUNT_RECOVERY_REDIRECT_URL/);
  assert.match(sourceContent, /\[System\.Net\.Dns\]::GetHostAddresses/);
  assert.match(sourceContent, /AddressFamily\]::InterNetwork/);
  assert.match(sourceContent, /Invoke-WebRequest[\s\S]*-Method Get[\s\S]*-TimeoutSec 15/);
  const preflightReturn = sourceContent.indexOf("if ($PreflightOnly)");
  const reachabilityCall = sourceContent.lastIndexOf(
    "Assert-ProductionRecoveryRedirectReachable -SupabaseSecretsPath $secretsPath",
  );
  const authCall = sourceContent.lastIndexOf("Assert-SupabaseAuth");
  const secretsWrite = sourceContent.indexOf('Write-Output "Setting Supabase Function secrets');
  assert.ok(preflightReturn >= 0 && preflightReturn < reachabilityCall);
  assert.ok(reachabilityCall < authCall && authCall < secretsWrite);

  const linked = createFixture("linked");
  fs.mkdirSync(path.join(linked.root, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(linked.root, "supabase/.temp/linked-project.json"),
    `${JSON.stringify({
      ref: "linkedprojectref00001",
      name: "PRIVATE_ACCOUNT_MARKER",
    })}\n`,
    "utf8",
  );
  const linkedResult = runDryRun(linked);
  assert.equal(linkedResult.status, 0, linkedResult.stderr || linkedResult.stdout);
  assert.match(linkedResult.stdout, /Dry run for Supabase project linkedprojectref00001\./);
  assert.doesNotMatch(`${linkedResult.stdout}\n${linkedResult.stderr}`, /PRIVATE_ACCOUNT_MARKER/);

  const legacy = createFixture("legacy");
  fs.mkdirSync(path.join(legacy.root, ".supabase"), { recursive: true });
  fs.mkdirSync(path.join(legacy.root, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(legacy.root, ".supabase/project-ref"),
    "legacyprojectref00001\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(legacy.root, "supabase/.temp/linked-project.json"),
    `${JSON.stringify({ ref: "linkedprojectref00001" })}\n`,
    "utf8",
  );
  const legacyResult = runDryRun(legacy);
  assert.equal(legacyResult.status, 0, legacyResult.stderr || legacyResult.stdout);
  assert.match(legacyResult.stdout, /Dry run for Supabase project legacyprojectref00001\./);
  assert.doesNotMatch(legacyResult.stdout, /linkedprojectref00001/);

  const invalid = createFixture("invalid");
  fs.mkdirSync(path.join(invalid.root, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(invalid.root, "supabase/.temp/linked-project.json"),
    "not-json\n",
    "utf8",
  );
  const invalidResult = runDryRun(invalid);
  assert.notEqual(invalidResult.status, 0);
  assert.match(`${invalidResult.stdout}\n${invalidResult.stderr}`, /ProjectRef is required/);

  const preflight = createFixture("preflight");
  const gitInit = spawnSync("git", ["init"], { cwd: preflight.root, encoding: "utf8" });
  assert.equal(gitInit.status, 0, gitInit.stderr || gitInit.stdout);
  fs.writeFileSync(path.join(preflight.root, ".gitignore"), ".env.*\n", "utf8");
  const voiceprintAdminToken = "test-only-voiceprint-admin-token-000000000000";
  const knowledgeParserToken = "test-only-knowledge-parser-token-00000000000";
  const manifestSyncToken = "test-only-manifest-sync-token-00000000000000";
  const hash = (value) => crypto.createHash("sha256").update(value).digest("hex");
  const safeSecrets = path.join(preflight.root, ".env.production.local");
  fs.writeFileSync(safeSecrets, [
    "AUTO_MIGRATE=true",
    `V9_GATEWAY_SYNC_TOKEN_SHA256=${hash(manifestSyncToken)}`,
    `V9_VOICEPRINT_ADMIN_TOKEN=${voiceprintAdminToken}`,
    `V9_KNOWLEDGE_PARSER_TOKEN=${knowledgeParserToken}`,
    "",
  ].join("\n"), "utf8");
  const safeGatewayOverlay = path.join(preflight.root, ".env.gateway-overlay.local");
  fs.writeFileSync(safeGatewayOverlay, [
    `V9_CONTENT_MANIFEST_SYNC_TOKEN=${manifestSyncToken}`,
    `V9_KNOWLEDGE_PARSER_TOKEN_SHA256=${hash(knowledgeParserToken)}`,
    "",
  ].join("\n"), "utf8");
  const safeVoiceprintOverlay = path.join(preflight.root, ".env.voiceprint-overlay.local");
  fs.writeFileSync(safeVoiceprintOverlay, [
    `V9_VOICEPRINT_ADMIN_TOKEN_SHA256=${hash(voiceprintAdminToken)}`,
    "",
  ].join("\n"), "utf8");
  const safeResult = runPreflight(
    preflight,
    safeSecrets,
    safeGatewayOverlay,
    safeVoiceprintOverlay,
  );
  assert.equal(safeResult.status, 0, safeResult.stderr || safeResult.stdout);
  assert.match(safeResult.stdout, /preflight passed/i);

  const mismatchedVoiceprintOverlay = path.join(preflight.root, ".env.voiceprint-mismatch.local");
  fs.writeFileSync(mismatchedVoiceprintOverlay, [
    `V9_VOICEPRINT_ADMIN_TOKEN_SHA256=${hash("different-voiceprint-token")}`,
    "",
  ].join("\n"), "utf8");
  const mismatchedResult = runPreflight(
    preflight,
    safeSecrets,
    safeGatewayOverlay,
    mismatchedVoiceprintOverlay,
  );
  assert.notEqual(mismatchedResult.status, 0);
  assert.match(
    `${mismatchedResult.stdout}\n${mismatchedResult.stderr}`,
    /voiceprint admin token hash mismatch/i,
  );
  assert.doesNotMatch(
    `${mismatchedResult.stdout}\n${mismatchedResult.stderr}`,
    new RegExp(voiceprintAdminToken),
  );

  const reservedSecrets = path.join(preflight.root, ".env.reserved.local");
  fs.writeFileSync(reservedSecrets, "SUPABASE_URL=https://example.supabase.co\nAUTO_MIGRATE=true\n", "utf8");
  const reservedResult = runPreflight(
    preflight,
    reservedSecrets,
    safeGatewayOverlay,
    safeVoiceprintOverlay,
  );
  assert.notEqual(reservedResult.status, 0);
  assert.match(`${reservedResult.stdout}\n${reservedResult.stderr}`, /reserved Supabase secret prefix/i);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("Supabase deploy project reference tests passed.");
