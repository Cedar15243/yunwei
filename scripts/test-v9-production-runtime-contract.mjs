import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.join(import.meta.dirname, "..");
const deployPath = path.join(root, "scripts/deploy-dingdang-supabase-prod.ps1");
const smokePath = path.join(root, "scripts/run-dingdang-real-live-smoke.ps1");
const installerPath = path.join(root, "scripts/install-and-verify-v9-release.ps1");
const shortcutPath = path.join(root, "scripts/test-dingdang-air3-shortcuts.ps1");
const summaryValidatorPath = path.join(root, "scripts/validate-dingdang-real-smoke-summary.mjs");
const supabaseReadinessAuditPath = path.join(root, "scripts/audit-dingdang-supabase-live-readiness.mjs");
const runbookPath = path.join(root, "docs/dingdang-final-verification-runbook.md");

function read(filePath) {
  return fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "");
}

function runPowerShell(scriptPath, args = []) {
  const executable = process.platform === "win32" ? "powershell" : "pwsh";
  return spawnSync(executable, [
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    scriptPath,
    ...args,
  ], {
    cwd: root,
    encoding: "utf8",
  });
}

function runNode(scriptPath, args = []) {
  return spawnSync(process.execPath, [scriptPath, ...args], {
    cwd: root,
    encoding: "utf8",
  });
}

assert.ok(fs.existsSync(installerPath), "formal V9 production installer is missing");

const deploy = read(deployPath);
const smoke = read(smokePath);
const installer = read(installerPath);
const shortcut = read(shortcutPath);
const supabaseReadinessAudit = read(supabaseReadinessAuditPath);
const runbook = read(runbookPath);

assert.match(deploy, /build-v9-release\.ps1/);
assert.match(deploy, /install-and-verify-v9-release\.ps1/);
assert.match(deploy, /DINGDANG_BACKEND_BASE_URL/);
assert.match(deploy, /V9_DEVICE_ACTIVATION_BASE_URL/);
assert.doesNotMatch(deploy, /build-dingdang-ops-ai-apk\.ps1/);
assert.doesNotMatch(deploy, /install-and-verify-dingdang-ops-ai\.ps1/);
assert.doesNotMatch(deploy, /DINGDANG_BACKEND_API_KEY/);

for (const source of [smoke, installer]) {
  assert.match(source, /com\.codex\.air3nativecamera\.dingdangexpert\.v9/);
  assert.match(source, /900000/);
  assert.match(source, /9\.0\.0/);
  assert.doesNotMatch(source, /com\.codex\.air3nativecamera\.dingdangops/);
  assert.match(source, /\$resolvedRows/);
  assert.match(source, /\$resolvedRows\.Count -gt 0/);
  assert.match(source, /function Resolve-AdbPath/);
  assert.match(source, /--git-common-dir/);
  assert.match(source, /platform-tools\\adb\.exe/);
}

assert.match(smoke, /cmd["']?,?[\s\S]*package["']?,?[\s\S]*resolve-activity/);
assert.match(smoke, /\$resolvedComponent/);
assert.match(smoke, /\[regex\]::Escape\(\$Package\)/);
assert.match(smoke, /Voice latency stage=asr_first_partial/);
assert.match(smoke, /Voice latency stage=asr_final/);
assert.match(smoke, /Realtime ASR selected source=/);
assert.match(smoke, /Photo context ready imageId=/);
assert.match(smoke, /\$cameraReadyMarker/);
assert.match(smoke, /\$cameraBackMarker/);
assert.match(smoke, /\$assistantTitleMarker/);
assert.match(smoke, /\$fieldInputMarker/);
assert.match(smoke, /\$imageAttachedMarker/);
assert.match(smoke, /asrProviderSource/);
assert.match(smoke, /photoContextReady/);
assert.doesNotMatch(smoke, /Realtime ASR start/);
assert.doesNotMatch(smoke, /Realtime ASR final/);
assert.doesNotMatch(smoke, /\$chatHistoryMarker|\$dingdangLabelMarker/);
assert.doesNotMatch(smoke, /\$cameraPromptMarker|\$returnChatMarker/);
assert.match(supabaseReadinessAudit, /Voice latency stage=asr_first_partial/);
assert.match(supabaseReadinessAudit, /Voice latency stage=asr_final/);
assert.match(supabaseReadinessAudit, /Realtime ASR selected source=/);
assert.doesNotMatch(supabaseReadinessAudit, /["']Realtime ASR partial["']/);
assert.doesNotMatch(supabaseReadinessAudit, /["']Realtime ASR final["']/);
assert.match(installer, /release-manifest\.json/);
assert.match(installer, /Get-FileHash/);
assert.match(installer, /install["']?,?[\s\S]*-r["']?,?[\s\S]*-g/);
assert.match(installer, /investorv8audit48/);
assert.match(shortcut, /function Resolve-AdbPath/);
assert.match(shortcut, /--git-common-dir/);
assert.match(shortcut, /\$Package\s*=\s*"com\.codex\.air3nativecamera\.dingdangexpert\.v9"/);
assert.match(shortcut, /\$ExpectedVersionCode\s*=\s*900000/);
assert.match(shortcut, /\$ExpectedVersionName\s*=\s*"9\.0\.0"/);
assert.doesNotMatch(shortcut, /com\.codex\.air3nativecamera\.dingdangops/);
assert.doesNotMatch(shortcut, /ExpectedVersionCode\s*=\s*602/);
assert.doesNotMatch(runbook, /install-and-verify-dingdang-ops-ai\.ps1/);
assert.match(runbook, /install-and-verify-v9-release\.ps1/);
assert.match(
  runbook,
  /test-dingdang-air3-shortcuts\.ps1[\s\S]*-Package com\.codex\.air3nativecamera\.dingdangexpert\.v9[\s\S]*-ExpectedVersionCode 900000[\s\S]*-ExpectedVersionName 9\.0\.0/,
);

const deployDryRun = runPowerShell(deployPath, [
  "-ProjectRef",
  "fixtureprojectref00001",
  "-DryRun",
]);
assert.equal(deployDryRun.status, 0, deployDryRun.stderr || deployDryRun.stdout);
assert.match(deployDryRun.stdout, /formal V9/i);
assert.match(deployDryRun.stdout, /install-and-verify-v9-release\.ps1/);
assert.doesNotMatch(deployDryRun.stdout, /dingdangops/i);

const smokeDryRun = runPowerShell(smokePath, ["-DryRun"]);
assert.equal(smokeDryRun.status, 0, smokeDryRun.stderr || smokeDryRun.stdout);
assert.match(smokeDryRun.stdout, /com\.codex\.air3nativecamera\.dingdangexpert\.v9/);
assert.match(smokeDryRun.stdout, /900000\/9\.0\.0/);
assert.doesNotMatch(smokeDryRun.stdout, /dingdangops/i);

const installerDryRun = runPowerShell(installerPath, ["-DryRun"]);
assert.equal(installerDryRun.status, 0, installerDryRun.stderr || installerDryRun.stdout);
assert.match(installerDryRun.stdout, /com\.codex\.air3nativecamera\.dingdangexpert\.v9/);
assert.match(installerDryRun.stdout, /900000\/9\.0\.0/);
assert.doesNotMatch(
  installerDryRun.stdout,
  /SUPABASE_ACCESS_TOKEN|OPS_GLASSES_API_KEY\s*=|PASSWORD\s*=|API_KEY\s*=/i,
);

const validRealSummary = {
  provider: "real",
  packageName: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  backendBaseUrlProvided: true,
  healthOk: true,
  cameraUiVisible: true,
  returnedToChatAfterPhoto: true,
  imageAttached: true,
  photoContextReady: true,
  photoContextImageId: "image-server-123",
  asrPartialVisible: true,
  asrFinalVisible: true,
  asrFinalSourceObserved: true,
  asrFinalFromCloud: true,
  asrProviderSource: "cloud",
  gptFirstDeltaObserved: true,
  inDingdang: true,
  latencyWithinBudget: true,
  latencyMs: {
    cameraUi: 1000,
    photoReturn: 2000,
    asrPartial: 300,
    asrFinal: 1200,
    gptFirstDelta: 400,
    totalInteraction: 9000,
    asrPartialSource: "logcat",
    asrFinalSource: "logcat",
    gptFirstDeltaSource: "logcat",
  },
  latencyBudgetsMs: {
    cameraUi: 5000,
    photoReturn: 12000,
    asrPartial: 5000,
    gptFirstDelta: 5000,
    totalInteraction: 45000,
  },
};

const fixtureDir = fs.mkdtempSync(path.join(os.tmpdir(), "v9-real-smoke-contract-"));
try {
  const validSummaryPath = path.join(fixtureDir, "valid-summary.json");
  fs.writeFileSync(validSummaryPath, JSON.stringify(validRealSummary), "utf8");
  const validSummaryResult = runNode(summaryValidatorPath, [validSummaryPath]);
  assert.equal(validSummaryResult.status, 0, validSummaryResult.stderr || validSummaryResult.stdout);

  const localFallbackSummaryPath = path.join(fixtureDir, "local-fallback-summary.json");
  fs.writeFileSync(localFallbackSummaryPath, JSON.stringify({
    ...validRealSummary,
    photoContextReady: false,
    photoContextImageId: "local-photo",
    asrFinalFromCloud: false,
    asrProviderSource: "local-after-primary-failure",
  }), "utf8");
  const localFallbackResult = runNode(summaryValidatorPath, [localFallbackSummaryPath]);
  assert.notEqual(localFallbackResult.status, 0, "local ASR/photo fallback must not pass real provider validation");
  assert.match(
    `${localFallbackResult.stdout}\n${localFallbackResult.stderr}`,
    /photoContextReady|photoContextImageId|asrFinalFromCloud|asrProviderSource/,
  );
} finally {
  fs.rmSync(fixtureDir, { recursive: true, force: true });
}

console.log("V9 production runtime contract tests passed.");
