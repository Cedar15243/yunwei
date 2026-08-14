import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.join(import.meta.dirname, "..");
const validatorPath = path.join(root, "scripts/validate-v9-air3-soak-summary.mjs");
const packageJson = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "v9-air3-soak-summary-"));

const baseSummary = {
  provider: "local_device",
  packageName: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  durationSecondsRequested: 1200,
  durationSecondsObserved: 1251,
  sampleIntervalSeconds: 30,
  requiredSampleCount: 41,
  sampleCount: 41,
  sampleCoveragePassed: true,
  cameraCyclesRequested: 3,
  cameraCyclesPassed: true,
  cycles: [1, 2, 3].map((cycle) => ({
    cycle,
    opened: true,
    returned: true,
    cameraEmptyAfterBack: true,
    foregroundAfterBack: true,
  })),
  maxBatteryTemperature: 320,
  maxThermalStatus: 0,
  thermalSensorPeaksC: {
    cpu: 47.2,
    gpu: 45.1,
    skin: 43.445,
  },
  thermalSensorSampleCount: 41,
  baselinePssKb: 81582,
  maxPssKb: 103209,
  pssDeltaKb: 21627,
  finalJankPercent: 1.11,
  externalPowerConnected: true,
  powerMeasurementValid: false,
  cameraLeak: false,
  audioLeak: false,
  crashBufferEmpty: true,
  anrObserved: false,
  foregroundFinal: true,
  thermalWithinBudget: true,
  memoryWithinBudget: true,
  jankWithinBudget: true,
  protectedV8Present: true,
  protectedV8Unchanged: true,
  passed: true,
};

function runFixture(name, mutate = () => {}) {
  const summary = structuredClone(baseSummary);
  mutate(summary);
  const summaryPath = path.join(tempRoot, `${name}.json`);
  fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  return spawnSync(process.execPath, [
    validatorPath,
    "--summary",
    summaryPath,
    "--min-duration",
    "1200",
    "--min-camera-cycles",
    "3",
  ], {
    cwd: root,
    encoding: "utf8",
  });
}

function writeSummary(directory, summary) {
  fs.mkdirSync(directory, { recursive: true });
  const summaryPath = path.join(directory, "summary.json");
  fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  return summaryPath;
}

function expectFailure(name, mutate, pattern) {
  const result = runFixture(name, mutate);
  assert.notEqual(result.status, 0, `${name} unexpectedly passed`);
  assert.match(`${result.stdout}\n${result.stderr}`, pattern);
}

try {
  assert.ok(fs.existsSync(validatorPath), "V9 Air3 soak summary validator is missing");
  assert.match(packageJson.scripts["validate:v9-air3-soak-summary"], /--latest-root output/);
  assert.doesNotMatch(packageJson.scripts["validate:v9-air3-soak-summary"], /20260809/);

  const valid = runFixture("valid");
  assert.equal(valid.status, 0, valid.stderr || valid.stdout);
  assert.match(valid.stdout, /validated/i);

  const latestRoot = path.join(tempRoot, "latest");
  const formalPath = writeSummary(
    path.join(latestRoot, "air3-v9-hardware-soak-20260812-final-r2"),
    structuredClone(baseSummary),
  );
  const smokeSummary = structuredClone(baseSummary);
  smokeSummary.durationSecondsRequested = 60;
  smokeSummary.durationSecondsObserved = 62;
  smokeSummary.requiredSampleCount = 3;
  smokeSummary.sampleCount = 3;
  smokeSummary.thermalSensorSampleCount = 3;
  const smokePath = writeSummary(
    path.join(latestRoot, "air3-v9-hardware-soak-20260812-sampling-smoke"),
    smokeSummary,
  );
  const now = new Date();
  fs.utimesSync(formalPath, new Date(now.getTime() - 2000), new Date(now.getTime() - 2000));
  fs.utimesSync(smokePath, now, now);
  const latest = spawnSync(process.execPath, [
    validatorPath,
    "--latest-root",
    latestRoot,
    "--min-duration",
    "1200",
    "--min-camera-cycles",
    "3",
  ], {
    cwd: root,
    encoding: "utf8",
  });
  assert.equal(latest.status, 0, latest.stderr || latest.stdout);
  assert.match(latest.stdout, /air3-v9-hardware-soak-20260812-final-r2/);
  assert.doesNotMatch(latest.stdout, /sampling-smoke/);

  expectFailure("duration", (summary) => { summary.durationSecondsObserved = 1199; }, /duration/i);
  expectFailure("sample-coverage", (summary) => {
    summary.sampleCount = 40;
    summary.thermalSensorSampleCount = 40;
    summary.sampleCoveragePassed = false;
  }, /sample coverage/i);
  expectFailure("cycle-count", (summary) => { summary.cameraCyclesRequested = 2; }, /camera cycle/i);
  expectFailure("cycle-result", (summary) => { summary.cycles[1].cameraEmptyAfterBack = false; }, /camera cycle/i);
  expectFailure("camera-leak", (summary) => { summary.cameraLeak = true; }, /camera leak/i);
  expectFailure("audio-leak", (summary) => { summary.audioLeak = true; }, /audio leak/i);
  expectFailure("crash", (summary) => { summary.crashBufferEmpty = false; }, /crash/i);
  expectFailure("anr", (summary) => { summary.anrObserved = true; }, /ANR/i);
  expectFailure("thermal", (summary) => { summary.maxThermalStatus = 3; }, /thermal/i);
  expectFailure("memory", (summary) => { summary.pssDeltaKb = 30001; }, /PSS/i);
  expectFailure("jank", (summary) => { summary.finalJankPercent = 10.01; }, /jank/i);
  expectFailure("v8", (summary) => { summary.protectedV8Unchanged = false; }, /V8/i);
  expectFailure("failed-summary", (summary) => { summary.passed = false; }, /passed/i);
  expectFailure("power-claim", (summary) => { summary.powerMeasurementValid = true; }, /power measurement/i);
  expectFailure("thermal-peaks", (summary) => { delete summary.thermalSensorPeaksC.skin; }, /thermal sensor/i);
  expectFailure("thermal-samples", (summary) => { summary.thermalSensorSampleCount = 40; }, /thermal sensor sample/i);
} finally {
  fs.rmSync(tempRoot, { recursive: true, force: true });
}

console.log("V9 Air3 soak summary validator tests passed.");
