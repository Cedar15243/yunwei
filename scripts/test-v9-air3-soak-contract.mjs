import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.join(import.meta.dirname, "..");
const runnerPath = path.join(root, "scripts/run-v9-air3-hardware-soak.ps1");
const packageJsonPath = path.join(root, "package.json");

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

assert.ok(fs.existsSync(runnerPath), "V9 Air3 hardware soak runner is missing");
const runner = read(runnerPath);
const packageJson = JSON.parse(read(packageJsonPath));

assert.equal(packageJson.scripts["test:v9-air3-soak-contract"], "node scripts/test-v9-air3-soak-contract.mjs");
assert.equal(packageJson.scripts["run:v9-air3-soak"], "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-v9-air3-hardware-soak.ps1");
assert.match(runner, /\$Package\s*=\s*"com\.codex\.air3nativecamera\.dingdangexpert\.v9"/);
assert.match(runner, /\$ExpectedVersionCode\s*=\s*900000/);
assert.match(runner, /\$ExpectedVersionName\s*=\s*"9\.0\.0"/);
assert.match(runner, /function Resolve-AdbPath/);
assert.match(runner, /--git-common-dir/);
assert.match(runner, /platform-tools\\adb\.exe/);
assert.match(runner, /\[switch\]\$DryRun/);
assert.match(runner, /DurationSeconds/);
assert.match(runner, /CameraCycles/);
assert.match(runner, /dumpsys[^\r\n]+battery/);
assert.match(runner, /dumpsys[^\r\n]+thermalservice/);
assert.match(runner, /dumpsys[^\r\n]+meminfo/);
assert.match(runner, /dumpsys[^\r\n]+gfxinfo/);
assert.match(runner, /dumpsys[^\r\n]+media\.camera/);
assert.match(runner, /dumpsys[^\r\n]+audio/);
assert.match(runner, /logcat[^\r\n]+-b[^\r\n]+crash/);
assert.match(runner, /ANR|anr/i);
assert.match(runner, /summary\.json/);
assert.match(runner, /passed/);
assert.match(runner, /cameraLeak/);
assert.match(runner, /audioLeak/);
assert.match(runner, /powerMeasurementValid/);
assert.match(runner, /thermalSensorPeaksC/);
assert.match(runner, /thermalSensorSampleCount/);
assert.match(runner, /requiredSampleCount\s*=\s*\[Math\]::Ceiling\(\$DurationSeconds\s*\/\s*\$SampleIntervalSeconds\)\s*\+\s*1/);
assert.match(runner, /nextSampleAt/);
assert.match(runner, /while\s*\(\$snapshots\.Count\s*-lt\s*\$requiredSampleCount\)/);
assert.doesNotMatch(runner, /Measure-Object\s+-Property\s+(?:battery|thermal|pss)\./);
assert.match(runner, /ForEach-Object\s*\{\s*\$_\.battery\.temperature\s*\}/);
assert.match(runner, /ForEach-Object\s*\{\s*\$_\.thermal\.status\s*\}/);
assert.match(runner, /ForEach-Object\s*\{\s*\$_\.pss\.pssKb\s*\}/);
assert.match(runner, /input keyevent 80/);
assert.match(runner, /input keyevent 66/);
assert.match(runner, /input keyevent 4/);
assert.match(runner, /\$cameraReadyMarker\s*=\s*New-Text/);
assert.match(runner, /\$cameraBackMarker\s*=\s*New-Text/);
assert.match(runner, /Active Camera Clients:\\s\*\\\[\\s\*\\\]/);
assert.doesNotMatch(runner, /Device\\s\+\\d\+\\s\+is\\s\+closed/);
assert.doesNotMatch(runner, /Invoke-RestMethod/);
assert.doesNotMatch(runner, /DINGDANG_BACKEND_API_KEY|OPS_GLASSES_API_KEY|SUPABASE_ACCESS_TOKEN/);
assert.doesNotMatch(runner, /com\.codex\.air3nativecamera\.dingdangops/);

const dryRun = runPowerShell(runnerPath, ["-DryRun", "-DurationSeconds", "5", "-CameraCycles", "1"]);
assert.equal(dryRun.status, 0, dryRun.stderr || dryRun.stdout);
assert.match(dryRun.stdout, /com\.codex\.air3nativecamera\.dingdangexpert\.v9/);
assert.match(dryRun.stdout, /900000\/9\.0\.0/);
assert.match(dryRun.stdout, /no network|without network|不访问网络/i);
assert.doesNotMatch(dryRun.stdout, /API_KEY|SECRET|TOKEN/i);

console.log("V9 Air3 hardware soak contract tests passed.");
