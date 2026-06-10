import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const summaryPath = process.argv[2] || "tmp/mock-live-smoke-latency-20260610/summary.json";
const absoluteSummaryPath = path.resolve(root, summaryPath);

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

const summary = readJson(absoluteSummaryPath);
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

const failedBooleans = requiredChecks.filter((key) => summary[key] !== true);
if (failedBooleans.length > 0) {
  fail("mock smoke summary has failed required checks", { failedBooleans });
}

const latencyMs = summary.latencyMs;
const latencyBudgetsMs = summary.latencyBudgetsMs;
if (!latencyMs || typeof latencyMs !== "object") {
  fail("mock smoke summary is missing latencyMs");
}
if (!latencyBudgetsMs || typeof latencyBudgetsMs !== "object") {
  fail("mock smoke summary is missing latencyBudgetsMs");
}

const latencyChecks = [
  "cameraUi",
  "photoReturn",
  "asrPartial",
  "gptFirstDelta",
  "totalInteraction",
];

const missingLatency = latencyChecks.filter(
  (key) => !Number.isFinite(latencyMs[key]) || !Number.isFinite(latencyBudgetsMs[key]),
);
if (missingLatency.length > 0) {
  fail("mock smoke summary has missing latency measurements or budgets", { missingLatency });
}

const overBudget = latencyChecks
  .filter((key) => latencyMs[key] > latencyBudgetsMs[key])
  .map((key) => ({ key, actualMs: latencyMs[key], budgetMs: latencyBudgetsMs[key] }));
if (overBudget.length > 0) {
  fail("mock smoke latency exceeded budget", { overBudget });
}

if (latencyMs.gptFirstDeltaSource !== "logcat") {
  fail("mock smoke must measure GPT first delta from app logcat", {
    gptFirstDeltaSource: latencyMs.gptFirstDeltaSource,
  });
}

if (summary.latencyWithinBudget !== true) {
  fail("mock smoke summary latencyWithinBudget is not true", {
    latencyWithinBudget: summary.latencyWithinBudget,
  });
}

console.log(JSON.stringify({
  ok: true,
  summaryPath,
  latencyMs,
  latencyBudgetsMs,
}, null, 2));
