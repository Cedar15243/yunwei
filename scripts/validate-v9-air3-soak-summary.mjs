import fs from "node:fs";
import path from "node:path";

const EXPECTED = Object.freeze({
  provider: "local_device",
  packageName: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
});

function parseArgs(argv) {
  const options = {
    summary: "",
    latestRoot: "",
    minDuration: 1200,
    minCameraCycles: 3,
    maxThermalStatus: 2,
    maxBatteryTemperature: 450,
    maxPssDeltaKb: 30000,
    maxJankPercent: 10,
  };
  const names = new Map([
    ["--summary", "summary"],
    ["--latest-root", "latestRoot"],
    ["--min-duration", "minDuration"],
    ["--min-camera-cycles", "minCameraCycles"],
    ["--max-thermal-status", "maxThermalStatus"],
    ["--max-battery-temperature", "maxBatteryTemperature"],
    ["--max-pss-delta-kb", "maxPssDeltaKb"],
    ["--max-jank-percent", "maxJankPercent"],
  ]);

  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index];
    const key = names.get(name);
    if (!key || index + 1 >= argv.length) {
      throw new Error(`Unknown or incomplete argument: ${name}`);
    }
    const value = argv[index + 1];
    options[key] = key === "summary" || key === "latestRoot" ? value : Number(value);
    index += 1;
  }

  if (!options.summary && !options.latestRoot) {
    throw new Error("--summary or --latest-root is required");
  }
  if (options.summary && options.latestRoot) {
    throw new Error("--summary and --latest-root are mutually exclusive");
  }
  for (const key of [
    "minDuration",
    "minCameraCycles",
    "maxThermalStatus",
    "maxBatteryTemperature",
    "maxPssDeltaKb",
    "maxJankPercent",
  ]) {
    if (!Number.isFinite(options[key]) || options[key] < 0) {
      throw new Error(`${key} must be a non-negative number`);
    }
  }
  return options;
}

function resolveLatestSummary(root) {
  const candidates = fs.readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() &&
      /^air3-v9-hardware-soak-.+-final(?:-r\d+)?$/.test(entry.name))
    .map((entry) => path.join(root, entry.name, "summary.json"))
    .filter((candidate) => fs.existsSync(candidate))
    .map((candidate) => ({ candidate, mtimeMs: fs.statSync(candidate).mtimeMs }))
    .sort((left, right) => right.mtimeMs - left.mtimeMs);
  if (candidates.length === 0) {
    throw new Error(`no Air3 hardware soak summary found under ${root}`);
  }
  return candidates[0].candidate;
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function validate(summary, options) {
  const failures = [];
  const fail = (message) => failures.push(message);

  for (const [key, value] of Object.entries(EXPECTED)) {
    if (summary[key] !== value) {
      fail(`${key} must be ${value}`);
    }
  }

  if (!isFiniteNumber(summary.durationSecondsRequested) ||
      summary.durationSecondsRequested < options.minDuration ||
      !isFiniteNumber(summary.durationSecondsObserved) ||
      summary.durationSecondsObserved < summary.durationSecondsRequested ||
      summary.durationSecondsObserved < options.minDuration) {
    fail(`duration must reach at least ${options.minDuration} seconds`);
  }

  const expectedSampleCount = Number.isInteger(summary.sampleIntervalSeconds) &&
    summary.sampleIntervalSeconds > 0 &&
    isFiniteNumber(summary.durationSecondsRequested)
    ? Math.ceil(summary.durationSecondsRequested / summary.sampleIntervalSeconds) + 1
    : NaN;
  if (!Number.isInteger(summary.sampleIntervalSeconds) ||
      summary.sampleIntervalSeconds <= 0 ||
      !Number.isInteger(summary.requiredSampleCount) ||
      summary.requiredSampleCount !== expectedSampleCount ||
      !Number.isInteger(summary.sampleCount) ||
      summary.sampleCount < summary.requiredSampleCount ||
      summary.sampleCoveragePassed !== true) {
    fail("sample coverage must include the scheduled baseline and terminal observation");
  }

  const cycles = Array.isArray(summary.cycles) ? summary.cycles : [];
  const cyclesValid = Number.isInteger(summary.cameraCyclesRequested) &&
    summary.cameraCyclesRequested >= options.minCameraCycles &&
    summary.cameraCyclesPassed === true &&
    cycles.length === summary.cameraCyclesRequested &&
    cycles.every((cycle) => cycle?.opened === true &&
      cycle?.returned === true &&
      cycle?.cameraEmptyAfterBack === true &&
      cycle?.foregroundAfterBack === true);
  if (!cyclesValid) {
    fail(`camera cycle evidence must contain at least ${options.minCameraCycles} clean cycles`);
  }

  if (summary.cameraLeak !== false) fail("camera leak must be false");
  if (summary.audioLeak !== false) fail("audio leak must be false");
  if (summary.crashBufferEmpty !== true) fail("crash buffer must be empty");
  if (summary.anrObserved !== false) fail("ANR must not be observed");
  if (summary.foregroundFinal !== true) fail("V9 must remain foreground at the final sample");

  if (!isFiniteNumber(summary.maxThermalStatus) ||
      summary.maxThermalStatus > options.maxThermalStatus ||
      summary.thermalWithinBudget !== true) {
    fail(`thermal status must not exceed ${options.maxThermalStatus}`);
  }
  if (!isFiniteNumber(summary.maxBatteryTemperature) ||
      summary.maxBatteryTemperature > options.maxBatteryTemperature) {
    fail(`battery temperature must not exceed ${options.maxBatteryTemperature}`);
  }

  const peaks = summary.thermalSensorPeaksC;
  if (!peaks || !["cpu", "gpu", "skin"].every((key) => isFiniteNumber(peaks[key]))) {
    fail("thermal sensor peaks must include finite CPU, GPU and skin values");
  }
  if (!Number.isInteger(summary.thermalSensorSampleCount) ||
      summary.thermalSensorSampleCount !== summary.sampleCount) {
    fail("thermal sensor sample count must equal sampleCount");
  }

  if (!isFiniteNumber(summary.pssDeltaKb) ||
      summary.pssDeltaKb > options.maxPssDeltaKb ||
      summary.memoryWithinBudget !== true) {
    fail(`PSS delta must not exceed ${options.maxPssDeltaKb} KB`);
  }
  if (!isFiniteNumber(summary.finalJankPercent) ||
      summary.finalJankPercent > options.maxJankPercent ||
      summary.jankWithinBudget !== true) {
    fail(`jank must not exceed ${options.maxJankPercent}%`);
  }

  if (typeof summary.externalPowerConnected !== "boolean" ||
      typeof summary.powerMeasurementValid !== "boolean" ||
      summary.powerMeasurementValid !== !summary.externalPowerConnected) {
    fail("power measurement validity must be false whenever external power is connected");
  }

  if (summary.protectedV8Present !== true || summary.protectedV8Unchanged !== true) {
    fail("protected V8 package must remain present and unchanged");
  }
  if (summary.passed !== true) {
    fail("summary passed must be true");
  }
  return failures;
}

try {
  const options = parseArgs(process.argv.slice(2));
   const summaryPath = options.latestRoot
     ? resolveLatestSummary(path.resolve(options.latestRoot))
     : path.resolve(options.summary);
  const summary = JSON.parse(fs.readFileSync(summaryPath, "utf8").replace(/^\uFEFF/, ""));
  const failures = validate(summary, options);
  if (failures.length > 0) {
    console.error("V9 Air3 soak summary validation failed:");
    for (const failure of failures) console.error(`- ${failure}`);
    process.exit(1);
  }
  console.log(`V9 Air3 soak summary validated: ${summaryPath}`);
} catch (error) {
  console.error(`V9 Air3 soak summary validation failed: ${error.message}`);
  process.exit(1);
}
