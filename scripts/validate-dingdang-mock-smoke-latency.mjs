import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const args = process.argv.slice(2);

function fail(message, details = {}) {
  console.error(JSON.stringify({ ok: false, message, ...details }, null, 2));
  process.exit(1);
}

function readJson(filePath) {
  if (!fs.existsSync(filePath)) {
    fail("summary file does not exist", { summaryPath: filePath });
  }
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

const requiredChecks = [
  "mockBackend",
  "adbReverse",
  "cameraUiVisible",
  "returnedToChatAfterPhoto",
  "imageAttached",
  "asrPartialVisible",
  "finalTranscriptVisible",
  "gptStreamVisible",
  "inDingdang",
  "restoredStandard",
];

const latencyChecks = [
  "cameraUi",
  "photoReturn",
  "asrPartial",
  "gptFirstDelta",
  "totalInteraction",
];

function latestMockSummaryPath() {
  const tmpDir = path.join(root, "tmp");
  if (!fs.existsSync(tmpDir)) {
    return null;
  }

  const candidates = fs.readdirSync(tmpDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("mock-live-smoke-"))
    .map((entry) => path.join(tmpDir, entry.name, "summary.json"))
    .filter((summaryPath) => fs.existsSync(summaryPath))
    .map((summaryPath) => ({ summaryPath, mtimeMs: fs.statSync(summaryPath).mtimeMs }))
    .sort((a, b) => b.mtimeMs - a.mtimeMs);

  return candidates[0]?.summaryPath || null;
}

function validateSummary(summary) {
  const failures = [];
  for (const key of requiredChecks) {
    if (summary[key] !== true) {
      failures.push({ key, expected: true, actual: summary[key] });
    }
  }

  const latencyMs = summary.latencyMs;
  const latencyBudgetsMs = summary.latencyBudgetsMs;
  if (!latencyMs || typeof latencyMs !== "object") {
    failures.push({ key: "latencyMs", expected: "object", actual: latencyMs });
  }
  if (!latencyBudgetsMs || typeof latencyBudgetsMs !== "object") {
    failures.push({ key: "latencyBudgetsMs", expected: "object", actual: latencyBudgetsMs });
  }

  for (const key of latencyChecks) {
    if (!Number.isFinite(latencyMs?.[key])) {
      failures.push({ key: `latencyMs.${key}`, expected: "finite number", actual: latencyMs?.[key] });
    }
    if (!Number.isFinite(latencyBudgetsMs?.[key])) {
      failures.push({
        key: `latencyBudgetsMs.${key}`,
        expected: "finite number",
        actual: latencyBudgetsMs?.[key],
      });
    }
    if (Number.isFinite(latencyMs?.[key]) &&
        Number.isFinite(latencyBudgetsMs?.[key]) &&
        latencyMs[key] > latencyBudgetsMs[key]) {
      failures.push({
        key: `latencyMs.${key}`,
        expected: `<= ${latencyBudgetsMs[key]}`,
        actual: latencyMs[key],
      });
    }
  }

  if (latencyMs?.gptFirstDeltaSource !== "logcat") {
    failures.push({
      key: "latencyMs.gptFirstDeltaSource",
      expected: "logcat",
      actual: latencyMs?.gptFirstDeltaSource,
    });
  }
  if (summary.latencyWithinBudget !== true) {
    failures.push({
      key: "latencyWithinBudget",
      expected: true,
      actual: summary.latencyWithinBudget,
    });
  }

  return failures;
}

function selfTest() {
  const valid = {
    mockBackend: true,
    adbReverse: true,
    cameraUiVisible: true,
    returnedToChatAfterPhoto: true,
    imageAttached: true,
    asrPartialVisible: true,
    finalTranscriptVisible: true,
    gptStreamVisible: true,
    inDingdang: true,
    restoredStandard: true,
    latencyWithinBudget: true,
    latencyMs: {
      cameraUi: 1000,
      photoReturn: 2000,
      asrPartial: 300,
      gptFirstDelta: 400,
      totalInteraction: 9000,
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

  const validFailures = validateSummary(valid);
  const invalidFailures = validateSummary({
    ...valid,
    mockBackend: false,
    latencyWithinBudget: false,
    latencyMs: {
      ...valid.latencyMs,
      gptFirstDelta: 9000,
      gptFirstDeltaSource: "ui",
    },
  });
  if (validFailures.length > 0 || invalidFailures.length === 0) {
    fail("self-test failed", { validFailures, invalidFailures });
  }

  console.log(JSON.stringify({ ok: true, selfTest: true, invalidFailureCount: invalidFailures.length }, null, 2));
}

if (args.includes("--self-test")) {
  selfTest();
  process.exit(0);
}

const explicitPath = args.find((arg) => !arg.startsWith("-"));
const absoluteSummaryPath = explicitPath ? path.resolve(root, explicitPath) : latestMockSummaryPath();
if (!absoluteSummaryPath) {
  fail("no mock live smoke summary found", {
    expected: "tmp/mock-live-smoke-*/summary.json or an explicit summary path",
  });
}

const summary = readJson(absoluteSummaryPath);
const failures = validateSummary(summary);
if (failures.length > 0) {
  fail("mock smoke summary failed validation", {
    summaryPath: path.relative(root, absoluteSummaryPath),
    failures,
  });
}

console.log(JSON.stringify({
  ok: true,
  summaryPath: path.relative(root, absoluteSummaryPath),
  latencyMs: summary.latencyMs,
  latencyBudgetsMs: summary.latencyBudgetsMs,
}, null, 2));
