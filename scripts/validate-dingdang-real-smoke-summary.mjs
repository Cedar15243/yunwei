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

function latestRealSummaryPath() {
  const tmpDir = path.join(root, "tmp");
  if (!fs.existsSync(tmpDir)) {
    return null;
  }

  const candidates = fs.readdirSync(tmpDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("real-live-smoke-"))
    .map((entry) => path.join(tmpDir, entry.name, "summary.json"))
    .filter((summaryPath) => fs.existsSync(summaryPath))
    .map((summaryPath) => ({
      summaryPath,
      mtimeMs: fs.statSync(summaryPath).mtimeMs,
    }))
    .sort((a, b) => b.mtimeMs - a.mtimeMs);

  return candidates[0]?.summaryPath || null;
}

function assertTrue(summary, key, failures) {
  if (summary[key] !== true) {
    failures.push({ key, expected: true, actual: summary[key] });
  }
}

function assertFiniteNumber(container, key, failures, label) {
  if (!Number.isFinite(container?.[key])) {
    failures.push({ key: `${label}.${key}`, expected: "finite number", actual: container?.[key] });
  }
}

function validateSummary(summary) {
  const failures = [];

  if (summary.packageName !== "com.codex.air3nativecamera.dingdangexpert.v9") {
    failures.push({
      key: "packageName",
      expected: "com.codex.air3nativecamera.dingdangexpert.v9",
      actual: summary.packageName,
    });
  }
  if (summary.versionCode !== 900000) {
    failures.push({ key: "versionCode", expected: 900000, actual: summary.versionCode });
  }
  if (summary.versionName !== "9.0.0") {
    failures.push({ key: "versionName", expected: "9.0.0", actual: summary.versionName });
  }

  if (summary.provider !== "real") {
    failures.push({ key: "provider", expected: "real", actual: summary.provider });
  }

  [
    "backendBaseUrlProvided",
    "healthOk",
    "cameraUiVisible",
    "returnedToChatAfterPhoto",
    "imageAttached",
    "photoContextReady",
    "asrPartialVisible",
    "asrFinalVisible",
    "asrFinalSourceObserved",
    "asrFinalFromCloud",
    "gptFirstDeltaObserved",
    "inDingdang",
    "latencyWithinBudget",
  ].forEach((key) => assertTrue(summary, key, failures));

  if (typeof summary.photoContextImageId !== "string" ||
      summary.photoContextImageId.length === 0 ||
      summary.photoContextImageId === "local-photo") {
    failures.push({
      key: "photoContextImageId",
      expected: "non-local server image id",
      actual: summary.photoContextImageId,
    });
  }
  if (summary.asrProviderSource !== "cloud") {
    failures.push({ key: "asrProviderSource", expected: "cloud", actual: summary.asrProviderSource });
  }

  const latencyMs = summary.latencyMs;
  const latencyBudgetsMs = summary.latencyBudgetsMs;
  if (!latencyMs || typeof latencyMs !== "object") {
    failures.push({ key: "latencyMs", expected: "object", actual: latencyMs });
  }
  if (!latencyBudgetsMs || typeof latencyBudgetsMs !== "object") {
    failures.push({ key: "latencyBudgetsMs", expected: "object", actual: latencyBudgetsMs });
  }

  ["cameraUi", "photoReturn", "asrPartial", "asrFinal", "gptFirstDelta", "totalInteraction"].forEach((key) => {
    assertFiniteNumber(latencyMs, key, failures, "latencyMs");
  });
  ["cameraUi", "photoReturn", "asrPartial", "gptFirstDelta", "totalInteraction"].forEach((key) => {
    assertFiniteNumber(latencyBudgetsMs, key, failures, "latencyBudgetsMs");
  });

  ["cameraUi", "photoReturn", "asrPartial", "gptFirstDelta", "totalInteraction"].forEach((key) => {
    if (Number.isFinite(latencyMs?.[key]) && Number.isFinite(latencyBudgetsMs?.[key]) && latencyMs[key] > latencyBudgetsMs[key]) {
      failures.push({
        key: `latencyMs.${key}`,
        expected: `<= ${latencyBudgetsMs[key]}`,
        actual: latencyMs[key],
      });
    }
  });

  if (latencyMs?.asrPartialSource !== "logcat") {
    failures.push({ key: "latencyMs.asrPartialSource", expected: "logcat", actual: latencyMs?.asrPartialSource });
  }
  if (latencyMs?.asrFinalSource !== "logcat") {
    failures.push({ key: "latencyMs.asrFinalSource", expected: "logcat", actual: latencyMs?.asrFinalSource });
  }
  if (latencyMs?.gptFirstDeltaSource !== "logcat") {
    failures.push({ key: "latencyMs.gptFirstDeltaSource", expected: "logcat", actual: latencyMs?.gptFirstDeltaSource });
  }

  return failures;
}

function selfTest() {
  const valid = {
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

  const validFailures = validateSummary(valid);
  const invalidFailures = validateSummary({
    ...valid,
    provider: "mock",
    healthOk: false,
    photoContextReady: false,
    photoContextImageId: "local-photo",
    asrFinalFromCloud: false,
    asrProviderSource: "local-after-primary-failure",
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
const summaryPath = explicitPath ? path.resolve(root, explicitPath) : latestRealSummaryPath();
if (!summaryPath) {
  fail("no real provider live smoke summary found", {
    expected: "tmp/real-live-smoke-*/summary.json or an explicit summary path",
  });
}

const summary = readJson(summaryPath);
const failures = validateSummary(summary);
if (failures.length > 0) {
  fail("real provider live smoke summary failed validation", {
    summaryPath: path.relative(root, summaryPath),
    failures,
  });
}

console.log(JSON.stringify({
  ok: true,
  summaryPath: path.relative(root, summaryPath),
  latencyMs: summary.latencyMs,
  latencyBudgetsMs: summary.latencyBudgetsMs,
}, null, 2));
