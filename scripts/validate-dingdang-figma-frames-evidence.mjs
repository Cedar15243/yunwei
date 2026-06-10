import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const args = process.argv.slice(2);
const requiredFrameIds = [
  "chat-main",
  "camera-capture",
  "asr-streaming",
  "gpt-streaming",
  "shortcut-flow",
];

function fail(message, details = {}) {
  console.error(JSON.stringify({ ok: false, message, ...details }, null, 2));
  process.exit(1);
}

function readJson(filePath) {
  if (!fs.existsSync(filePath)) {
    fail("Figma frames evidence file does not exist", { evidencePath: filePath });
  }
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

function latestEvidencePath() {
  const tmpDir = path.join(root, "tmp");
  if (!fs.existsSync(tmpDir)) {
    return null;
  }
  const candidates = fs.readdirSync(tmpDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("dingdang-figma-frames-"))
    .map((entry) => path.join(tmpDir, entry.name, "evidence.json"))
    .filter((evidencePath) => fs.existsSync(evidencePath))
    .map((evidencePath) => ({
      evidencePath,
      mtimeMs: fs.statSync(evidencePath).mtimeMs,
    }))
    .sort((a, b) => b.mtimeMs - a.mtimeMs);
  return candidates[0]?.evidencePath || null;
}

function validateEvidence(evidence) {
  const failures = [];
  if (evidence.ok !== true) {
    failures.push({ key: "ok", expected: true, actual: evidence.ok });
  }
  if (evidence.figmaFileKey !== "pDX9LEKARKp5GGchwuFLz4") {
    failures.push({
      key: "figmaFileKey",
      expected: "pDX9LEKARKp5GGchwuFLz4",
      actual: evidence.figmaFileKey,
    });
  }
  if (evidence.metadataVerified !== true) {
    failures.push({ key: "metadataVerified", expected: true, actual: evidence.metadataVerified });
  }
  if (!Array.isArray(evidence.frames)) {
    failures.push({ key: "frames", expected: "array", actual: typeof evidence.frames });
    return failures;
  }

  const frameMap = new Map(evidence.frames.map((frame) => [frame.id, frame]));
  for (const id of requiredFrameIds) {
    const frame = frameMap.get(id);
    if (!frame) {
      failures.push({ key: `frames.${id}`, expected: "present", actual: "missing" });
      continue;
    }
    if (typeof frame.nodeId !== "string" || !frame.nodeId.includes(":")) {
      failures.push({ key: `frames.${id}.nodeId`, expected: "Figma node id", actual: frame.nodeId });
    }
    if (frame.metadataVerified !== true) {
      failures.push({ key: `frames.${id}.metadataVerified`, expected: true, actual: frame.metadataVerified });
    }
    if (frame.screenshotVerified !== true) {
      failures.push({ key: `frames.${id}.screenshotVerified`, expected: true, actual: frame.screenshotVerified });
    }
    if (!Number.isFinite(frame.width) || frame.width < 1200) {
      failures.push({ key: `frames.${id}.width`, expected: ">= 1200", actual: frame.width });
    }
    if (!Number.isFinite(frame.height) || frame.height < 800) {
      failures.push({ key: `frames.${id}.height`, expected: ">= 800", actual: frame.height });
    }
  }

  return failures;
}

function selfTest() {
  const valid = {
    ok: true,
    figmaFileKey: "pDX9LEKARKp5GGchwuFLz4",
    metadataVerified: true,
    frames: requiredFrameIds.map((id, index) => ({
      id,
      nodeId: `${100 + index}:1`,
      metadataVerified: true,
      screenshotVerified: true,
      width: 1440,
      height: 900,
    })),
  };
  const validFailures = validateEvidence(valid);
  const invalidFailures = validateEvidence({
    ...valid,
    metadataVerified: false,
    frames: valid.frames.slice(0, 4),
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
const evidencePath = explicitPath ? path.resolve(root, explicitPath) : latestEvidencePath();
if (!evidencePath) {
  fail("no Figma frames evidence found", {
    expected: "tmp/dingdang-figma-frames-*/evidence.json or an explicit evidence path",
  });
}

const evidence = readJson(evidencePath);
const failures = validateEvidence(evidence);
if (failures.length > 0) {
  fail("Figma frames evidence failed validation", {
    evidencePath: path.relative(root, evidencePath),
    failures,
  });
}

console.log(JSON.stringify({
  ok: true,
  evidencePath: path.relative(root, evidencePath),
  figmaFileKey: evidence.figmaFileKey,
  frameCount: evidence.frames.length,
  frameIds: requiredFrameIds,
}, null, 2));
